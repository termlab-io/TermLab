package com.conch.vault.keygen;

/**
 * SSH key algorithms supported by {@link SshKeyGenerator}.
 *
 * <p>Listed in descending order of modern preference:
 * <ul>
 *   <li>{@link #ED25519} — small, fast, secure. Default choice.</li>
 *   <li>{@link #ECDSA_P256} / {@link #ECDSA_P384} — NIST curves. Widely supported.</li>
 *   <li>{@link #RSA_3072} / {@link #RSA_4096} — legacy, slow to generate, broadly compatible.</li>
 * </ul>
 */
public enum KeyGenAlgorithm {
    ED25519("ed25519", "Ed25519 (recommended)"),
    ECDSA_P256("ecdsa-p256", "ECDSA P-256"),
    ECDSA_P384("ecdsa-p384", "ECDSA P-384"),
    RSA_3072("rsa-3072", "RSA 3072"),
    RSA_4096("rsa-4096", "RSA 4096");

    private final String id;
    private final String displayName;

    KeyGenAlgorithm(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** Stable identifier stored in the vault (matches {@code VaultKey.algorithm}). */
    public String id() {
        return id;
    }

    /** Human label for the KeyGenDialog dropdown. */
    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
