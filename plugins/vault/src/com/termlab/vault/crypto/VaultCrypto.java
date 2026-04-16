package com.termlab.vault.crypto;

import com.termlab.vault.model.Vault;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-256-GCM encrypt/decrypt of a {@link Vault} object.
 *
 * <p>Serializes the Vault to JSON via Gson, encrypts the JSON bytes, and
 * assembles the result into the on-disk layout defined by {@link VaultFileFormat}.
 * Decryption runs the reverse: parse the header, derive the key, authenticate
 * and decrypt the ciphertext, then parse the JSON back into a Vault.
 *
 * <p>A fresh 16-byte salt and 12-byte nonce are generated on every encrypt
 * call. Derived keys and plaintext bytes are zeroed in a {@code finally} block
 * so they don't linger in the heap after the call returns. The JSON bytes are
 * zeroed too, but the {@link String} form Gson parses into during decrypt is
 * not — this is the known v1 weakness tracked in the vault plan's Risks
 * section and fixed in a future hardening pass.
 */
public final class VaultCrypto {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int GCM_TAG_BITS = 128;

    private VaultCrypto() {}

    /**
     * Encrypt {@code vault} with the given password. The output is an
     * on-disk-ready byte array containing the full header + ciphertext.
     *
     * @param vault the vault to encrypt
     * @param password raw password bytes; NOT zeroed by this method
     * @return a ready-to-write byte array
     */
    public static byte[] encrypt(Vault vault, byte[] password) {
        byte[] salt = new byte[VaultFileFormat.SALT_LEN];
        RNG.nextBytes(salt);
        byte[] key = KeyDerivation.deriveKey(password, salt);
        byte[] plaintext = VaultGson.GSON.toJson(vault).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] nonce = new byte[VaultFileFormat.NONCE_LEN];
            RNG.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return VaultFileFormat.assemble(salt, nonce, ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("vault encryption failed", e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Encrypt a vault with password + device-bound secret. Use this variant
     * whenever a device secret is available (it always is on supported
     * platforms); the password-only overload exists for tests and recovery.
     */
    public static byte[] encrypt(Vault vault, byte[] password, byte[] deviceSecret) {
        byte[] salt = new byte[VaultFileFormat.SALT_LEN];
        RNG.nextBytes(salt);
        byte[] key = KeyDerivation.deriveKey(password, deviceSecret, salt);
        byte[] plaintext = VaultGson.GSON.toJson(vault).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] nonce = new byte[VaultFileFormat.NONCE_LEN];
            RNG.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return VaultFileFormat.assemble(salt, nonce, ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("vault encryption failed", e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Decrypt vault bytes back into a {@link Vault}. Throws
     * {@link WrongPasswordException} if the GCM tag doesn't authenticate
     * (almost certainly a bad password) and {@link VaultCorruptedException}
     * if the header is structurally invalid.
     */
    public static Vault decrypt(byte[] data, byte[] password)
        throws WrongPasswordException, VaultCorruptedException {

        VaultFileFormat.Parsed parsed = VaultFileFormat.parse(data);
        byte[] key = KeyDerivation.deriveKey(password, parsed.salt());
        try {
            return decryptWithKey(parsed, key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Same as {@link #decrypt(byte[], byte[])} but binds to a device secret. */
    public static Vault decrypt(byte[] data, byte[] password, byte[] deviceSecret)
        throws WrongPasswordException, VaultCorruptedException {

        VaultFileFormat.Parsed parsed = VaultFileFormat.parse(data);
        byte[] key = KeyDerivation.deriveKey(password, deviceSecret, parsed.salt());
        try {
            return decryptWithKey(parsed, key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static Vault decryptWithKey(VaultFileFormat.Parsed parsed, byte[] key)
        throws WrongPasswordException {
        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, parsed.nonce()));
            plaintext = cipher.doFinal(parsed.ciphertext());
        } catch (AEADBadTagException e) {
            throw new WrongPasswordException();
        } catch (Exception e) {
            throw new RuntimeException("vault decryption failed", e);
        }
        try {
            return VaultGson.GSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), Vault.class);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }
}
