package com.termlab.share.codec;

import com.termlab.share.codec.exceptions.BundleCorruptedException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class ShareBundleFormat {

    public static final byte[] MAGIC = "TERMLABSHR".getBytes(StandardCharsets.US_ASCII);
    public static final int VERSION = 1;
    public static final int SALT_LEN = 16;
    public static final int NONCE_LEN = 12;

    private static final int HEADER_LEN = MAGIC.length + 4 + SALT_LEN + NONCE_LEN;

    public record Parsed(int version, byte[] salt, byte[] nonce, byte[] ciphertext) {}

    private ShareBundleFormat() {}

    public static byte[] assemble(byte[] salt, byte[] nonce, byte[] ciphertext) {
        if (salt.length != SALT_LEN) {
            throw new IllegalArgumentException("bad salt length");
        }
        if (nonce.length != NONCE_LEN) {
            throw new IllegalArgumentException("bad nonce length");
        }
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LEN + ciphertext.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.putInt(VERSION);
        buf.put(salt);
        buf.put(nonce);
        buf.put(ciphertext);
        return buf.array();
    }

    public static Parsed parse(byte[] data) throws BundleCorruptedException {
        if (data == null || data.length < HEADER_LEN) {
            throw new BundleCorruptedException("bundle shorter than header");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                throw new BundleCorruptedException("not a termlab share bundle (bad magic)");
            }
        }
        int version = ByteBuffer.wrap(data, MAGIC.length, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] salt = new byte[SALT_LEN];
        System.arraycopy(data, MAGIC.length + 4, salt, 0, SALT_LEN);
        byte[] nonce = new byte[NONCE_LEN];
        System.arraycopy(data, MAGIC.length + 4 + SALT_LEN, nonce, 0, NONCE_LEN);
        byte[] ciphertext = new byte[data.length - HEADER_LEN];
        System.arraycopy(data, HEADER_LEN, ciphertext, 0, ciphertext.length);
        return new Parsed(version, salt, nonce, ciphertext);
    }
}
