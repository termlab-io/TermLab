package com.termlab.vault.crypto;

/**
 * Thrown when the on-disk vault file fails structural validation — bad magic
 * bytes, truncated header, or an unknown version. Distinct from
 * {@link WrongPasswordException}, which is thrown only after the header parses
 * cleanly but AES-GCM authentication fails.
 */
public class VaultCorruptedException extends Exception {
    public VaultCorruptedException(String message) {
        super(message);
    }
}
