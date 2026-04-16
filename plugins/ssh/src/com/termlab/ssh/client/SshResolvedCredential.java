package com.termlab.ssh.client;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Short-lived holder for credential material that the SSH client actually
 * uses to authenticate. Distinct from the SDK's
 * {@code CredentialProvider.Credential} for two reasons:
 *
 * <ol>
 *   <li>It carries a {@link Path} for the key file (the SDK gives us a
 *       {@link String}; MINA wants a filesystem path).</li>
 *   <li>It guarantees a non-null {@link #username}, even when the source
 *       credential was a standalone SSH key (in which case the resolver
 *       falls back to {@code SshHost.username}).</li>
 * </ol>
 *
 * <p>AutoCloseable: on {@link #close()}, {@code password} and
 * {@code keyPassphrase} char arrays are zeroed. Callers should always
 * use try-with-resources around this type.
 */
public final class SshResolvedCredential implements AutoCloseable {

    public enum Mode {
        /** Password authentication only. */
        PASSWORD,
        /** SSH key authentication, passphrase optional. */
        KEY,
        /**
         * Both methods — MINA adds both identities to the session and
         * lets the server decide which it accepts.
         */
        KEY_AND_PASSWORD
    }

    private final String username;
    private final Mode mode;
    private final char @Nullable [] password;
    private final @Nullable Path keyPath;
    private final char @Nullable [] keyPassphrase;
    private boolean closed = false;

    public SshResolvedCredential(
        @NotNull String username,
        @NotNull Mode mode,
        char @Nullable [] password,
        @Nullable Path keyPath,
        char @Nullable [] keyPassphrase
    ) {
        this.username = username;
        this.mode = mode;
        this.password = password;
        this.keyPath = keyPath;
        this.keyPassphrase = keyPassphrase;
    }

    /** Username for the SSH connection. Never null, never empty. */
    public @NotNull String username() {
        ensureOpen();
        return username;
    }

    public @NotNull Mode mode() {
        ensureOpen();
        return mode;
    }

    /**
     * The raw password characters. {@code null} in {@link Mode#KEY} mode.
     * Callers should NOT copy or persist this array — use it immediately
     * and rely on {@link #close()} to zero it.
     */
    public char @Nullable [] password() {
        ensureOpen();
        return password;
    }

    /** Path to the private key file, or {@code null} for password-only mode. */
    public @Nullable Path keyPath() {
        ensureOpen();
        return keyPath;
    }

    /** Passphrase for the key file. {@code null} for unencrypted keys. */
    public char @Nullable [] keyPassphrase() {
        ensureOpen();
        return keyPassphrase;
    }

    @Override
    public void close() {
        if (closed) return;
        if (password != null) Arrays.fill(password, '\0');
        if (keyPassphrase != null) Arrays.fill(keyPassphrase, '\0');
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("SshResolvedCredential has already been closed");
        }
    }

    // -- Factories ------------------------------------------------------------

    /**
     * Create a password-only credential. The provided {@code password}
     * array is taken by reference — callers should not reuse or mutate
     * it after construction.
     */
    public static @NotNull SshResolvedCredential password(
        @NotNull String username,
        char @NotNull [] password
    ) {
        return new SshResolvedCredential(username, Mode.PASSWORD, password, null, null);
    }

    /**
     * Create a key-only credential. {@code keyPassphrase} may be null for
     * unencrypted keys.
     */
    public static @NotNull SshResolvedCredential key(
        @NotNull String username,
        @NotNull Path keyPath,
        char @Nullable [] keyPassphrase
    ) {
        return new SshResolvedCredential(username, Mode.KEY, null, keyPath, keyPassphrase);
    }

    /** Create a combined credential with both a key and a password. */
    public static @NotNull SshResolvedCredential keyAndPassword(
        @NotNull String username,
        @NotNull Path keyPath,
        char @Nullable [] keyPassphrase,
        char @NotNull [] password
    ) {
        return new SshResolvedCredential(username, Mode.KEY_AND_PASSWORD, password, keyPath, keyPassphrase);
    }
}
