package com.conch.vault.credentials;

import com.conch.sdk.CredentialProvider;
import com.conch.vault.lock.LockManager;
import com.conch.vault.model.Vault;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Implements Conch's {@link CredentialProvider} SDK interface on top of the
 * in-process {@link LockManager}.
 *
 * <p>Registered in {@code plugin.xml} as an extension to
 * {@code com.conch.core.credentialProvider}. The IntelliJ extension-point
 * framework instantiates it with a no-arg constructor; the public test
 * constructor accepts an explicit {@link LockManager} so unit tests can
 * bypass the IntelliJ application container.
 */
public final class VaultCredentialProvider implements CredentialProvider {

    /**
     * Supplier for the LockManager. In production, resolved lazily via
     * {@link ApplicationManager#getApplication()} — we can't do this in a
     * field initializer because the extension can be constructed before
     * {@code Application} is fully initialized.
     */
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
        return "Conch Vault";
    }

    @Override
    public boolean isAvailable() {
        LockManager lm = lockManagerSupplier.get();
        return lm != null && !lm.isLocked();
    }

    @Override
    public @Nullable Credential getCredential(@NotNull UUID accountId) {
        LockManager lm = lockManagerSupplier.get();
        if (lm == null) return null;
        Vault vault = lm.getVault();
        if (vault == null) return null;
        return vault.accounts.stream()
            .filter(a -> a.id().equals(accountId))
            .findFirst()
            .map(AuthMethodMapper::toCredential)
            .orElse(null);
    }

    /**
     * Show an account picker and return the chosen credential.
     *
     * <p>The picker UI lives in the {@code ui} package and is implemented in
     * Phase 4 of the vault plan. Until then this method returns {@code null},
     * which is a legal signal per the SDK contract ("user cancelled or no
     * picker available").
     */
    @Override
    public @Nullable Credential promptForCredential() {
        // TODO(phase-4): instantiate AccountPickerDialog and return the
        // chosen credential, automatically triggering UnlockDialog if the
        // vault is locked at call time.
        return null;
    }

    @FunctionalInterface
    private interface LockManagerSupplier {
        @Nullable LockManager get();
    }
}
