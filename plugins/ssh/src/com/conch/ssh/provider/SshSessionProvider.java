package com.conch.ssh.provider;

import com.conch.sdk.TerminalSessionProvider;
import com.conch.ssh.client.ConchServerKeyVerifier;
import com.conch.ssh.client.ConchSshClient;
import com.conch.ssh.client.SshConnectException;
import com.conch.ssh.client.SshConnection;
import com.conch.ssh.client.SshResolvedCredential;
import com.conch.ssh.credentials.SshCredentialPicker;
import com.conch.ssh.credentials.SshCredentialResolver;
import com.conch.ssh.model.KeyFileAuth;
import com.conch.ssh.model.PromptPasswordAuth;
import com.conch.ssh.model.SshAuth;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.model.VaultAuth;
import com.conch.ssh.persistence.KnownHostsFile;
import com.conch.ssh.ui.InlineCredentialPromptDialog;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.ui.Messages;
import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.nio.file.Path;

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

        return connectWithRetry(host, initial, source);
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
        char[] passphrase = InlineCredentialPromptDialog.promptPassphrase(
            null, host, auth.keyFilePath());
        if (passphrase == null) return null;
        // Empty input means "no passphrase" — MINA's key loader skips
        // decryption when we pass null. A zero-length char[] carries no
        // secret, so we don't bother zeroing it.
        char[] phraseOrNull = passphrase.length == 0 ? null : passphrase;
        return SshResolvedCredential.key(
            host.username(),
            Path.of(auth.keyFilePath()),
            phraseOrNull
        );
    }

    // -- connect loop ---------------------------------------------------------

    private @Nullable TtyConnector connectWithRetry(
        @NotNull SshHost host,
        @NotNull SshResolvedCredential initialCredential,
        @NotNull AuthSource source
    ) {
        ConchSshClient client = getClient();
        SshResolvedCredential current = initialCredential;
        int attemptsLeft = 2;  // initial + one retry after AUTH_FAILED

        while (attemptsLeft > 0) {
            attemptsLeft--;
            ConnectOutcome outcome = runConnect(client, host, current);

            if (outcome.connection != null) {
                current.close();
                return outcome.connection.getTtyConnector();
            }

            current.close();

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
                    outcome.connection = client.connect(
                        host, credential, new ConchServerKeyVerifier());
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
