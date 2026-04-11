package com.conch.vault.model;

/**
 * Per-vault settings. Stored inside the encrypted vault payload, not in
 * IntelliJ's application settings — so every vault carries its own
 * auto-lock timeout.
 */
public record VaultSettings(
    int autoLockMinutes,
    boolean pushToSystemAgent,
    AutoSavePolicy autoSavePasswords
) {
    public static VaultSettings defaults() {
        return new VaultSettings(15, false, AutoSavePolicy.ASK);
    }

    public enum AutoSavePolicy { ALWAYS, ASK, NEVER }
}
