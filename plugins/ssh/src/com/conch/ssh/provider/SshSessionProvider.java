package com.conch.ssh.provider;

import com.conch.sdk.TerminalSessionProvider;
import com.conch.ssh.client.ConchSshClient;
import com.conch.ssh.client.SshConnectException;
import com.conch.ssh.client.SshConnection;
import com.conch.ssh.client.SshResolvedCredential;
import com.conch.ssh.credentials.SshCredentialPicker;
import com.conch.ssh.credentials.SshCredentialResolver;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.persistence.HostPaths;
import com.conch.ssh.persistence.KnownHostsFile;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.ui.Messages;
import com.jediterm.terminal.TtyConnector;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * {@link TerminalSessionProvider} that opens SSH sessions via
 * {@link ConchSshClient}, renders them through the existing
 * {@code ConchTerminalEditor}, and handles credential resolution +
 * retry flow without any changes to the editor.
 *
 * <p>Flow on {@link #createSession(SessionContext)}:
 * <ol>
 *   <li>The context must be an {@link SshSessionContext} carrying the
 *       target {@link SshHost}. Anything else returns {@code null}
 *       with an error dialog — a bare {@code SessionContext} would
 *       mean the caller didn't select a host first.</li>
 *   <li>{@link SshCredentialResolver#resolve(SshHost)} looks up the
 *       host's saved credential. If the host has no credential id, or
 *       the saved id no longer resolves, the picker kicks in instead.</li>
 *   <li>The connect runs inside a {@link Task.Modal} so the EDT never
 *       blocks during MINA's ~seconds-long SSH handshake.</li>
 *   <li>On {@link SshConnectException.Kind#AUTH_FAILED}, the provider
 *       re-prompts via the picker once — giving the user a chance to
 *       correct a stale password without closing the tab.</li>
 *   <li>On {@link SshConnectException.Kind#HOST_KEY_REJECTED}, a hard
 *       MITM-warning dialog fires; there is deliberately no
 *       "accept anyway" path.</li>
 * </ol>
 *
 * <p>Host-key verification is currently a placeholder
 * {@link AcceptAllServerKeyVerifier} — Phase 5 replaces it with a
 * {@code ConchServerKeyVerifier} that consults {@link KnownHostsFile}
 * and prompts the user on first contact. Until then, an unknown host
 * is accepted silently and written to disk on next reconnect attempt.
 */
public final class SshSessionProvider implements TerminalSessionProvider {

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
        // SSH always needs a host selection up front, so the core's
        // "quick new terminal" path should not hit this provider.
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

        SshCredentialResolver resolver = new SshCredentialResolver();
        SshCredentialPicker picker = new SshCredentialPicker();

        // Step 1: try the saved credential.
        SshResolvedCredential credential = resolver.resolve(host);

        // Step 2: fall back to the picker if the host has no saved
        // credential or the saved one can no longer be resolved.
        if (credential == null) {
            credential = picker.pick(host);
        }
        if (credential == null) {
            // User cancelled the picker, or no provider available.
            return null;
        }

        // Step 3: connect (off EDT, with a re-prompt on AUTH_FAILED).
        return connectWithRetry(host, credential, picker);
    }

    // -- internals ------------------------------------------------------------

    private @Nullable TtyConnector connectWithRetry(
        @NotNull SshHost host,
        @NotNull SshResolvedCredential initialCredential,
        @NotNull SshCredentialPicker picker
    ) {
        ConchSshClient client = getClient();
        SshResolvedCredential current = initialCredential;
        int attemptsLeft = 2;  // initial attempt + one retry after AUTH_FAILED

        while (attemptsLeft > 0) {
            attemptsLeft--;
            ConnectOutcome outcome = runConnect(client, host, current);

            if (outcome.connection != null) {
                // Success — close the credential (we're done with it)
                // and hand the TtyConnector to the caller.
                current.close();
                return outcome.connection.getTtyConnector();
            }

            // Clean up the credential we just tried — either the retry
            // uses a freshly picked one, or we're about to return null.
            current.close();

            if (outcome.failure == null) {
                // Task was cancelled by the user mid-connect. Don't retry.
                return null;
            }

            SshConnectException.Kind kind = outcome.failure.kind();
            if (kind == SshConnectException.Kind.AUTH_FAILED && attemptsLeft > 0) {
                SshResolvedCredential retry = picker.pick(host);
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

            Messages.showErrorDialog(
                "SSH connection failed: " + outcome.failure.getMessage(),
                "SSH Connection Failed");
            return null;
        }

        return null;
    }

    private @NotNull ConnectOutcome runConnect(
        @NotNull ConchSshClient client,
        @NotNull SshHost host,
        @NotNull SshResolvedCredential credential
    ) {
        ConnectOutcome outcome = new ConnectOutcome();

        ProgressManager.getInstance().run(new Task.Modal(
            null,
            "Connecting to " + host.label() + " (" + host.host() + ":" + host.port() + ")…",
            /* canBeCancelled = */ true
        ) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    // Host-key verification is a Phase 5 concern —
                    // AcceptAllServerKeyVerifier keeps the flow unblocked
                    // until ConchServerKeyVerifier lands.
                    outcome.connection = client.connect(
                        host, credential, AcceptAllServerKeyVerifier.INSTANCE);
                } catch (SshConnectException e) {
                    outcome.failure = e;
                } catch (Exception e) {
                    outcome.failure = new SshConnectException(
                        SshConnectException.Kind.UNKNOWN,
                        "Unexpected failure: " + e.getMessage(),
                        e);
                }
            }
        });

        return outcome;
    }

    private @NotNull ConchSshClient getClient() {
        // Phase 4.3 promotes ConchSshClient to an application service.
        // Until then, fall back to a per-call client so tests and dev
        // builds can still run the provider. The service lookup path
        // will be preferred once registered.
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
    }
}
