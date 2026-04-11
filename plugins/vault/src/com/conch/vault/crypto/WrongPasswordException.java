package com.conch.vault.crypto;

/**
 * Thrown by {@link VaultCrypto#decrypt(byte[], byte[])} when AES-GCM
 * authentication fails. In practice this almost always means the supplied
 * password was wrong; it can also indicate vault corruption that happens to
 * pass header checks but breaks the GCM tag.
 */
public class WrongPasswordException extends Exception {
    public WrongPasswordException() {
        super("wrong password or tampered vault");
    }
}
