package com.termlab.share.model;

import com.termlab.vault.model.VaultAccount;
import com.termlab.vault.model.VaultKey;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record BundledVault(
    @NotNull List<VaultAccount> accounts,
    @NotNull List<VaultKey> keys
) {
    public static @NotNull BundledVault empty() {
        return new BundledVault(List.of(), List.of());
    }

    public boolean isEmpty() {
        return accounts.isEmpty() && keys.isEmpty();
    }
}
