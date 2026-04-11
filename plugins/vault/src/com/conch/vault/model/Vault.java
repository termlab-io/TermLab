package com.conch.vault.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level vault state.
 *
 * <p>Two parallel collections: {@link #accounts} hold user+password (+ optional
 * key) credentials, while {@link #keys} hold SSH keys that may or may not be
 * attached to an account. Both are first-class in the vault UI and both are
 * enumerated through the SDK's
 * {@code com.conch.sdk.CredentialProvider#listCredentials()}.
 *
 * <p>Mutable because the UI edits the lists in place between save calls; the
 * individual records ({@link VaultAccount}, {@link VaultKey}) are immutable
 * and get swapped out on edit.
 */
public final class Vault {
    public static final int VERSION = 1;

    public int version;
    public List<VaultAccount> accounts;
    public List<VaultKey> keys;
    public VaultSettings settings;

    public Vault() {
        this.version = VERSION;
        this.accounts = new ArrayList<>();
        this.keys = new ArrayList<>();
        this.settings = VaultSettings.defaults();
    }
}
