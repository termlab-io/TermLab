package com.conch.minecraftadmin.credentials;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Short-lived holder for a resolved Minecraft credential (username + password).
 *
 * <p>Implements {@link AutoCloseable}; callers should use try-with-resources
 * to ensure {@link #close()} zeroes the password char array when done.
 */
public final class McCredential implements AutoCloseable {

    private final @NotNull String username;
    private final @NotNull char[] password;

    public McCredential(@NotNull String username, @NotNull char[] password) {
        this.username = username;
        this.password = password;
    }

    /** The username associated with this credential. */
    public @NotNull String username() {
        return username;
    }

    /** The password as a char array. Do not retain a reference after {@link #close()}. */
    public @NotNull char[] password() {
        return password;
    }

    /** Zeroes the password char array. Safe to call multiple times. */
    @Override
    public void close() {
        Arrays.fill(password, '\0');
    }
}
