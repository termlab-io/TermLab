package com.termlab.vault.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A single credential entry in the vault. Immutable — edits happen by
 * replacing the account with a new record carrying an updated {@code updatedAt}.
 */
public record VaultAccount(
    UUID id,
    String displayName,
    String username,
    AuthMethod auth,
    Instant createdAt,
    Instant updatedAt
) {}
