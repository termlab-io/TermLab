package com.conch.vault.keychain;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Storage backend for the vault's 32-byte device secret. Abstracted so that:
 *
 * <ul>
 *   <li><b>Production</b> uses {@link PasswordSafeDeviceSecretStore}, which
 *       delegates to IntelliJ's {@code PasswordSafe} service. PasswordSafe
 *       routes to the OS-native credential store on each platform (Keychain
 *       on macOS, Credential Manager on Windows, KWallet/libsecret on Linux)
 *       and falls back to an encrypted file when no OS store is available.
 *   </li>
 *   <li><b>Tests</b> use {@link InMemoryDeviceSecretStore}, which avoids the
 *       IntelliJ application container entirely. Our standalone JUnit runner
 *       doesn't spin up a full IDE, so it can't instantiate the real
 *       PasswordSafe — and we don't want unit tests to touch the user's real
 *       OS keychain anyway.
 *   </li>
 * </ul>
 */
public interface DeviceSecretStore {

    /**
     * @return the stored 32-byte secret, or {@code null} if none exists yet.
     */
    byte @Nullable [] read();

    /**
     * Store {@code secret}, overwriting any previous value.
     *
     * @param secret must be exactly 32 bytes
     */
    void write(byte @NotNull [] secret);

    /** Remove the stored secret if it exists. No-op if missing. */
    void delete();
}
