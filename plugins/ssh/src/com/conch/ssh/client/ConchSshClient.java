package com.conch.ssh.client;

import com.conch.ssh.model.SshHost;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;

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

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(15);

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

        ClientSession session;
        try {
            ConnectFuture connectFuture = mina.connect(credential.username(), host.host(), host.port());
            session = connectFuture.verify(CONNECT_TIMEOUT).getSession();
        } catch (IOException e) {
            throw new SshConnectException(
                SshConnectException.Kind.HOST_UNREACHABLE,
                "Could not reach " + host.host() + ":" + host.port() + " — " + e.getMessage(),
                e);
        }

        // Server key verification MUST run before auth() — MINA checks the
        // verifier during the KEX phase, which is part of auth's state machine.
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
            return new SshConnection(session, channel);
        } catch (IOException e) {
            safeClose(session);
            throw new SshConnectException(
                SshConnectException.Kind.CHANNEL_OPEN_FAILED,
                "Could not open shell channel on " + host.host() + ": " + e.getMessage(),
                e);
        }
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

    private synchronized SshClient ensureStarted() {
        if (client == null) {
            client = SshClient.setUpDefaultClient();
            client.start();
        }
        return client;
    }

    private static void attachIdentities(
        @NotNull ClientSession session,
        @NotNull SshResolvedCredential cred
    ) throws IOException, GeneralSecurityException {
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

    private static void safeClose(ClientSession session) {
        try {
            session.close(true);
        } catch (Exception ignored) {
        }
    }
}
