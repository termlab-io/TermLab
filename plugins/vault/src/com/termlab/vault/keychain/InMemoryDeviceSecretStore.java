package com.termlab.vault.keychain;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Plain-memory {@link DeviceSecretStore} used by unit tests and anywhere
 * the IntelliJ application container isn't available. Not for production
 * use — the secret dies with the process.
 */
public final class InMemoryDeviceSecretStore implements DeviceSecretStore {

    private byte @Nullable [] secret;

    @Override
    public byte @Nullable [] read() {
        return secret == null ? null : Arrays.copyOf(secret, secret.length);
    }

    @Override
    public void write(byte @NotNull [] secret) {
        if (secret.length != 32) {
            throw new IllegalArgumentException("device secret must be 32 bytes, got " + secret.length);
        }
        this.secret = Arrays.copyOf(secret, secret.length);
    }

    @Override
    public void delete() {
        if (secret != null) {
            Arrays.fill(secret, (byte) 0);
            secret = null;
        }
    }
}
