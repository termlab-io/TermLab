package com.termlab.vault.persistence;

import com.termlab.vault.settings.TermLabVaultConfig;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Canonical paths for the vault's on-disk state.
 *
 * <p>The vault file lives at {@code ~/.config/termlab/vault.enc} by default.
 * This matches the convention used by the rest of TermLab (terminal settings,
 * workspaces, etc.) on every platform — Linux-style paths, not
 * platform-native Application Support / AppData. The user can override the
 * vault file location in Settings → Credential Vault → Storage.
 */
public final class VaultPaths {

    private VaultPaths() {}

    /**
     * @return the effective vault file path — either the user's override from
     *         {@link TermLabVaultConfig}, or the default under
     *         {@code ~/.config/termlab/}.
     */
    public static Path vaultFile() {
        String override = TermLabVaultConfig.getInstance().getState().vaultFilePath;
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return defaultVaultFile();
    }

    /** The hard-coded default, used when no override is configured. */
    public static Path defaultVaultFile() {
        return Paths.get(System.getProperty("user.home"), ".config", "termlab", "vault.enc");
    }
}
