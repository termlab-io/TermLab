package com.termlab.vault.credentials;

import com.termlab.sdk.CredentialProvider;
import com.termlab.vault.lock.LockManager;
import com.termlab.vault.model.Vault;
import com.termlab.vault.model.VaultAccount;
import com.termlab.vault.model.VaultKey;
import com.termlab.vault.ui.AccountPickerDialog;
import com.termlab.vault.ui.UnlockDialog;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Implements TermLab's {@link CredentialProvider} SDK interface on top of the
 * in-process {@link LockManager}.
 *
 * <p>Exposes both vault accounts and standalone SSH keys as credentials.
 * Consumers (SSH plugin, future RDP plugin, etc.) call
 * {@link #listCredentials()} to populate a picker and {@link #getCredential(UUID)}
 * to retrieve the authoritative data for the selected entry. SSH keys return
 * with {@code username=null}; the consumer should prompt for a username
 * before using them.
 *
 * <p>Registered in {@code plugin.xml} as an extension to
 * {@code com.termlab.core.credentialProvider}. The IntelliJ extension-point
 * framework instantiates it with a no-arg constructor; the test constructor
 * accepts an explicit {@link LockManager} so unit tests can bypass the
 * IntelliJ application container.
 */
public final class VaultCredentialProvider implements CredentialProvider {

    private final LockManagerSupplier lockManagerSupplier;

    /** Extension-point constructor. Resolves LockManager via the app service. */
    public VaultCredentialProvider() {
        this.lockManagerSupplier = () ->
            ApplicationManager.getApplication().getService(LockManager.class);
    }

    /** Test constructor. */
    public VaultCredentialProvider(@NotNull LockManager lockManager) {
        this.lockManagerSupplier = () -> lockManager;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Credential Vault";
    }

    @Override
    public boolean isAvailable() {
        LockManager lm = lockManagerSupplier.get();
        return lm != null && !lm.isLocked();
    }

    /**
     * If the vault is locked, pop {@link UnlockDialog} so the caller
     * can subsequently resolve a credential by id without going
     * through the account picker. Returns {@code true} when the vault
     * is unlocked after the call.
     */
    @Override
    public boolean ensureAvailable() {
        LockManager lm = lockManagerSupplier.get();
        if (lm == null) return false;
        if (!lm.isLocked()) return true;
        Project project = ProjectManager.getInstance().getDefaultProject();
        boolean unlocked = new UnlockDialog(project, lm).showAndGet();
        return unlocked && !lm.isLocked();
    }

    @Override
    public @NotNull List<CredentialDescriptor> listCredentials() {
        LockManager lm = lockManagerSupplier.get();
        if (lm == null) return Collections.emptyList();
        Vault vault = lm.getVault();
        if (vault == null) return Collections.emptyList();

        List<CredentialDescriptor> out = new ArrayList<>(vault.accounts.size() + vault.keys.size());
        for (VaultAccount account : vault.accounts) {
            out.add(AuthMethodMapper.toDescriptor(account));
        }
        for (VaultKey key : vault.keys) {
            out.add(AuthMethodMapper.toDescriptor(key));
        }
        return out;
    }

    @Override
    public @Nullable Credential getCredential(@NotNull UUID credentialId) {
        LockManager lm = lockManagerSupplier.get();
        if (lm == null) return null;
        Vault vault = lm.getVault();
        if (vault == null) return null;

        for (VaultAccount account : vault.accounts) {
            if (account.id().equals(credentialId)) {
                return AuthMethodMapper.toCredential(account);
            }
        }
        for (VaultKey key : vault.keys) {
            if (key.id().equals(credentialId)) {
                return AuthMethodMapper.toCredential(key);
            }
        }
        return null;
    }

    /**
     * Show an account picker and return the chosen credential. If the vault
     * is locked at call time, {@link AccountPickerDialog} transparently
     * runs an unlock flow first; cancelling either step returns {@code null}.
     *
     * <p>Currently only offers accounts — the standalone-key picker is a
     * future addition once the SSH plugin exists and can drive it.
     */
    @Override
    public @Nullable Credential promptForCredential() {
        LockManager lm = lockManagerSupplier.get();
        if (lm == null) return null;
        Project project = ProjectManager.getInstance().getDefaultProject();
        VaultAccount picked = AccountPickerDialog.show(project, lm);
        return picked == null ? null : AuthMethodMapper.toCredential(picked);
    }

    @FunctionalInterface
    private interface LockManagerSupplier {
        @Nullable LockManager get();
    }
}
