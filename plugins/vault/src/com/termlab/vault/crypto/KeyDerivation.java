package com.termlab.vault.crypto;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Argon2id-based key derivation for vault master passwords.
 *
 * <p>Parameters match the rusty_termlab_2 Rust reference: m=64 MB, t=3, p=4,
 * 32-byte output. These values are deliberately expensive (~250 ms on M1)
 * to resist GPU brute-force attacks on a leaked vault file.
 */
public final class KeyDerivation {

    /** Derived key length (AES-256). */
    public static final int KEY_LEN = 32;

    /** Salt length — random-per-vault, stored in the file header. */
    public static final int SALT_LEN = 16;

    private static final int M_COST = 65536;  // 64 MiB
    private static final int T_COST = 3;      // 3 iterations
    private static final int P_COST = 4;      // 4 lanes

    private KeyDerivation() {}

    /**
     * Derive a 32-byte key from a password and salt using Argon2id.
     *
     * @param password raw password bytes (UTF-8 encoding of the user's typed password)
     * @param salt must be exactly {@link #SALT_LEN} bytes
     * @return fresh 32-byte key. Caller is responsible for zeroing it when done.
     */
    public static byte[] deriveKey(byte[] password, byte[] salt) {
        if (salt.length != SALT_LEN) {
            throw new IllegalArgumentException("salt must be " + SALT_LEN + " bytes, got " + salt.length);
        }
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(M_COST)
            .withIterations(T_COST)
            .withParallelism(P_COST)
            .withSalt(salt)
            .build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] out = new byte[KEY_LEN];
        gen.generateBytes(password, out);
        return out;
    }

    /**
     * Derive a 32-byte key from a password + device secret + salt.
     *
     * <p>Concatenates {@code password || deviceSecret} and feeds the result
     * to Argon2. This binds the derived key to both something the user knows
     * (password) and something the device has (OS-keychain secret), so a
     * copied vault file is undecryptable on another machine.
     */
    public static byte[] deriveKey(byte[] password, byte[] deviceSecret, byte[] salt) {
        byte[] combined = new byte[password.length + deviceSecret.length];
        System.arraycopy(password, 0, combined, 0, password.length);
        System.arraycopy(deviceSecret, 0, combined, password.length, deviceSecret.length);
        try {
            return deriveKey(combined, salt);
        } finally {
            java.util.Arrays.fill(combined, (byte) 0);
        }
    }
}
