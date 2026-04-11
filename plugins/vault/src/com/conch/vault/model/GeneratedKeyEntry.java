package com.conch.vault.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for an SSH key generated through the vault. The private and public
 * key files themselves live on disk at the recorded paths; this record holds
 * the metadata the UI displays and uses to delete/rotate keys.
 */
public record GeneratedKeyEntry(
    UUID id,
    String algorithm,      // "ed25519" | "ecdsa-p256" | "ecdsa-p384" | "rsa-3072" | "rsa-4096"
    String fingerprint,    // SHA256:base64-of-hash
    String comment,
    String privatePath,
    String publicPath,
    Instant createdAt
) {}
