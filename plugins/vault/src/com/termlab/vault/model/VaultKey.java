package com.termlab.vault.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An SSH key stored in the vault. The private and public key files themselves
 * live on disk at the recorded paths; this record holds the metadata the UI
 * displays and the SSH plugin uses to look up keys by id.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id}          — stable UUID, survives renames</li>
 *   <li>{@code name}        — user-facing label for pickers and lists
 *                             ("Work laptop", "GitHub CI", "Personal
 *                             Ed25519"). Not necessarily unique.</li>
 *   <li>{@code algorithm}   — {@link com.termlab.vault.keygen.KeyGenAlgorithm}
 *                             id string ({@code "ed25519"},
 *                             {@code "ecdsa-p256"}, etc.)</li>
 *   <li>{@code fingerprint} — {@code SHA256:base64} (no padding), matches
 *                             {@code ssh-keygen -l -f}</li>
 *   <li>{@code comment}     — the text appended to the public key line
 *                             (usually email or hostname). Not the same as
 *                             {@code name}; this field goes into the
 *                             {@code .pub} file itself.</li>
 *   <li>{@code privatePath}, {@code publicPath} — absolute paths</li>
 *   <li>{@code createdAt}   — when the key was generated or imported</li>
 * </ul>
 */
public record VaultKey(
    UUID id,
    String name,
    String algorithm,
    String fingerprint,
    String comment,
    String privatePath,
    String publicPath,
    Instant createdAt
) {
    /** Copy this key with a new {@code name}. */
    public VaultKey withName(String newName) {
        return new VaultKey(id, newName, algorithm, fingerprint, comment,
            privatePath, publicPath, createdAt);
    }
}
