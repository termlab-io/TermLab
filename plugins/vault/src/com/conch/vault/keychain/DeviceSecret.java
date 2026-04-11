package com.conch.vault.keychain;

import org.jetbrains.annotations.NotNull;

import java.security.SecureRandom;

/**
 * Cross-platform device-secret manager.
 *
 * <p>The device secret is a 32-byte random value that gets mixed into the
 * Argon2id key derivation alongside the user's master password. Because
 * the secret lives in the OS credential store — not in the vault file
 * itself — copying the vault file to another machine produces a blob that
 * nothing can decrypt. This is the "device binding" part of the security
 * model described in {@code docs/specs/2026-04-08-conch-workstation-design.md}.
 *
 * <p>All storage goes through a {@link DeviceSecretStore}, which abstracts
 * the platform-specific details. Production callers use
 * {@link #defaultStore()} to get the PasswordSafe-backed implementation;
 * tests inject {@link InMemoryDeviceSecretStore}.
 *
 * <p>This class is stateless — it's a thin facade that reads the store on
 * every call. The secret itself is never cached as a static field.
 */
public final class DeviceSecret {

    private static final SecureRandom RNG = new SecureRandom();

    private final DeviceSecretStore store;

    public DeviceSecret(@NotNull DeviceSecretStore store) {
        this.store = store;
    }

    /** @return a DeviceSecret that reads/writes via IntelliJ's PasswordSafe. */
    public static DeviceSecret defaultInstance() {
        return new DeviceSecret(defaultStore());
    }

    /** @return the production store — IntelliJ's cross-platform PasswordSafe. */
    public static DeviceSecretStore defaultStore() {
        return new PasswordSafeDeviceSecretStore();
    }

    /**
     * Read the existing secret, or generate and store a fresh one if none
     * exists yet. Idempotent after the first call — subsequent calls return
     * the same 32 bytes.
     *
     * @return a 32-byte array. The caller should NOT mutate or zero it;
     *         a fresh copy is returned on every call.
     */
    public byte @NotNull [] getOrCreate() {
        byte[] existing = store.read();
        if (existing != null && existing.length == 32) {
            return existing;
        }
        byte[] fresh = new byte[32];
        RNG.nextBytes(fresh);
        store.write(fresh);
        return fresh;
    }

    /**
     * Forget the device secret. A vault file encrypted under the old secret
     * becomes permanently undecryptable after this call — even on the same
     * machine. Intended for "reset everything" flows.
     */
    public void delete() {
        store.delete();
    }
}
