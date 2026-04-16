package com.termlab.ssh.client;

import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detects whether an SSH private key file on disk is encrypted
 * (needs a passphrase) or unencrypted.
 *
 * <p>Implementation: invoke MINA SSHD's real parser via
 * {@link SecurityUtils#loadKeyPairIdentities} with a
 * {@link FilePasswordProvider} whose only job is to flip a flag and
 * throw when MINA asks it for a password. Three outcomes:
 *
 * <ol>
 *   <li><b>Load succeeds, probe never invoked</b> → {@link Encryption#NONE}.
 *       MINA parsed the key without needing a password.</li>
 *   <li><b>Load fails, probe WAS invoked</b> → {@link Encryption#ENCRYPTED}.
 *       The only way our provider gets called is if MINA determined the
 *       key is encrypted and needs a passphrase.</li>
 *   <li><b>Load fails, probe NOT invoked</b> → the failure is in parsing,
 *       not decryption. Wrap as {@link IOException} and propagate — the
 *       caller decides how to handle an unreadable or malformed file.</li>
 * </ol>
 *
 * <p>This is the same parser {@code TermLabSshClient.connect} uses for the
 * real load, so by construction the detection can't disagree with what
 * the subsequent load will do.
 */
public final class KeyFileInspector {

    /** Whether a key file needs a passphrase to decrypt. */
    public enum Encryption {
        /** The key loaded cleanly without any passphrase. */
        NONE,
        /** MINA's parser asked for a password before it could decrypt the key. */
        ENCRYPTED
    }

    private KeyFileInspector() {}

    /**
     * Probe {@code keyPath} and report whether it's encrypted.
     *
     * @throws IOException if the file is missing, unreadable, or not a
     *                     key format MINA can parse
     */
    public static @NotNull Encryption inspect(@NotNull Path keyPath) throws IOException {
        AtomicBoolean wasAsked = new AtomicBoolean(false);
        FilePasswordProvider probe = (session, resource, retry) -> {
            wasAsked.set(true);
            // Unwind MINA's parser. Any exception here will be caught
            // below; the wasAsked flag is how we distinguish "our probe
            // triggered this" from "the file is malformed".
            throw new RuntimeException("KeyFileInspector probe abort");
        };

        try (InputStream in = Files.newInputStream(keyPath)) {
            try {
                Iterable<KeyPair> pairs = SecurityUtils.loadKeyPairIdentities(
                    null,
                    NamedResource.ofName(keyPath.toString()),
                    in,
                    probe);
                if (pairs == null) {
                    throw new IOException(
                        "Could not parse key file " + keyPath + ": no key pairs found");
                }
                // Materialize the iterable — MINA may defer parsing to iteration time.
                for (KeyPair ignored : pairs) {
                    // just iterate; side-effects (password probe) happen here
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                if (wasAsked.get()) {
                    return Encryption.ENCRYPTED;
                }
                throw new IOException(
                    "Could not parse key file " + keyPath + ": " + e.getMessage(), e);
            }
        }
        return Encryption.NONE;
    }
}
