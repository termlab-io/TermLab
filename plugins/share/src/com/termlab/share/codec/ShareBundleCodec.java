package com.termlab.share.codec;

import com.termlab.share.codec.exceptions.BundleCorruptedException;
import com.termlab.share.codec.exceptions.UnsupportedBundleVersionException;
import com.termlab.share.codec.exceptions.WrongBundlePasswordException;
import com.termlab.share.model.ShareBundle;
import com.termlab.vault.crypto.KeyDerivation;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

public final class ShareBundleCodec {

    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    private ShareBundleCodec() {}

    public static byte[] encode(ShareBundle bundle, byte[] password) {
        byte[] plaintext = ShareBundleGson.GSON.toJson(bundle).getBytes(StandardCharsets.UTF_8);
        byte[] salt = new byte[ShareBundleFormat.SALT_LEN];
        byte[] nonce = new byte[ShareBundleFormat.NONCE_LEN];
        RNG.nextBytes(salt);
        RNG.nextBytes(nonce);

        byte[] key = KeyDerivation.deriveKey(password, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ShareBundleFormat.assemble(salt, nonce, ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("bundle encryption failed", e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    public static ShareBundle decode(byte[] data, byte[] password)
        throws BundleCorruptedException, WrongBundlePasswordException, UnsupportedBundleVersionException {
        ShareBundleFormat.Parsed parsed = ShareBundleFormat.parse(data);
        if (parsed.version() != ShareBundleFormat.VERSION) {
            throw new UnsupportedBundleVersionException(parsed.version());
        }

        byte[] key = KeyDerivation.deriveKey(password, parsed.salt());
        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, parsed.nonce()));
            plaintext = cipher.doFinal(parsed.ciphertext());
        } catch (AEADBadTagException e) {
            throw new WrongBundlePasswordException();
        } catch (Exception e) {
            throw new BundleCorruptedException("bundle decryption failed", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }

        ShareBundle bundle;
        try {
            bundle = ShareBundleGson.GSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), ShareBundle.class);
        } catch (Exception e) {
            throw new BundleCorruptedException("bundle JSON parse failed", e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
        if (bundle == null) {
            throw new BundleCorruptedException("bundle decoded to null");
        }
        if (bundle.schemaVersion() != ShareBundle.CURRENT_SCHEMA_VERSION) {
            throw new UnsupportedBundleVersionException(bundle.schemaVersion());
        }
        return bundle;
    }
}
