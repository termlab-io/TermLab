package com.conch.ssh.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KeyFileInspectorTest {

    @Test
    void inspect_unencryptedOpenSshKey_returnsNone(@TempDir Path tmp) throws Exception {
        Path keyPath = tmp.resolve("id_unenc");
        Files.writeString(keyPath, UNENCRYPTED_ED25519);

        assertEquals(
            KeyFileInspector.Encryption.NONE,
            KeyFileInspector.inspect(keyPath));
    }

    @Test
    void inspect_encryptedOpenSshKey_returnsEncrypted(@TempDir Path tmp) throws Exception {
        Path keyPath = tmp.resolve("id_enc");
        Files.writeString(keyPath, ENCRYPTED_ED25519);

        assertEquals(
            KeyFileInspector.Encryption.ENCRYPTED,
            KeyFileInspector.inspect(keyPath));
    }

    @Test
    void inspect_missingFile_throwsIOException(@TempDir Path tmp) {
        Path keyPath = tmp.resolve("nonexistent");
        assertThrows(IOException.class, () -> KeyFileInspector.inspect(keyPath));
    }

    @Test
    void inspect_garbageFile_throwsIOException(@TempDir Path tmp) throws Exception {
        Path keyPath = tmp.resolve("garbage");
        Files.writeString(keyPath, "not a key at all, just some bytes\n");

        assertThrows(IOException.class, () -> KeyFileInspector.inspect(keyPath));
    }

    // -- fixtures -------------------------------------------------------------
    //
    // Throwaway Ed25519 keys generated via `ssh-keygen -t ed25519`. They
    // exist only to exercise MINA's parser — they're not valid
    // credentials for any real host. The encrypted variant uses
    // passphrase "test-passphrase".

    private static final String UNENCRYPTED_ED25519 =
        "-----BEGIN OPENSSH PRIVATE KEY-----\n"
        + "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\n"
        + "QyNTUxOQAAACCSWhy8xyD/oaVcxp3mP0t0ClZJRp3zfC8bZaki6b/onQAAAJg5u4O7ObuD\n"
        + "uwAAAAtzc2gtZWQyNTUxOQAAACCSWhy8xyD/oaVcxp3mP0t0ClZJRp3zfC8bZaki6b/onQ\n"
        + "AAAEDg0aZNQcOHeaUc1duO8iuGiBDbOQ3kZqKmylS4ehiibJJaHLzHIP+hpVzGneY/S3QK\n"
        + "VklGnfN8LxtlqSLpv+idAAAAEHRlc3QtdW5lbmNyeXB0ZWQBAgMEBQ==\n"
        + "-----END OPENSSH PRIVATE KEY-----\n";

    private static final String ENCRYPTED_ED25519 =
        "-----BEGIN OPENSSH PRIVATE KEY-----\n"
        + "b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABDmmpmxdl\n"
        + "F3taXhLJstFfH9AAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIPWZuhfhUPZM3nWK\n"
        + "XmIGgXS8q4ZNuZrylqO3horzOIMXAAAAoMggc1l3S3ejToteDLTxPzWZdFGVkzCm/7nbuM\n"
        + "OBxhCSN9rB5vbqvmSb/M6AG30LWaMWXHJiaR11BFdsyhvNluPdG0TIRSWBHvswThRa5hP5\n"
        + "7Dnm28/ZwjqBYFa66am5VWPZkVka8brXnJoqkJHg0T81479C3cr8/swSxeclpylUZ8Eu0E\n"
        + "EG5bZXzWXJxWWi/jE8jt4CQNfU9eW4WINeccU=\n"
        + "-----END OPENSSH PRIVATE KEY-----\n";
}
