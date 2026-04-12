package com.conch.ssh.client;

import com.conch.ssh.model.SshHost;
import com.intellij.openapi.diagnostic.Logger;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntry;
import org.apache.sshd.client.config.hosts.DefaultConfigFileHostEntryResolver;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.auth.password.PasswordAuthenticationReporter;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.PublicKeyAuthenticationReporter;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.ClientSessionCreator;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.core.CoreModuleProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin MINA SSHD wrapper that owns a single {@link SshClient} instance
 * and exposes a {@link #connect(SshHost, SshResolvedCredential, ServerKeyVerifier)}
 * method that goes through the full
 * TCP → key-verify → auth → shell-channel flow.
 *
 * <p>The underlying {@code SshClient} internally pools network resources
 * and spawns IO threads, so we create-and-start it once, reuse across
 * sessions, and tear it down only on {@link #shutdown()}. When this class
 * is promoted to an IntelliJ {@code <applicationService>} in a later
 * phase, its lifetime matches the application's.
 *
 * <p>All MINA exceptions are mapped to {@link SshConnectException} with
 * a typed {@link SshConnectException.Kind} so the UI layer can decide
 * how to recover (re-prompt for credentials, show MITM warning, etc.).
 */
public final class ConchSshClient {

    private static final Logger LOG = Logger.getInstance(ConchSshClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(15);

    private static final Pattern TOKEN_PATTERN =
        Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\S+");

    private volatile SshClient client;

    public ConchSshClient() {
    }

    /**
     * Connect to {@code host}, verify its key, authenticate with
     * {@code credential}, and open an interactive shell channel.
     *
     * @param host       the host entry to connect to
     * @param credential the credential to authenticate with. Ownership
     *                   stays with the caller — this method does NOT
     *                   close it.
     * @param verifier   host-key verifier (usually a
     *                   {@code ConchServerKeyVerifier} backed by the
     *                   {@code KnownHostsFile})
     * @return an {@link SshConnection} owning the session and channel
     * @throws SshConnectException on any connect/auth/channel failure
     */
    public @NotNull SshConnection connect(
        @NotNull SshHost host,
        @NotNull SshResolvedCredential credential,
        @NotNull ServerKeyVerifier verifier
    ) throws SshConnectException {
        SshClient mina = ensureStarted();
        LOG.info("Conch SSH: connect start host=" + host.host() + ":" + host.port()
            + " user=" + credential.username()
            + " proxyCommand=" + (host.proxyCommand() != null)
            + " proxyJump=" + (host.proxyJump() != null ? host.proxyJump() : "<none>"));

        // MINA's built-in ProxyJump support establishes jump sessions inside
        // connect(), so the verifier must be configured on the client before
        // connect() starts.
        mina.setServerKeyVerifier(verifier);

        ClientSession session;
        try {
            ConnectFuture connectFuture = connectFutureFor(mina, host, credential);
            session = connectFuture.verify(CONNECT_TIMEOUT).getSession();
            SshdSocketAddress target = session.getAttribute(ClientSessionCreator.TARGET_SERVER);
            LOG.info("Conch SSH: connect session established connectAddress="
                + session.getConnectAddress()
                + " targetServer=" + (target == null ? "<none>" : target.getHostName() + ":" + target.getPort()));
        } catch (IllegalArgumentException e) {
            LOG.warn("Conch SSH: invalid proxy config for host="
                + host.host() + ":" + host.port() + " -> " + e.getMessage(), e);
            throw new SshConnectException(
                SshConnectException.Kind.INVALID_PROXY_CONFIG,
                "Invalid SSH proxy configuration: " + e.getMessage(),
                e);
        } catch (IOException e) {
            LOG.warn("Conch SSH: network/connect failure host="
                + host.host() + ":" + host.port() + " -> " + e.getMessage(), e);
            throw new SshConnectException(
                SshConnectException.Kind.HOST_UNREACHABLE,
                "Could not reach " + host.host() + ":" + host.port() + " — " + e.getMessage(),
                e);
        }

        // Keep the per-session verifier set too so direct and proxied sessions
        // share the same check path.
        session.setServerKeyVerifier(verifier);

        // Attach identities.
        try {
            attachIdentities(session, credential);
        } catch (IOException | GeneralSecurityException e) {
            safeClose(session);
            throw new SshConnectException(
                SshConnectException.Kind.AUTH_FAILED,
                "Could not load key material: " + e.getMessage(),
                e);
        }

        configureSessionAuthPreferences(session, credential);

        // Authenticate.
        try {
            AuthFuture auth = session.auth();
            auth.verify(AUTH_TIMEOUT);
            if (!auth.isSuccess()) {
                safeClose(session);
                Throwable cause = auth.getException();
                throw new SshConnectException(
                    SshConnectException.Kind.AUTH_FAILED,
                    "SSH authentication failed",
                    cause != null ? cause : new IOException("auth future did not succeed"));
            }
        } catch (IOException e) {
            safeClose(session);
            // MINA throws IOException wrapping MITM / cipher / kex failures.
            // Detect the known-hosts rejection case by message content — not
            // pretty, but MINA doesn't surface a structured cause for this.
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            SshConnectException.Kind kind =
                msg.contains("server key") || msg.contains("host key") || msg.contains("rejected")
                    ? SshConnectException.Kind.HOST_KEY_REJECTED
                    : SshConnectException.Kind.AUTH_FAILED;
            LOG.warn("Conch SSH: auth failure host="
                + host.host() + ":" + host.port() + " kind=" + kind + " -> " + e.getMessage(), e);
            throw new SshConnectException(kind, "Authentication failed: " + e.getMessage(), e);
        }

        // Open the shell channel with a sensible initial PTY size.
        // JediTerm will send a window-change to the real size as soon as
        // the terminal widget is realized, so the 80x24 default is only
        // briefly visible — if at all.
        try {
            org.apache.sshd.client.channel.ChannelShell channel = session.createShellChannel();
            channel.setPtyType("xterm-256color");
            channel.setPtyColumns(80);
            channel.setPtyLines(24);
            channel.open().verify(AUTH_TIMEOUT);
            LOG.info("Conch SSH: connect success host=" + host.host() + ":" + host.port()
                + " sessionId=" + session.getSessionId());
            return new SshConnection(session, channel);
        } catch (IOException e) {
            safeClose(session);
            LOG.warn("Conch SSH: channel open failed host="
                + host.host() + ":" + host.port() + " -> " + e.getMessage(), e);
            throw new SshConnectException(
                SshConnectException.Kind.CHANNEL_OPEN_FAILED,
                "Could not open shell channel on " + host.host() + ": " + e.getMessage(),
                e);
        }
    }

    /**
     * Connect and authenticate without opening a shell channel. Returns
     * the raw {@link ClientSession} for callers that need to do something
     * other than a terminal — port forwarding, SFTP, etc.
     *
     * <p>The caller owns the returned session and MUST close it when done.
     */
    public @NotNull ClientSession connectSession(
        @NotNull SshHost host,
        @NotNull SshResolvedCredential credential,
        @NotNull ServerKeyVerifier verifier
    ) throws SshConnectException {
        SshClient mina = ensureStarted();
        LOG.info("Conch SSH: connectSession (no shell) host=" + host.host() + ":" + host.port());

        mina.setServerKeyVerifier(verifier);

        ClientSession session;
        try {
            ConnectFuture connectFuture = connectFutureFor(mina, host, credential);
            session = connectFuture.verify(CONNECT_TIMEOUT).getSession();
        } catch (IllegalArgumentException e) {
            throw new SshConnectException(
                SshConnectException.Kind.INVALID_PROXY_CONFIG,
                "Invalid SSH proxy configuration: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new SshConnectException(
                SshConnectException.Kind.HOST_UNREACHABLE,
                "Could not reach " + host.host() + ":" + host.port() + " — " + e.getMessage(), e);
        }

        session.setServerKeyVerifier(verifier);

        try {
            attachIdentities(session, credential);
        } catch (IOException | GeneralSecurityException e) {
            safeClose(session);
            throw new SshConnectException(
                SshConnectException.Kind.AUTH_FAILED,
                "Could not load key material: " + e.getMessage(), e);
        }

        configureSessionAuthPreferences(session, credential);

        try {
            AuthFuture auth = session.auth();
            auth.verify(AUTH_TIMEOUT);
            if (!auth.isSuccess()) {
                safeClose(session);
                Throwable cause = auth.getException();
                throw new SshConnectException(
                    SshConnectException.Kind.AUTH_FAILED,
                    "SSH authentication failed",
                    cause != null ? cause : new IOException("auth future did not succeed"));
            }
        } catch (IOException e) {
            safeClose(session);
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            SshConnectException.Kind kind =
                msg.contains("server key") || msg.contains("host key") || msg.contains("rejected")
                    ? SshConnectException.Kind.HOST_KEY_REJECTED
                    : SshConnectException.Kind.AUTH_FAILED;
            throw new SshConnectException(kind, "Authentication failed: " + e.getMessage(), e);
        }

        return session;
    }

    /**
     * Tear down the underlying MINA client. After this call, {@link #connect}
     * will re-create the client on next use. Safe to call when no client
     * has been started.
     */
    public synchronized void shutdown() {
        if (client != null) {
            try {
                client.stop();
            } catch (Exception ignored) {
                // best effort
            }
            client = null;
        }
    }

    // -- internals ------------------------------------------------------------

    private @NotNull ConnectFuture connectFutureFor(
        @NotNull SshClient mina,
        @NotNull SshHost host,
        @NotNull SshResolvedCredential credential
    ) throws IOException, SshConnectException {
        String proxyJump = effectiveProxyJump(host);
        if (proxyJump == null) {
            LOG.info("Conch SSH: direct connect path host=" + host.host() + ":" + host.port());
            // Use an explicit HostConfigEntry so that any ProxyJump directive
            // in ~/.ssh/config doesn't override the Conch-configured direct path.
            HostConfigEntry direct = new HostConfigEntry(
                host.host(), host.host(), host.port(), credential.username(), null);
            return mina.connect(direct);
        }

        LOG.info("Conch SSH: proxy jump connect path host=" + host.host() + ":" + host.port()
            + " via=" + proxyJump + " (resolving effective host config)");
        HostConfigEntry target = resolveEffectiveTarget(mina, host, credential.username(), proxyJump);
        return mina.connect(target);
    }

    private @NotNull HostConfigEntry resolveEffectiveTarget(
        @NotNull SshClient mina,
        @NotNull SshHost host,
        @NotNull String username,
        @NotNull String proxyJump
    ) throws IOException {
        HostConfigEntryResolver resolver = mina.getHostConfigEntryResolver();
        HostConfigEntry resolved = resolver.resolveEffectiveHost(
            host.host(),
            host.port(),
            (SocketAddress) null,
            username,
            proxyJump,
            (AttributeRepository) null);

        HostConfigEntry target;
        if (resolved == null) {
            target = new HostConfigEntry(host.host(), host.host(), host.port(), username, proxyJump);
        } else {
            target = resolved;
            if (trimToNull(target.getHostName()) == null) {
                target.setHostName(host.host());
            }
            if (target.getPort() <= 0) {
                target.setPort(host.port());
            }
            if (trimToNull(target.getUsername()) == null) {
                target.setUsername(username);
            }
            target.setProxyJump(proxyJump);
        }

        LOG.info("Conch SSH: effective target config hostName=" + target.getHostName()
            + " port=" + target.getPort()
            + " user=" + target.getUsername()
            + " identitiesOnly=" + target.isIdentitiesOnly()
            + " identitiesCount=" + target.getIdentities().size()
            + " proxyJump=" + target.getProxyJump());
        return target;
    }

    private static @Nullable String effectiveProxyJump(@NotNull SshHost host) throws SshConnectException {
        String command = trimToNull(host.proxyCommand());
        if (command != null) {
            LOG.info("Conch SSH: resolving proxy command for host=" + host.host() + ":" + host.port()
                + " command=" + command);
            String fromCommand = proxyJumpFromProxyCommand(command);
            if (fromCommand == null) {
                throw new SshConnectException(
                    SshConnectException.Kind.INVALID_PROXY_CONFIG,
                    "Unsupported ProxyCommand. Use an OpenSSH style command like: ssh -W %h:%p bastion");
            }
            LOG.info("Conch SSH: proxy command resolved to jump host=" + fromCommand);
            return fromCommand;
        }
        String jump = trimToNull(host.proxyJump());
        if (jump != null) {
            LOG.info("Conch SSH: using configured proxy jump for host=" + host.host() + ":" + host.port()
                + " jump=" + jump);
        }
        return jump;
    }

    public static @Nullable String proxyJumpFromProxyCommand(@NotNull String proxyCommand) {
        List<String> tokens = splitCommandTokens(proxyCommand);
        if (tokens.isEmpty()) return null;
        if (!"ssh".equals(tokens.get(0))) return null;

        String forwarding = null;
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("-W".equals(token)) {
                if (i + 1 >= tokens.size()) return null;
                forwarding = stripWrappingQuotes(tokens.get(++i));
                continue;
            }
            if (token.startsWith("-W")) {
                forwarding = stripWrappingQuotes(token.substring(2));
            }
        }

        if (!"%h:%p".equals(forwarding)) return null;

        String hop = stripWrappingQuotes(tokens.get(tokens.size() - 1));
        if (hop.isBlank()) return null;
        if (hop.startsWith("-")) return null;
        return hop;
    }

    private static @NotNull List<String> splitCommandTokens(@NotNull String command) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(command);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static @NotNull String stripWrappingQuotes(@NotNull String value) {
        if (value.length() >= 2) {
            char start = value.charAt(0);
            char end = value.charAt(value.length() - 1);
            if ((start == '\'' && end == '\'') || (start == '"' && end == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private synchronized SshClient ensureStarted() {
        if (client == null) {
            client = SshClient.setUpDefaultClient();
            // MINA's internal proxy-jump flow does not use our explicit
            // future.verify(...) calls for intermediate hops, so set client
            // defaults as well to avoid long "hung" waits.
            CoreModuleProperties.IO_CONNECT_TIMEOUT.set(client, CONNECT_TIMEOUT);
            CoreModuleProperties.AUTH_TIMEOUT.set(client, AUTH_TIMEOUT);
            // Read ~/.ssh/config so MINA can resolve per-host settings
            // (User, IdentityFile, Port, etc.) for intermediate proxy-jump hops.
            client.setHostConfigEntryResolver(DefaultConfigFileHostEntryResolver.INSTANCE);
            // Load default ~/.ssh/id_* keys at the client level so MINA's
            // internal proxy-jump sessions can authenticate to jump hosts.
            // Session-level attachIdentities() only runs on the final target.
            configureDefaultKeyIdentities(client);
            // Return null for encrypted-key passphrase prompts so MINA
            // skips them immediately rather than blocking.
            client.setFilePasswordProvider((session, resource, retryIndex) -> null);
            client.setPublicKeyAuthenticationReporter(new AuthDebugReporter());
            client.setPasswordAuthenticationReporter(new PasswordDebugReporter());
            client.start();
        }
        return client;
    }

    /**
     * Scan {@code ~/.ssh/} for standard unencrypted key files and register
     * them as a client-level {@code KeyIdentityProvider}.  MINA's internal
     * proxy-jump flow creates intermediate sessions whose authentication
     * draws from the <em>client</em>, not from per-session
     * {@link #attachIdentities} calls (those only run on the final target).
     */
    private static void configureDefaultKeyIdentities(@NotNull SshClient client) {
        String home = System.getProperty("user.home");
        if (home == null) return;
        Path sshDir = Path.of(home, ".ssh");
        List<KeyPair> keys = new ArrayList<>();
        for (String name : List.of("id_ed25519", "id_rsa", "id_ecdsa", "id_ed25519_sk", "id_ecdsa_sk")) {
            Path keyPath = sshDir.resolve(name);
            if (!Files.exists(keyPath) || !Files.isRegularFile(keyPath)) continue;
            try {
                if (KeyFileInspector.inspect(keyPath) != KeyFileInspector.Encryption.NONE) continue;
            } catch (IOException e) {
                continue;
            }
            try (InputStream in = Files.newInputStream(keyPath)) {
                Iterable<KeyPair> pairs = SecurityUtils.loadKeyPairIdentities(
                    null, NamedResource.ofName(keyPath.toString()), in, FilePasswordProvider.EMPTY);
                if (pairs != null) {
                    for (KeyPair kp : pairs) {
                        keys.add(kp);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Conch SSH: could not load default key " + keyPath + ": " + e.getMessage());
            }
        }
        if (!keys.isEmpty()) {
            client.setKeyIdentityProvider(session -> keys);
            LOG.info("Conch SSH: " + keys.size() + " default key(s) available for proxy-jump auth");
        }
    }

    private static void attachIdentities(
        @NotNull ClientSession session,
        @NotNull SshResolvedCredential cred
    ) throws IOException, GeneralSecurityException {
        LOG.info("Conch SSH: attaching identities mode=" + cred.mode()
            + " user=" + cred.username()
            + " hasPassword=" + (cred.password() != null)
            + " keyPath=" + (cred.keyPath() == null ? "<none>" : cred.keyPath()));
        switch (cred.mode()) {
            case PASSWORD -> addPassword(session, cred);
            case KEY -> addKey(session, cred);
            case KEY_AND_PASSWORD -> {
                addKey(session, cred);
                addPassword(session, cred);
            }
        }
    }

    private static void addPassword(ClientSession session, SshResolvedCredential cred) {
        char[] pw = cred.password();
        if (pw == null) return;
        // MINA takes a String. There's no char[] overload. The String
        // survives in the heap until GC — same class of v1 weakness the
        // vault's internal String-backed fields have. Documented in the
        // SSH plan's Risks section.
        session.addPasswordIdentity(new String(pw));
    }

    private static void configureSessionAuthPreferences(
        @NotNull ClientSession session,
        @NotNull SshResolvedCredential cred
    ) {
        switch (cred.mode()) {
            case KEY -> {
                session.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
                LOG.info("Conch SSH: auth method preference for session set to [publickey]");
            }
            case KEY_AND_PASSWORD -> {
                session.setUserAuthFactories(List.of(
                    UserAuthPublicKeyFactory.INSTANCE,
                    UserAuthPasswordFactory.INSTANCE));
                LOG.info("Conch SSH: auth method preference for session set to [publickey,password]");
            }
            case PASSWORD -> {
                session.setUserAuthFactories(List.of(UserAuthPasswordFactory.INSTANCE));
                LOG.info("Conch SSH: auth method preference for session set to [password]");
            }
        }
    }

    private static void addKey(
        ClientSession session,
        SshResolvedCredential cred
    ) throws IOException, GeneralSecurityException {
        if (cred.keyPath() == null) return;

        FilePasswordProvider provider = cred.keyPassphrase() == null
            ? FilePasswordProvider.EMPTY
            : (sessionCtx, resource, retryIndex) -> new String(cred.keyPassphrase());

        try (InputStream in = Files.newInputStream(cred.keyPath())) {
            Iterable<KeyPair> pairs = SecurityUtils.loadKeyPairIdentities(
                null,
                NamedResource.ofName(cred.keyPath().toString()),
                in,
                provider);
            if (pairs == null) {
                throw new IOException("no key pairs found in " + cred.keyPath());
            }
            for (KeyPair kp : pairs) {
                session.addPublicKeyIdentity(kp);
            }
        }
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void safeClose(ClientSession session) {
        try {
            session.close(true);
        } catch (Exception ignored) {
        }
    }

    private static final class AuthDebugReporter implements PublicKeyAuthenticationReporter {
        @Override
        public void signalAuthenticationAttempt(
            ClientSession session, String service, KeyPair identity, String signature
        ) {
            LOG.info("Conch SSH: pubkey auth attempt service=" + service
                + " user=" + session.getUsername()
                + " keyFp=" + keyFingerprint(identity)
                + " sig=" + signature);
        }

        @Override
        public void signalAuthenticationSuccess(ClientSession session, String service, KeyPair identity) {
            LOG.info("Conch SSH: pubkey auth success service=" + service
                + " user=" + session.getUsername()
                + " keyFp=" + keyFingerprint(identity));
        }

        @Override
        public void signalAuthenticationFailure(
            ClientSession session, String service, KeyPair identity, boolean partial, List<String> serverMethods
        ) {
            LOG.warn("Conch SSH: pubkey auth failure service=" + service
                + " user=" + session.getUsername()
                + " keyFp=" + keyFingerprint(identity)
                + " partial=" + partial
                + " serverMethods=" + serverMethods);
        }

        @Override
        public void signalAuthenticationExhausted(ClientSession session, String service) {
            LOG.warn("Conch SSH: pubkey auth exhausted service=" + service
                + " user=" + session.getUsername());
        }

        private static String keyFingerprint(@Nullable KeyPair keyPair) {
            if (keyPair == null || keyPair.getPublic() == null) return "<null>";
            return KeyUtils.getFingerPrint(keyPair.getPublic());
        }
    }

    private static final class PasswordDebugReporter implements PasswordAuthenticationReporter {
        @Override
        public void signalAuthenticationAttempt(
            ClientSession session, String service, String oldPassword, boolean modified, String newPassword
        ) {
            LOG.info("Conch SSH: password auth attempt service=" + service
                + " user=" + session.getUsername() + " modified=" + modified);
        }

        @Override
        public void signalAuthenticationSuccess(ClientSession session, String service, String password) {
            LOG.info("Conch SSH: password auth success service=" + service
                + " user=" + session.getUsername());
        }

        @Override
        public void signalAuthenticationFailure(
            ClientSession session, String service, String password, boolean partial, List<String> serverMethods
        ) {
            LOG.warn("Conch SSH: password auth failure service=" + service
                + " user=" + session.getUsername()
                + " partial=" + partial
                + " serverMethods=" + serverMethods);
        }

        @Override
        public void signalAuthenticationExhausted(ClientSession session, String service) {
            LOG.warn("Conch SSH: password auth exhausted service=" + service
                + " user=" + session.getUsername());
        }
    }
}
