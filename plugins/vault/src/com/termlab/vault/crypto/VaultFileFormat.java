package com.termlab.vault.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * On-disk layout for the encrypted vault file.
 *
 * <pre>
 *   [MAGIC(8) | VERSION(4 LE) | SALT(16) | NONCE(12) | CIPHERTEXT(...)]
 * </pre>
 *
 * <p>The layout matches the rusty_termlab_2 Rust reference so the format is
 * cross-language-stable. Only the inner serialization differs — Rust used
 * bincode, Java uses JSON via Gson. Vault files are therefore not byte-compat
 * between Rust and Java versions; a migration importer is not planned for v1.
 */
public final class VaultFileFormat {

    public static final byte[] MAGIC = "TERMLABVLT".getBytes(StandardCharsets.US_ASCII);
    public static final int VERSION = 1;
    public static final int SALT_LEN = 16;
    public static final int NONCE_LEN = 12;

    private static final int HEADER_LEN = MAGIC.length + 4 + SALT_LEN + NONCE_LEN;  // 40

    /** Parsed header + ciphertext slice returned from {@link #parse(byte[])}. */
    public record Parsed(int version, byte[] salt, byte[] nonce, byte[] ciphertext) {}

    private VaultFileFormat() {}

    public static byte[] assemble(byte[] salt, byte[] nonce, byte[] ciphertext) {
        if (salt.length != SALT_LEN) {
            throw new IllegalArgumentException("salt length: " + salt.length);
        }
        if (nonce.length != NONCE_LEN) {
            throw new IllegalArgumentException("nonce length: " + nonce.length);
        }
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LEN + ciphertext.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.putInt(VERSION);
        buf.put(salt);
        buf.put(nonce);
        buf.put(ciphertext);
        return buf.array();
    }

    public static Parsed parse(byte[] data) throws VaultCorruptedException {
        if (data.length < HEADER_LEN) {
            throw new VaultCorruptedException(
                "vault file too short (got " + data.length + " bytes, need at least " + HEADER_LEN + ")");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                throw new VaultCorruptedException("invalid magic bytes");
            }
        }
        int version = ByteBuffer.wrap(data, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (version != VERSION) {
            throw new VaultCorruptedException("unsupported vault version: " + version);
        }
        byte[] salt = new byte[SALT_LEN];
        System.arraycopy(data, 12, salt, 0, SALT_LEN);
        byte[] nonce = new byte[NONCE_LEN];
        System.arraycopy(data, 12 + SALT_LEN, nonce, 0, NONCE_LEN);
        byte[] ciphertext = new byte[data.length - HEADER_LEN];
        System.arraycopy(data, HEADER_LEN, ciphertext, 0, ciphertext.length);
        return new Parsed(version, salt, nonce, ciphertext);
    }
}
