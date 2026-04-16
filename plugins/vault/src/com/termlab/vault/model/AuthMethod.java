package com.termlab.vault.model;

import org.jetbrains.annotations.Nullable;

/**
 * One of three authentication methods for a vault account. Sealed because
 * adding a new variant forces every consumer (UI, SSH plugin) to handle it
 * explicitly via pattern match.
 *
 * <p>Plaintext password and passphrase fields are intentionally {@link String}
 * here because Gson serializes Strings natively. In-memory after decryption
 * they should be copied into {@code char[]} at the SDK boundary
 * ({@code com.termlab.vault.credentials.AuthMethodMapper}) so consumers can
 * zero them after use. The String form only exists between disk-decrypt and
 * SDK-boundary conversion. This is a known v1 weakness tracked in the vault
 * plan's "Risks and gotchas" section.
 */
public sealed interface AuthMethod
    permits AuthMethod.Password, AuthMethod.Key, AuthMethod.KeyAndPassword {

    record Password(String password) implements AuthMethod {}

    record Key(String keyPath, @Nullable String passphrase) implements AuthMethod {}

    record KeyAndPassword(String keyPath, @Nullable String passphrase, String password)
        implements AuthMethod {}
}
