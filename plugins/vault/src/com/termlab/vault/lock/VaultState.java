package com.termlab.vault.lock;

/**
 * States the vault can be in at any given moment.
 *
 * <p>Transitions are driven by {@link LockManager}:
 * <pre>
 *   LOCKED  →  UNLOCKING  →  UNLOCKED  →  SEALING  →  LOCKED
 *                        ↘ (failed)  →  LOCKED
 * </pre>
 */
public enum VaultState {
    /** Vault file exists on disk but no decrypted copy is held in memory. */
    LOCKED,

    /** Password has been submitted; Argon2 key derivation is in progress. */
    UNLOCKING,

    /** Vault is decrypted and available in memory. */
    UNLOCKED,

    /** {@link LockManager#lock()} has been called; secrets are being zeroed. */
    SEALING
}
