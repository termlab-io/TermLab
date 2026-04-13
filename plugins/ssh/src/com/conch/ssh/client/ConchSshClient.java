package com.conch.ssh.client;

import com.conch.ssh.credentials.SshCredentialResolver;
import com.conch.ssh.model.HostStore;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.model.VaultAuth;
import com.intellij.openapi.application.ApplicationManager;
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
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.core.CoreModuleProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

    /**
     * Pre-loaded {@code ~/.ssh/id_*} keys registered as a client-level
     * fallback. Populated once on startup; used by
     * {@link #keysForSession} when no per-bastion credential is
     * registered for the session's connect address.
     */
    private volatile @NotNull List<KeyPair> defaultClientKeys = List.of();

    /**
     * Map from lowercased {@code host:port} to the credential
     * {@link #keysForSession} / {@link #passwordsForSession} should
     * supply when MINA's proxy-jump flow creates a session to that
     * address. Populated by {@link #registerBastionAuth} immediately
     * before {@code mina.connect()} and cleared in a {@code finally}
     * block after connect completes.
     *
     * <p>Keys are intentionally a simple string form so we can build
     * them identically from any {@link SocketAddress} flavor MINA
     * might pass us at lookup time (see {@link #sessionAddressKey}).
     */
    private final ConcurrentMap<String, SshResolvedCredential> bastionCredentialsByAddress =
        new ConcurrentHashMap<>();

    public ConchSshClient() {
    }

    /**
     * Pairs a bastion's resolved credential with the address MINA will
     * use when opening the bastion session. The address is what goes
     * into the proxy-jump {@code user@host:port} spec, minus the user —
     * i.e. {@code host:port}. Used by {@link #connect} overloads that
     * need to authenticate the bastion with its own credentials rather
     * than relying on client-level {@code ~/.ssh/id_*} defaults.
     */
    public record BastionAuth(
        @NotNull String host,
        int port,
        @NotNull SshResolvedCredential credential
    ) {
        /** The key form used by {@link ConchSshClient#bastionCredentialsByAddress}. */
        @NotNull String addressKey() {
            return host.toLowerCase(Locale.ROOT) + ":" + port;
        }
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
        return connect(host, credential, null, verifier);
    }

    /**
     * Connect variant that also attaches a bastion-specific credential,
     * used when the target host has a {@code proxyJump} and the bastion
     * needs different identities than the target (e.g. different user,
     * different key file, a vault-backed password). The provided
     * {@link BastionAuth} is registered on the client for the duration
     * of this call so {@link #keysForSession} / {@link #passwordsForSession}
     * can supply its identities when MINA opens the bastion session.
     * Ownership of the bastion credential stays with the caller; this
     * method does not close it.
     *
     * <p>{@code bastionAuth} may be {@code null}, in which case bastion
     * authentication falls back to client-level {@code ~/.ssh/id_*}
     * defaults (the old behavior).
     */
    public @NotNull SshConnection connect(
        @NotNull SshHost host,
        @NotNull SshResolvedCredential credential,
        @Nullable BastionAuth bastionAuth,
        @NotNull ServerKeyVerifier verifier
    ) throws SshConnectException {
        SshClient mina = ensureStarted();
        LOG.info("Conch SSH: connect start host=" + host.host() + ":" + host.port()
            + " user=" + credential.username()
            + " proxyCommand=" + (host.proxyCommand() != null)
            + " proxyJump=" + (host.proxyJump() != null ? host.proxyJump() : "<none>")
            + " bastionAuth=" + (bastionAuth == null
                ? "<none:default-client-keys>"
                : bastionAuth.host() + ":" + bastionAuth.port()
                    + " user=" + bastionAuth.credential().username()
                    + " mode=" + bastionAuth.credential().mode()));

        // MINA's built-in ProxyJump support establishes jump sessions inside
        // connect(), so the verifier must be configured on the client before
        // connect() starts.
        mina.setServerKeyVerifier(verifier);

        String bastionKey = registerBastionAuth(bastionAuth);
        try {
            return connectInternal(mina, host, credential, verifier);
        } finally {
            unregisterBastionAuth(bastionKey);
        }
    }

    private @NotNull SshConnection connectInternal(
        @NotNull SshClient mina,
        @NotNull SshHost host,
        @NotNull SshResolvedCredential credential,
        @NotNull ServerKeyVerifier verifier
    ) throws SshConnectException {
        ClientSession session;
        try {
            long connectStartNs = System.nanoTime();
            ConnectFuture connectFuture = connectFutureFor(mina, host, credential);
            LOG.info("Conch SSH: connectFuture obtained host=" + host.host() + ":" + host.port()
                + " awaitingVerify...");
            session = connectFuture.verify(CONNECT_TIMEOUT).getSession();
            long connectMs = (System.nanoTime() - connectStartNs) / 1_000_000;
            SshdSocketAddress target = session.getAttribute(ClientSessionCreator.TARGET_SERVER);
            LOG.info("Conch SSH: connect session established host=" + host.host() + ":" + host.port()
                + " connectAddress=" + session.getConnectAddress()
                + " remoteAddress=" + session.getIoSession().getRemoteAddress()
                + " targetServer=" + (target == null ? "<none:direct-or-hop>"
                    : target.getHostName() + ":" + target.getPort())
                + " connectMs=" + connectMs
                + " sessionId=" + session.getSessionId());
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
            LOG.info("Conch SSH: starting auth host=" + host.host() + ":" + host.port()
                + " proxyJump=" + (host.proxyJump() != null ? host.proxyJump() : "<none>"));
            long authStartNs = System.nanoTime();
            AuthFuture auth = session.auth();
            auth.verify(AUTH_TIMEOUT);
            long authMs = (System.nanoTime() - authStartNs) / 1_000_000;
            if (!auth.isSuccess()) {
                safeClose(session);
                Throwable cause = auth.getException();
                LOG.warn("Conch SSH: auth future !isSuccess host=" + host.host() + ":" + host.port()
                    + " authMs=" + authMs
                    + " causeClass=" + (cause == null ? "null" : cause.getClass().getName())
                    + " causeMsg=" + (cause == null ? "null" : cause.getMessage()));
                throw new SshConnectException(
                    SshConnectException.Kind.AUTH_FAILED,
                    "SSH authentication failed",
                    cause != null ? cause : new IOException("auth future did not succeed"));
            }
            LOG.info("Conch SSH: auth success host=" + host.host() + ":" + host.port()
                + " authMs=" + authMs);
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
                + host.host() + ":" + host.port()
                + " proxyJump=" + (host.proxyJump() != null ? host.proxyJump() : "<none>")
                + " kind=" + kind
                + " exceptionClass=" + e.getClass().getName()
                + " -> " + e.getMessage(), e);
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
        return connectSession(host, credential, null, verifier);
    }

    /**
     * {@link #connectSession} variant that accepts a bastion-specific
     * credential. Behaves identically to the no-bastion overload
     * otherwise. See {@link #connect(SshHost, SshResolvedCredential, BastionAuth, ServerKeyVerifier)}
     * for the proxy-jump authentication contract.
     */
    public @NotNull ClientSession connectSession(
        @NotNull SshHost host,
        @NotNull SshResolvedCredential credential,
        @Nullable BastionAuth bastionAuth,
        @NotNull ServerKeyVerifier verifier
    ) throws SshConnectException {
        SshClient mina = ensureStarted();
        LOG.info("Conch SSH: connectSession (no shell) host=" + host.host() + ":" + host.port()
            + " user=" + credential.username()
            + " proxyJump=" + (host.proxyJump() != null ? host.proxyJump() : "<none>")
            + " bastionAuth=" + (bastionAuth == null
                ? "<none:default-client-keys>"
                : bastionAuth.host() + ":" + bastionAuth.port()
                    + " user=" + bastionAuth.credential().username()
                    + " mode=" + bastionAuth.credential().mode()));

        mina.setServerKeyVerifier(verifier);

        String bastionKey = registerBastionAuth(bastionAuth);
        try {
            return connectSessionInternal(mina, host, credential, verifier);
        } finally {
            unregisterBastionAuth(bastionKey);
        }
    }

    private @NotNull ClientSession connectSessionInternal(
        @NotNull SshClient mina,
        @NotNull SshHost host,
        @NotNull SshResolvedCredential credential,
        @NotNull ServerKeyVerifier verifier
    ) throws SshConnectException {
        ClientSession session;
        try {
            long connectStartNs = System.nanoTime();
            ConnectFuture connectFuture = connectFutureFor(mina, host, credential);
            session = connectFuture.verify(CONNECT_TIMEOUT).getSession();
            long connectMs = (System.nanoTime() - connectStartNs) / 1_000_000;
            SshdSocketAddress target = session.getAttribute(ClientSessionCreator.TARGET_SERVER);
            LOG.info("Conch SSH: connectSession session established host=" + host.host() + ":" + host.port()
                + " connectAddress=" + session.getConnectAddress()
                + " remoteAddress=" + session.getIoSession().getRemoteAddress()
                + " targetServer=" + (target == null ? "<none:direct-or-hop>"
                    : target.getHostName() + ":" + target.getPort())
                + " connectMs=" + connectMs);
        } catch (IllegalArgumentException e) {
            throw new SshConnectException(
                SshConnectException.Kind.INVALID_PROXY_CONFIG,
                "Invalid SSH proxy configuration: " + e.getMessage(), e);
        } catch (IOException e) {
            LOG.warn("Conch SSH: connectSession network/connect failure host="
                + host.host() + ":" + host.port() + " -> " + e.getMessage(), e);
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

        // Normalize the jump spec to OpenSSH user@host[:port] form. MINA
        // does NOT honor OpenSSH's default-to-target-user rule for
        // ProxyJump — when the spec is missing a user prefix, MINA
        // creates the bastion ClientSession with username=null, the
        // connect listener throws IllegalStateException, and the
        // bastion never authenticates. The whole proxy-jump connect
        // then hangs until our 10s timeout while the bastion silently
        // sits in pre-auth limbo (you'll see "AuthTimeout after
        // 15999/15000 ms" 5s after our timeout). This bites on Windows
        // where ~/.ssh/config doesn't supply a User directive for the
        // bastion IP and MINA's HostConfigEntryResolver returns an
        // entry with no username.
        //
        // Picking the bastion user (in order of preference):
        //   1. An explicit `user@` prefix already in the jump spec.
        //   2. The username from a matching SshHost in HostStore. The
        //      user configured it once, we shouldn't make them repeat
        //      it on every host that jumps through the bastion.
        //   3. The target user (OpenSSH's documented default).
        String bastionFallbackUser = resolveBastionUserFromStore(proxyJump, credential.username());
        String normalizedProxyJump = normalizeProxyJumpUser(proxyJump, bastionFallbackUser);
        if (!normalizedProxyJump.equals(proxyJump)) {
            String source = bastionFallbackUser.equals(credential.username())
                ? "defaulted to target user"
                : "resolved from HostStore (or its vault credential)";
            LOG.info("Conch SSH: proxy jump normalized from '" + proxyJump
                + "' to '" + normalizedProxyJump + "' — bastion user '"
                + bastionFallbackUser + "' (" + source + ")");
        }

        LOG.info("Conch SSH: proxy jump connect path host=" + host.host() + ":" + host.port()
            + " via=" + normalizedProxyJump + " (resolving effective host config)");
        HostConfigEntry target = resolveEffectiveTarget(mina, host, credential.username(), normalizedProxyJump);
        return mina.connect(target);
    }

    /**
     * Ensure a ProxyJump spec is of the form {@code user@host[:port]}
     * before handing it to MINA. If the caller-supplied {@code proxyJump}
     * already includes a {@code user@} prefix, it is returned unchanged.
     * Otherwise {@code fallbackUsername} is prepended.
     *
     * <p>This matches OpenSSH's documented ProxyJump default: <em>"If
     * user is not specified, then by default the user matches the user
     * requested by the target."</em> MINA does not implement that
     * default — without an explicit user it creates the bastion
     * ClientSession with {@code username=null} and silently fails to
     * authenticate. Always pre-normalizing the spec sidesteps both the
     * MINA bug and any platform-specific behavior of MINA's
     * {@code HostConfigEntryResolver} (notably, on Windows where a
     * populated {@code ~/.ssh/config} can return a bastion entry with
     * no User directive).
     */
    static @NotNull String normalizeProxyJumpUser(
        @NotNull String proxyJump,
        @NotNull String fallbackUsername
    ) {
        // OpenSSH ProxyJump syntax: [user@]host[:port]. The username
        // can never contain '@' itself, so the first '@' delimits it.
        int at = proxyJump.indexOf('@');
        if (at > 0) {
            return proxyJump;
        }
        return fallbackUsername + "@" + proxyJump;
    }

    /**
     * Look up the username to use when connecting to the bastion host
     * in a ProxyJump. If the {@code proxyJump} spec already carries an
     * explicit {@code user@} prefix, that wins and we return
     * {@code targetUser} (which {@link #normalizeProxyJumpUser} will
     * then ignore). Otherwise we parse {@code host[:port]} out of the
     * spec and look for a matching {@link SshHost} in {@link HostStore}
     * so the user doesn't have to repeat the bastion user on every host
     * that jumps through it.
     *
     * <p>If the matching {@link SshHost} is stored with a Vault
     * credential and a <em>blank</em> username (which the edit dialog
     * permits since the credential can supply the username), we resolve
     * the vault credential synchronously to recover the embedded
     * username. This keeps "mixy-matchy" credential setups working:
     * target uses a manual key, bastion uses a vault credential.
     *
     * <p>When no stored host matches at all, we fall back to the target
     * user per the OpenSSH ProxyJump default.
     *
     * <p>The HostStore lookup is host-and-port sensitive — the user's
     * bastion host must be saved with the same IP/hostname and port as
     * appear in the jump spec. Hostnames are compared
     * case-insensitively.
     */
    private static @NotNull String resolveBastionUserFromStore(
        @NotNull String proxyJump,
        @NotNull String targetUser
    ) {
        if (proxyJump.indexOf('@') > 0) {
            // Already has an explicit user — normalizeProxyJumpUser
            // will leave it alone, so the fallback is irrelevant.
            return targetUser;
        }

        String bastionHost = proxyJump;
        int bastionPort = SshHost.DEFAULT_PORT;
        int colon = proxyJump.lastIndexOf(':');
        if (colon > 0 && colon < proxyJump.length() - 1) {
            try {
                bastionPort = Integer.parseInt(proxyJump.substring(colon + 1));
                bastionHost = proxyJump.substring(0, colon);
            } catch (NumberFormatException ignored) {
                // Malformed port — treat the whole string as a hostname.
            }
        }

        if (ApplicationManager.getApplication() == null) return targetUser;
        HostStore store = ApplicationManager.getApplication().getService(HostStore.class);
        if (store == null) return targetUser;

        SshHost bastion = null;
        for (SshHost stored : store.getHosts()) {
            if (stored.host().equalsIgnoreCase(bastionHost)
                && stored.port() == bastionPort) {
                bastion = stored;
                break;
            }
        }
        if (bastion == null) {
            LOG.info("Conch SSH: bastion lookup for " + bastionHost + ":" + bastionPort
                + " found no matching SshHost in HostStore (will default to target user)");
            return targetUser;
        }

        if (!bastion.username().isBlank()) {
            return bastion.username();
        }

        // Blank username + vault auth: the dialog lets users leave the
        // username field empty when a vault credential will supply it.
        // Resolve the credential synchronously (reads from the already-
        // unlocked vault; does not prompt) to recover the username.
        if (bastion.auth() instanceof VaultAuth vault && vault.credentialId() != null) {
            try {
                SshResolvedCredential resolved =
                    new SshCredentialResolver().resolve(vault.credentialId(), "");
                if (resolved != null) {
                    try {
                        String username = resolved.username();
                        if (username != null && !username.isBlank()) {
                            LOG.info("Conch SSH: bastion username recovered from vault credential"
                                + " bastion=" + bastionHost + ":" + bastionPort
                                + " user=" + username);
                            return username;
                        }
                    } finally {
                        resolved.close();
                    }
                } else {
                    LOG.info("Conch SSH: bastion vault credential " + vault.credentialId()
                        + " could not be resolved (vault locked or credential missing)");
                }
            } catch (Throwable t) {
                LOG.warn("Conch SSH: failed to resolve bastion vault credential "
                    + vault.credentialId() + ": " + t.getMessage());
            }
        }

        LOG.info("Conch SSH: bastion host " + bastionHost + ":" + bastionPort
            + " has blank username and no recoverable credential — defaulting to target user");
        return targetUser;
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
            // Load ~/.ssh/id_* once as a client-level fallback. The
            // actual provider below dispatches per-session so a
            // bastion-specific credential (registered via
            // registerBastionAuth) can override the default.
            this.defaultClientKeys = loadDefaultClientKeys();
            client.setKeyIdentityProvider(this::keysForSession);
            client.setPasswordIdentityProvider(this::passwordsForSession);
            // Return null for encrypted-key passphrase prompts so MINA
            // skips them immediately rather than blocking. (Passphrases
            // for bastion keys come in pre-resolved via BastionAuth;
            // see loadKeyPairsFromCredential below.)
            client.setFilePasswordProvider((session, resource, retryIndex) -> null);
            client.setPublicKeyAuthenticationReporter(new AuthDebugReporter());
            client.setPasswordAuthenticationReporter(new PasswordDebugReporter());
            client.start();
        }
        return client;
    }

    /**
     * Session-aware {@code KeyIdentityProvider}. When a bastion
     * credential is registered for this session's connect address,
     * supply keys loaded from it (if the credential is key-based).
     * Otherwise fall through to {@link #defaultClientKeys}.
     *
     * <p>MINA calls this on its IO thread from inside the handshake,
     * once per session — both the target session and any intermediate
     * proxy-jump bastion session. The session address lookup is what
     * lets the same {@link SshClient} service multiple hops with
     * different credentials.
     */
    private @NotNull Iterable<KeyPair> keysForSession(@Nullable SessionContext session) {
        SshResolvedCredential bastionCred = findBastionCredForSession(session);
        if (bastionCred != null) {
            List<KeyPair> keys = loadKeyPairsFromCredential(bastionCred);
            if (!keys.isEmpty()) {
                LOG.info("Conch SSH: supplying " + keys.size()
                    + " bastion key(s) from registered credential for session "
                    + sessionAddressKey(session) + " user=" + bastionCred.username());
                return keys;
            }
        }
        return defaultClientKeys;
    }

    /**
     * Session-aware {@code PasswordIdentityProvider}. When the session
     * address has a password-bearing bastion credential registered,
     * offer the password. Otherwise offer nothing (we only supply
     * passwords for bastions the user explicitly configured that way).
     */
    private @NotNull Iterable<String> passwordsForSession(@Nullable SessionContext session) {
        SshResolvedCredential bastionCred = findBastionCredForSession(session);
        if (bastionCred == null) return List.of();
        char[] pw = bastionCred.password();
        if (pw == null) return List.of();
        LOG.info("Conch SSH: supplying bastion password from registered credential for session "
            + sessionAddressKey(session) + " user=" + bastionCred.username());
        // MINA wants a String. Same string-in-heap weakness as
        // session.addPasswordIdentity in attachIdentities.
        return List.of(new String(pw));
    }

    private @Nullable String registerBastionAuth(@Nullable BastionAuth bastionAuth) {
        if (bastionAuth == null) return null;
        String key = bastionAuth.addressKey();
        SshResolvedCredential previous =
            bastionCredentialsByAddress.put(key, bastionAuth.credential());
        if (previous != null) {
            // Very unlikely — two concurrent connects to the same
            // bastion. The later one wins for its lifetime. We log so
            // that if this does happen in the wild we can recognize it.
            LOG.warn("Conch SSH: bastion credential for " + key
                + " was already registered; replacing (concurrent connect?)");
        }
        return key;
    }

    private void unregisterBastionAuth(@Nullable String bastionKey) {
        if (bastionKey == null) return;
        bastionCredentialsByAddress.remove(bastionKey);
    }

    private @Nullable SshResolvedCredential findBastionCredForSession(@Nullable SessionContext session) {
        if (session == null) return null;
        String key = sessionAddressKey(session);
        if (key == null) return null;
        return bastionCredentialsByAddress.get(key);
    }

    private static @Nullable String sessionAddressKey(@Nullable SessionContext session) {
        if (session == null) return null;
        // SessionContext exposes remote/local via ConnectionEndpointsIndicator.
        // The remote address is the peer — bastion for an intermediate
        // proxy-jump session, target for a direct session.
        return socketAddressKey(session.getRemoteAddress());
    }

    private static @Nullable String socketAddressKey(@Nullable SocketAddress addr) {
        if (addr instanceof InetSocketAddress inet) {
            String host = inet.getHostString();
            if (host == null) return null;
            return host.toLowerCase(Locale.ROOT) + ":" + inet.getPort();
        }
        if (addr instanceof SshdSocketAddress sshd) {
            return sshd.getHostName().toLowerCase(Locale.ROOT) + ":" + sshd.getPort();
        }
        return null;
    }

    /**
     * Scan {@code ~/.ssh/} for standard unencrypted key files. These
     * serve as the client-level fallback when a session has no
     * bastion-specific credential registered. Encrypted keys are
     * skipped — Conch has no way to prompt for a passphrase from
     * MINA's IO thread during proxy-jump, and the user should instead
     * configure the bastion explicitly in Conch if it needs a
     * passphrase-protected key.
     */
    private static @NotNull List<KeyPair> loadDefaultClientKeys() {
        String home = System.getProperty("user.home");
        if (home == null) return List.of();
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
            LOG.info("Conch SSH: " + keys.size() + " default key(s) available as client-level fallback");
        }
        return Collections.unmodifiableList(keys);
    }

    /**
     * Load key pairs out of a {@link SshResolvedCredential}'s key file
     * for use as MINA identities. Returns an empty list for
     * password-only credentials or when the key file can't be parsed.
     * Honors the credential's embedded passphrase for encrypted keys.
     */
    private static @NotNull List<KeyPair> loadKeyPairsFromCredential(@NotNull SshResolvedCredential cred) {
        if (cred.mode() == SshResolvedCredential.Mode.PASSWORD) return List.of();
        Path keyPath = cred.keyPath();
        if (keyPath == null) return List.of();
        char[] passphrase = cred.keyPassphrase();
        FilePasswordProvider fpp = passphrase == null
            ? FilePasswordProvider.EMPTY
            : (sessionCtx, resource, retryIndex) -> new String(passphrase);
        try (InputStream in = Files.newInputStream(keyPath)) {
            Iterable<KeyPair> pairs = SecurityUtils.loadKeyPairIdentities(
                null, NamedResource.ofName(keyPath.toString()), in, fpp);
            if (pairs == null) return List.of();
            List<KeyPair> result = new ArrayList<>();
            for (KeyPair kp : pairs) {
                result.add(kp);
            }
            return result;
        } catch (Exception e) {
            LOG.warn("Conch SSH: failed to load bastion key from " + keyPath + ": " + e.getMessage());
            return List.of();
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
