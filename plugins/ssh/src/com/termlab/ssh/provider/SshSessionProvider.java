package com.termlab.ssh.provider;

import com.termlab.sdk.TerminalSessionProvider;
import com.termlab.ssh.client.TermLabServerKeyVerifier;
import com.termlab.ssh.client.TermLabSshClient;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.client.SshConnection;
import com.termlab.ssh.client.SshResolvedCredential;
import com.termlab.ssh.credentials.HostCredentialBundle;
import com.termlab.ssh.model.SshAuth;
import com.termlab.ssh.model.SshHost;
import com.termlab.ssh.persistence.KnownHostsFile;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link TerminalSessionProvider} that opens SSH sessions via
 * {@link TermLabSshClient}, renders them through the existing
 * {@code TermLabTerminalEditor}, and dispatches on {@link SshAuth} to
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
 * <p>Host-key verification is handled by {@link TermLabServerKeyVerifier}
 * which consults {@link KnownHostsFile} and prompts the user on first
 * contact.
 */
public final class SshSessionProvider implements TerminalSessionProvider {

    private static final Logger LOG = Logger.getInstance(SshSessionProvider.class);

    public static final String ID = "com.termlab.ssh";

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

        HostCredentialBundle.AuthSource source = HostCredentialBundle.authSourceFor(host);
        SshResolvedCredential initial = source.fetch(host);
        if (initial == null) return null;

        // Resolve bastion credentials up front so proxy-jump auth uses
        // the bastion's own configured credentials (vault, key file,
        // prompt) rather than whatever happens to be in ~/.ssh/id_*.
        // The bastion credential lives for the entire connect (including
        // any target-auth retry) and is closed in connectWithRetry.
        TermLabSshClient.BastionAuth bastion = HostCredentialBundle.resolveBastionFor(host);

        return connectWithRetry(host, initial, source, bastion);
    }

    // -- connect loop ---------------------------------------------------------

    private @Nullable TtyConnector connectWithRetry(
        @NotNull SshHost host,
        @NotNull SshResolvedCredential initialCredential,
        @NotNull HostCredentialBundle.AuthSource source,
        @Nullable TermLabSshClient.BastionAuth bastion
    ) {
        TermLabSshClient client = getClient();
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
                            + "The remote host presented a different key than the one TermLab "
                            + "has on file. This may mean someone is intercepting your "
                            + "connection (man-in-the-middle attack).\n\n"
                            + "If the key legitimately changed, remove the entry from "
                            + "~/.config/termlab/known_hosts manually and try again.",
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
                bastion.credential().close();
            }
        }
    }

    private @NotNull ConnectOutcome runConnect(
        @NotNull TermLabSshClient client,
        @NotNull SshHost host,
        @NotNull SshResolvedCredential credential,
        @Nullable TermLabSshClient.BastionAuth bastion
    ) {
        ConnectOutcome outcome = new ConnectOutcome();
        LOG.info("TermLab SSH: opening connect modal host=" + host.host() + ":" + host.port());

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
                Future<?> job = AppExecutorUtil.getAppExecutorService().submit(() -> {
                    try {
                        connectionRef.set(client.connect(
                            host, credential, bastion, new TermLabServerKeyVerifier()));
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
                            LOG.warn("TermLab SSH: connect cancelled by user host="
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
                    LOG.warn("TermLab SSH: connect worker interrupted host="
                        + host.host() + ":" + host.port(), e);
                    client.shutdown();
                    outcome.cancelled = true;
                    return;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    LOG.warn("TermLab SSH: connect worker failed host="
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

    private @NotNull TermLabSshClient getClient() {
        if (ApplicationManager.getApplication() != null) {
            TermLabSshClient service =
                ApplicationManager.getApplication().getService(TermLabSshClient.class);
            if (service != null) return service;
        }
        return new TermLabSshClient();
    }

    /** Mutable holder the Task.Modal populates with either success or failure. */
    private static final class ConnectOutcome {
        @Nullable SshConnection connection;
        @Nullable SshConnectException failure;
        boolean cancelled;
    }
}
