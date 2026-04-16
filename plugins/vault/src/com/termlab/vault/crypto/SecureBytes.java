package com.termlab.vault.crypto;

import java.util.Arrays;

/**
 * AutoCloseable wrapper around a byte array that zeroes its contents on
 * close. Used to hold short-lived secrets (master passwords, derived keys)
 * so they don't linger in memory after the operation that needed them.
 *
 * <p>Two constructors, with different ownership semantics:
 * <ul>
 *   <li>{@link #copyOf(byte[])} — defensive copy. Use when the caller wants
 *       to keep a reference to the source array.</li>
 *   <li>{@link #wrap(byte[])} — takes ownership of the passed array. Use when
 *       the caller is done with the array and wants {@code close()} to zero
 *       the original too.</li>
 * </ul>
 *
 * <p>Not thread-safe: a SecureBytes instance should be owned by a single
 * scope (typically try-with-resources inside one method call).
 */
public final class SecureBytes implements AutoCloseable {

    private final byte[] data;
    private boolean closed = false;

    private SecureBytes(byte[] data) {
        this.data = data;
    }

    /** Defensive copy of {@code source}. The source remains untouched. */
    public static SecureBytes copyOf(byte[] source) {
        return new SecureBytes(Arrays.copyOf(source, source.length));
    }

    /** Take ownership of {@code owned} — close() will zero it in place. */
    public static SecureBytes wrap(byte[] owned) {
        return new SecureBytes(owned);
    }

    public byte[] bytes() {
        if (closed) throw new IllegalStateException("SecureBytes already closed");
        return data;
    }

    public int length() {
        if (closed) throw new IllegalStateException("SecureBytes already closed");
        return data.length;
    }

    @Override
    public void close() {
        if (closed) return;
        Arrays.fill(data, (byte) 0);
        closed = true;
    }
}
