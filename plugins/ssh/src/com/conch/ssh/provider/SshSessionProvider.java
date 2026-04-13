package com.conch.ssh.provider;

import com.conch.sdk.TerminalSessionProvider;
import com.conch.ssh.client.ConchServerKeyVerifier;
import com.conch.ssh.client.ConchSshClient;
import com.conch.ssh.client.KeyFileInspector;
import com.conch.ssh.client.SshConnectException;
import com.conch.ssh.client.SshConnection;
import com.conch.ssh.client.SshResolvedCredential;
import com.conch.ssh.credentials.SshCredentialPicker;
import com.conch.ssh.credentials.SshCredentialResolver;
import com.conch.ssh.model.HostStore;
import com.conch.ssh.model.KeyFileAuth;
import com.conch.ssh.model.PromptPasswordAuth;
import com.conch.ssh.model.SshAuth;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.model.VaultAuth;
import com.conch.ssh.persistence.KnownHostsFile;
import com.conch.ssh.ui.InlineCredentialPromptDialog;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link TerminalSessionProvider} that opens SSH sessions via
 * {@link ConchSshClient}, renders them through the existing
 * {@code ConchTerminalEditor}, and dispatches on {@link SshAuth} to
 * choose how the credential for this host is sourced.
 *
 * <p>Flow on {@link #createSession(SessionContext)}:
 * <ol>
 *   <li>The context must be an {@link SshSessionContext} carrying an
 *       {@link SshHost}.</li>
 *   <li>Dispatch on {@code host.auth()}:
 *       <ul>
 *         <li>{@link VaultAuth}({@code id} != null) → look up in the
 *             vault. If that fails, fall through to the vault picker.</li>
 *         <li>{@link VaultAuth}({@code id} == null) → run the vault
 *             picker directly.</li>
 *         <li>{@link PromptPasswordAuth} →
 *             {@link InlineCredentialPromptDialog#promptPassword}.</li>
 *         <li>{@link KeyFileAuth} →
 *             {@link InlineCredentialPromptDialog#promptPassphrase} +
 *             {@link SshResolvedCredential#key}.</li>
 *       </ul>
 *   </li>
 *   <li>Connect runs inside a {@link Task.Modal} so the EDT never blocks
 *       during MINA's ~seconds-long handshake.</li>
 *   <li>On {@link SshConnectException.Kind#AUTH_FAILED}, the provider
 *       re-runs the same auth-variant's source once via the supplied
 *       {@link AuthSource}, giving the user a chance to correct a stale
 *       credential without closing the tab.</li>
 *   <li>On {@link SshConnectException.Kind#HOST_KEY_REJECTED}, a hard
 *       MITM-warning dialog fires and there is no "accept anyway" path.</li>
 * </ol>
 *
 * <p>Host-key verification is handled by {@link ConchServerKeyVerifier}
 * which consults {@link KnownHostsFile} and prompts the user on first
 * contact.
 */
public final class SshSessionProvider implements TerminalSessionProvider {

    private static final Logger LOG = Logger.getInstance(SshSessionProvider.class);

    public static final String ID = "com.conch.ssh";

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "SSH";
    }

    @Override
    public @Nullable Icon getIcon() {
        return AllIcons.Webreferences.Server;
    }

    @Override
    public boolean canQuickOpen() {
        return false;
    }

    @Override
    public @Nullable TtyConnector createSession(@NotNull SessionContext context) {
        if (!(context instanceof SshSessionContext sshContext)) {
            Messages.showErrorDialog(
                "SSH sessions require an SshSessionContext with a selected host.",
                "No Host Selected");
            return null;
        }
        SshHost host = sshContext.host();

        AuthSource source = authSourceFor(host);
        SshResolvedCredential initial = source.fetch(host);
        if (initial == null) return null;

        // Resolve bastion credentials up front so proxy-jump auth uses
        // the bastion's own configured credentials (vault, key file,
        // prompt) rather than whatever happens to be in ~/.ssh/id_*.
        // The bastion credential lives for the entire connect (including
        // any target-auth retry) and is closed in connectWithRetry.
        ResolvedBastion bastion = null;
        if (host.proxyJump() != null && !host.proxyJump().isBlank()) {
            bastion = resolveBastionForTarget(host);
        }

        return connectWithRetry(host, initial, source, bastion);
    }

    /**
     * Pair of {@link ConchSshClient.BastionAuth} plus the parsed
     * bastion host/port, kept together so {@code connectWithRetry} can
     * pass the auth to MINA and log useful diagnostics.
     */
    private record ResolvedBastion(
        @NotNull ConchSshClient.BastionAuth auth,
        @NotNull SshHost storedHost
    ) {}

    /**
     * Resolve a bastion credential for the target host's {@code proxyJump}.
     * Returns {@code null} when the bastion isn't in HostStore (fall
     * back to client-level defaults), the user cancelled a prompt, or
     * credential resolution otherwise failed — the caller then runs
     * the existing behavior, which may still succeed if a default key
     * happens to authenticate the bastion.
     *
     * <p>This runs on the EDT because credential sources may prompt
     * (password dialog, key passphrase, vault picker). Do NOT move it
     * inside the connect Task.Modal.
     */
    private @Nullable ResolvedBastion resolveBastionForTarget(@NotNull SshHost target) {
        String proxyJump = target.proxyJump();
        if (proxyJump == null) return null;

        // proxyJump may be user@host[:port] or host[:port] or an alias.
        // Strip any user@ prefix — we only use the host:port part to
        // find the matching SshHost in HostStore.
        String spec = proxyJump;
        int at = spec.indexOf('@');
        if (at > 0) spec = spec.substring(at + 1);

        String bastionHost = spec;
        int bastionPort = SshHost.DEFAULT_PORT;
        int colon = spec.lastIndexOf(':');
        if (colon > 0 && colon < spec.length() - 1) {
            try {
                bastionPort = Integer.parseInt(spec.substring(colon + 1));
                bastionHost = spec.substring(0, colon);
            } catch (NumberFormatException ignored) {
                // not a port number; treat the whole spec as hostname
            }
        }

        HostStore store = ApplicationManager.getApplication() == null
            ? null
            : ApplicationManager.getApplication().getService(HostStore.class);
        if (store == null) return null;

        SshHost stored = null;
        for (SshHost candidate : store.getHosts()) {
            if (candidate.host().equalsIgnoreCase(bastionHost)
                && candidate.port() == bastionPort) {
                stored = candidate;
                break;
            }
        }
        if (stored == null) {
            LOG.info("Conch SSH: no HostStore entry for bastion "
                + bastionHost + ":" + bastionPort
                + " (proxy-jump will use ~/.ssh/id_* fallback)");
            return null;
        }

        // Reuse the same AuthSource dispatch the target uses, so vault /
        // keyfile / prompt auth all work identically for bastion hosts.
        AuthSource bastionSource = authSourceFor(stored);
        SshResolvedCredential bastionCred;
        try {
            bastionCred = bastionSource.fetch(stored);
        } catch (Throwable t) {
            LOG.warn("Conch SSH: failed to resolve bastion credential for "
                + stored.host() + ":" + stored.port() + ": " + t.getMessage(), t);
            return null;
        }
        if (bastionCred == null) {
            LOG.info("Conch SSH: bastion credential resolution cancelled or failed for "
                + stored.host() + ":" + stored.port()
                + " (proxy-jump will use ~/.ssh/id_* fallback)");
            return null;
        }

        ConchSshClient.BastionAuth auth = new ConchSshClient.BastionAuth(
            stored.host(), stored.port(), bastionCred);
        LOG.info("Conch SSH: resolved bastion credential "
            + stored.host() + ":" + stored.port()
            + " user=" + bastionCred.username()
            + " mode=" + bastionCred.mode());
        return new ResolvedBastion(auth, stored);
    }

    // -- dispatch -------------------------------------------------------------

    /**
     * Encapsulates "how to produce an {@link SshResolvedCredential} for
     * this host", including the retry case after an auth failure. The
     * three {@link SshAuth} variants map to three different sources —
     * never cross-contaminate (e.g. a PromptPassword host should never
     * trigger a vault picker on retry).
     */
    private interface AuthSource {
        @Nullable SshResolvedCredential fetch(@NotNull SshHost host);
    }

    private @NotNull AuthSource authSourceFor(@NotNull SshHost host) {
        SshCredentialResolver resolver = new SshCredentialResolver();
        SshCredentialPicker picker = new SshCredentialPicker();

        return switch (host.auth()) {
            case VaultAuth v -> vaultSource(resolver, picker, v);
            case PromptPasswordAuth p -> h -> promptPasswordSource(h);
            case KeyFileAuth k -> h -> keyFileSource(h, k);
        };
    }

    private static @NotNull AuthSource vaultSource(
        @NotNull SshCredentialResolver resolver,
        @NotNull SshCredentialPicker picker,
        @NotNull VaultAuth vault
    ) {
        return host -> {
            if (vault.credentialId() != null) {
                SshResolvedCredential saved = resolver.resolve(vault.credentialId(), host.username());
                if (saved != null) return saved;
            }
            return picker.pick(host);
        };
    }

    private static @Nullable SshResolvedCredential promptPasswordSource(@NotNull SshHost host) {
        char[] pw = InlineCredentialPromptDialog.promptPassword(null, host);
        if (pw == null) return null;
        return SshResolvedCredential.password(host.username(), pw);
    }

    private static @Nullable SshResolvedCredential keyFileSource(
        @NotNull SshHost host,
        @NotNull KeyFileAuth auth
    ) {
        Path keyPath = Path.of(auth.keyFilePath());

        KeyFileInspector.Encryption encryption;
        try {
            encryption = KeyFileInspector.inspect(keyPath);
        } catch (IOException e) {
            Messages.showErrorDialog(
                "Could not read SSH key file:\n" + keyPath + "\n\n" + e.getMessage(),
                "SSH Key File Unreadable");
            return null;
        }

        if (encryption == KeyFileInspector.Encryption.NONE) {
            return SshResolvedCredential.key(host.username(), keyPath, null);
        }

        char[] passphrase = InlineCredentialPromptDialog.promptPassphrase(
            null, host, auth.keyFilePath());
        if (passphrase == null) return null;
        return SshResolvedCredential.key(host.username(), keyPath, passphrase);
    }

    // -- connect loop ---------------------------------------------------------

    private @Nullable TtyConnector connectWithRetry(
        @NotNull SshHost host,
        @NotNull SshResolvedCredential initialCredential,
        @NotNull AuthSource source,
        @Nullable ResolvedBastion bastion
    ) {
        ConchSshClient client = getClient();
        SshResolvedCredential current = initialCredential;
        int attemptsLeft = 2;  // initial + one retry after AUTH_FAILED

        try {
            while (attemptsLeft > 0) {
                attemptsLeft--;
                ConnectOutcome outcome = runConnect(client, host, current, bastion);

                if (outcome.connection != null) {
                    current.close();
                    return outcome.connection.getTtyConnector();
                }

                current.close();

                if (outcome.cancelled) return null;

                if (outcome.failure == null) return null;  // user cancelled mid-connect

                SshConnectException.Kind kind = outcome.failure.kind();
                if (kind == SshConnectException.Kind.AUTH_FAILED && attemptsLeft > 0) {
                    SshResolvedCredential retry = source.fetch(host);
                    if (retry == null) return null;
                    current = retry;
                    continue;
                }

                if (kind == SshConnectException.Kind.HOST_KEY_REJECTED) {
                    Messages.showErrorDialog(
                        "Host key mismatch for " + host.host() + ":" + host.port() + ".\n\n"
                            + "The remote host presented a different key than the one Conch "
                            + "has on file. This may mean someone is intercepting your "
                            + "connection (man-in-the-middle attack).\n\n"
                            + "If the key legitimately changed, remove the entry from "
                            + "~/.config/conch/known_hosts manually and try again.",
                        "Host Key Rejected");
                    return null;
                }

                if (kind == SshConnectException.Kind.HOST_UNREACHABLE) {
                    Messages.showErrorDialog(
                        "Could not reach " + host.host() + ":" + host.port() + ":\n"
                            + outcome.failure.getMessage(),
                        "SSH Connection Failed");
                    return null;
                }

                if (kind == SshConnectException.Kind.INVALID_PROXY_CONFIG) {
                    Messages.showErrorDialog(
                        outcome.failure.getMessage(),
                        "Invalid SSH Proxy Configuration");
                    return null;
                }

                Messages.showErrorDialog(
                    "SSH connection failed: " + outcome.failure.getMessage(),
                    "SSH Connection Failed");
                return null;
            }

            return null;
        } finally {
            // Bastion credential lives only for this connect cycle —
            // it was resolved upstream in createSession and handed down
            // to us. Clear it once we're done, regardless of outcome.
            if (bastion != null) {
                bastion.auth().credential().close();
            }
        }
    }

    private @NotNull ConnectOutcome runConnect(
        @NotNull ConchSshClient client,
        @NotNull SshHost host,
        @NotNull SshResolvedCredential credential,
        @Nullable ResolvedBastion bastion
    ) {
        ConnectOutcome outcome = new ConnectOutcome();
        LOG.info("Conch SSH: opening connect modal host=" + host.host() + ":" + host.port());

        ProgressManager.getInstance().run(new Task.Modal(
            null,
            "Connecting to " + host.label() + " (" + host.host() + ":" + host.port() + ")…",
            /* canBeCancelled = */ true
        ) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);

                AtomicReference<SshConnection> connectionRef = new AtomicReference<>();
                AtomicReference<SshConnectException> failureRef = new AtomicReference<>();
                ConchSshClient.BastionAuth bastionAuth =
                    bastion == null ? null : bastion.auth();
                Future<?> job = AppExecutorUtil.getAppExecutorService().submit(() -> {
                    try {
                        connectionRef.set(client.connect(
                            host, credential, bastionAuth, new ConchServerKeyVerifier()));
                    } catch (SshConnectException e) {
                        failureRef.set(e);
                    } catch (Exception e) {
                        failureRef.set(new SshConnectException(
                            SshConnectException.Kind.UNKNOWN,
                            "Unexpected failure: " + e.getMessage(),
                            e));
                    }
                });

                try {
                    while (true) {
                        if (indicator.isCanceled()) {
                            job.cancel(true);
                            // Best-effort hard stop so blocked MINA internals
                            // do not keep waiting after user cancellation.
                            LOG.warn("Conch SSH: connect cancelled by user host="
                                + host.host() + ":" + host.port() + " -> shutting down SSH client");
                            client.shutdown();
                            outcome.cancelled = true;
                            SshConnection connection = connectionRef.getAndSet(null);
                            if (connection != null) connection.close();
                            return;
                        }

                        try {
                            job.get(100, TimeUnit.MILLISECONDS);
                            break;
                        } catch (TimeoutException ignored) {
                            // keep polling cancellation
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    job.cancel(true);
                    LOG.warn("Conch SSH: connect worker interrupted host="
                        + host.host() + ":" + host.port(), e);
                    client.shutdown();
                    outcome.cancelled = true;
                    return;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    LOG.warn("Conch SSH: connect worker failed host="
                        + host.host() + ":" + host.port() + " -> " + cause.getMessage(), cause);
                    failureRef.compareAndSet(null, new SshConnectException(
                        SshConnectException.Kind.UNKNOWN,
                        "Unexpected failure: " + cause.getMessage(),
                        cause));
                }

                outcome.connection = connectionRef.get();
                outcome.failure = failureRef.get();
            }
        });

        return outcome;
    }

    private @NotNull ConchSshClient getClient() {
        if (ApplicationManager.getApplication() != null) {
            ConchSshClient service =
                ApplicationManager.getApplication().getService(ConchSshClient.class);
            if (service != null) return service;
        }
        return new ConchSshClient();
    }

    /** Mutable holder the Task.Modal populates with either success or failure. */
    private static final class ConnectOutcome {
        @Nullable SshConnection connection;
        @Nullable SshConnectException failure;
        boolean cancelled;
    }
}
