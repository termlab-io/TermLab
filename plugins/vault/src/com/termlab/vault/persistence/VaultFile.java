package com.termlab.vault.persistence;

import com.termlab.vault.crypto.VaultCorruptedException;
import com.termlab.vault.crypto.VaultCrypto;
import com.termlab.vault.crypto.WrongPasswordException;
import com.termlab.vault.model.Vault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Atomic vault file I/O.
 *
 * <p>Saves go through a temporary file + rename so a crash mid-write can't
 * corrupt the existing vault. Loads are straightforward read-all-bytes +
 * decrypt.
 *
 * <p>All methods are stateless. Call sites typically go through the
 * {@code LockManager} in {@code com.termlab.vault.lock} rather than invoking
 * this class directly.
 */
public final class VaultFile {

    private VaultFile() {}

    /**
     * Encrypt and write {@code vault} to {@code target} atomically.
     *
     * @param target destination path — parent directories are created as needed
     * @param vault the vault to save
     * @param password raw password bytes; NOT zeroed by this method
     */
    public static void save(Path target, Vault vault, byte[] password) throws IOException {
        byte[] encrypted = VaultCrypto.encrypt(vault, password);
        saveRaw(target, encrypted);
    }

    /** Device-bound variant. See {@link VaultCrypto#encrypt(Vault, byte[], byte[])}. */
    public static void save(Path target, Vault vault, byte[] password, byte[] deviceSecret) throws IOException {
        byte[] encrypted = VaultCrypto.encrypt(vault, password, deviceSecret);
        saveRaw(target, encrypted);
    }

    private static void saveRaw(Path target, byte[] encrypted) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, encrypted);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Load and decrypt {@code source}.
     *
     * @throws WrongPasswordException  if the password doesn't match
     * @throws VaultCorruptedException if the file header is structurally invalid
     * @throws IOException             if the file can't be read
     */
    public static Vault load(Path source, byte[] password)
        throws IOException, WrongPasswordException, VaultCorruptedException {
        byte[] data = Files.readAllBytes(source);
        return VaultCrypto.decrypt(data, password);
    }

    /** Device-bound variant. */
    public static Vault load(Path source, byte[] password, byte[] deviceSecret)
        throws IOException, WrongPasswordException, VaultCorruptedException {
        byte[] data = Files.readAllBytes(source);
        return VaultCrypto.decrypt(data, password, deviceSecret);
    }

    public static boolean exists(Path target) {
        return Files.isRegularFile(target);
    }
}
