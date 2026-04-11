package com.conch.vault.persistence;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Canonical paths for the vault's on-disk state. All files live under
 * {@code ~/.config/conch/} — the same directory Conch uses for its own
 * configuration.
 */
public final class VaultPaths {

    private VaultPaths() {}

    /** The encrypted vault file. */
    public static Path vaultFile() {
        return Paths.get(System.getProperty("user.home"), ".config", "conch", "vault.enc");
    }
}
