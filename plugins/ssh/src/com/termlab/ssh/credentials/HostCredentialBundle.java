package com.termlab.ssh.credentials;

import com.termlab.ssh.client.TermLabSshClient;
import com.termlab.ssh.client.KeyFileInspector;
import com.termlab.ssh.client.SshResolvedCredential;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.KeyFileAuth;
import com.termlab.ssh.model.PromptPasswordAuth;
import com.termlab.ssh.model.SshHost;
import com.termlab.ssh.model.VaultAuth;
import com.termlab.ssh.ui.InlineCredentialPromptDialog;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Resolves the target-host credential plus any proxy-jump bastion
 * credential needed to open a session against a TermLab {@link SshHost}.
 *
 * <p>Extracted from {@code SshSessionProvider} so both the terminal-side
 * {@code SshSessionProvider} and the SFTP plugin's {@code RemoteFilePane}
 * can share the same vault / keyfile / prompt dispatch logic, including
 * bastion-credential lookup from {@link HostStore}.
 *
 * <p>The bundle implements {@link AutoCloseable} so a caller that
 * successfully resolves credentials but then fails to pass them into
 * {@link TermLabSshClient#connect} can still dispose of any sensitive
 * material.
 */
public record HostCredentialBundle(
    @NotNull SshResolvedCredential target,
    @Nullable TermLabSshClient.BastionAuth bastion
) implements AutoCloseable {

    private static final Logger LOG = Logger.getInstance(HostCredentialBundle.class);

    /**
     * Per-host credential producer — a separate object (not just a
     * method) so the caller can re-fetch after an {@code AUTH_FAILED}
     * retry without re-dispatching on {@link com.termlab.ssh.model.SshAuth}.
     *
     * <p>The three {@code SshAuth} variants map to three different
     * sources; never cross-contaminate (e.g. a
     * {@link PromptPasswordAuth} host must not trigger a vault picker
     * on retry).
     */
    public interface AuthSource {
        @Nullable SshResolvedCredential fetch(@NotNull SshHost host);
    }

    /**
     * Build an {@link AuthSource} for {@code host} by dispatching on
     * its {@link SshHost#auth()} variant. Safe to call on the EDT —
     * the returned source only interacts with user-facing prompts
     * when {@link AuthSource#fetch} is invoked.
     */
    public static @NotNull AuthSource authSourceFor(@NotNull SshHost host) {
        SshCredentialResolver resolver = new SshCredentialResolver();
        SshCredentialPicker picker = new SshCredentialPicker();

        return switch (host.auth()) {
            case VaultAuth v -> vaultSource(resolver, picker, v);
            case PromptPasswordAuth p -> HostCredentialBundle::promptPasswordSource;
            case KeyFileAuth k -> h -> keyFileSource(h, k);
        };
    }

    /**
     * Resolve the target credential plus (if the host has a
     * {@code proxyJump}) the bastion credential, in a single call.
     * Returns {@code null} if the user cancelled any of the prompts.
     *
     * <p>Must run on the EDT because credential resolution may open
     * modal dialogs (password, passphrase, vault picker).
     */
    public static @Nullable HostCredentialBundle resolveForHost(@NotNull SshHost host) {
        SshResolvedCredential target = authSourceFor(host).fetch(host);
        if (target == null) return null;

        TermLabSshClient.BastionAuth bastion = null;
        if (host.proxyJump() != null && !host.proxyJump().isBlank()) {
            bastion = resolveBastionFor(host);
        }
        return new HostCredentialBundle(target, bastion);
    }

    /**
     * Resolve a bastion credential for {@code target.proxyJump()}.
     * Returns {@code null} when the bastion isn't in {@link HostStore}
     * (fall back to client-level defaults), the user cancelled a
     * prompt, or credential resolution otherwise failed.
     */
    public static @Nullable TermLabSshClient.BastionAuth resolveBastionFor(@NotNull SshHost target) {
        String proxyJump = target.proxyJump();
        if (proxyJump == null) return null;

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
            LOG.info("TermLab SSH: no HostStore entry for bastion "
                + bastionHost + ":" + bastionPort
                + " (proxy-jump will use ~/.ssh/id_* fallback)");
            return null;
        }

        SshResolvedCredential bastionCred;
        try {
            bastionCred = authSourceFor(stored).fetch(stored);
        } catch (Throwable t) {
            LOG.warn("TermLab SSH: failed to resolve bastion credential for "
                + stored.host() + ":" + stored.port() + ": " + t.getMessage(), t);
            return null;
        }
        if (bastionCred == null) {
            LOG.info("TermLab SSH: bastion credential resolution cancelled or failed for "
                + stored.host() + ":" + stored.port()
                + " (proxy-jump will use ~/.ssh/id_* fallback)");
            return null;
        }

        LOG.info("TermLab SSH: resolved bastion credential "
            + stored.host() + ":" + stored.port()
            + " user=" + bastionCred.username()
            + " mode=" + bastionCred.mode());
        return new TermLabSshClient.BastionAuth(stored.host(), stored.port(), bastionCred);
    }

    @Override
    public void close() {
        target.close();
        if (bastion != null) bastion.credential().close();
    }

    // -- dispatch helpers (package-private for tests, but accessed only here) --

    private static @NotNull AuthSource vaultSource(
        @NotNull SshCredentialResolver resolver,
        @NotNull SshCredentialPicker picker,
        @NotNull VaultAuth vault
    ) {
        return host -> {
            if (vault.credentialId() != null) {
                // Try silent lookup first — works when the vault is
                // already unlocked.
                SshResolvedCredential saved = resolver.resolve(vault.credentialId(), host.username());
                if (saved != null) return saved;
                // Likely the vault is locked. Unlock it (no picker)
                // and retry the exact credential the host is wired to.
                // Only fall through to the picker if the configured
                // credentialId genuinely doesn't exist after unlock —
                // e.g. the entry was deleted or the user picked a
                // credential from a different store.
                if (resolver.ensureAnyProviderAvailable()) {
                    saved = resolver.resolve(vault.credentialId(), host.username());
                    if (saved != null) return saved;
                }
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
}
