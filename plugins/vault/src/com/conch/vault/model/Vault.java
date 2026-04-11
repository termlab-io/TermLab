package com.conch.vault.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level vault state. Mutable because the UI edits account lists in place
 * between save calls; the individual {@link VaultAccount} records are immutable
 * and get swapped out on edit.
 */
public final class Vault {
    public static final int VERSION = 1;

    public int version;
    public List<VaultAccount> accounts;
    public List<GeneratedKeyEntry> generatedKeys;
    public VaultSettings settings;

    public Vault() {
        this.version = VERSION;
        this.accounts = new ArrayList<>();
        this.generatedKeys = new ArrayList<>();
        this.settings = VaultSettings.defaults();
    }
}
