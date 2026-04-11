package com.conch.ssh.persistence;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Security;

import static org.junit.jupiter.api.Assertions.*;

class KnownHostsFileTest {

    @BeforeAll
    static void registerBouncyCastle() {
        // BouncyCastle provider is needed so KeyPairGenerator.getInstance("Ed25519")
        // resolves in this JVM. Safe to register repeatedly — Security.addProvider
        // is a no-op if the provider is already there.
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    @Test
    void match_unknownHost_whenFileMissing(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("known_hosts");
        PublicKey key = freshEd25519Key();
        assertEquals(KnownHostsFile.Match.UNKNOWN,
            KnownHostsFile.match(file, "example.com", 22, key));
    }

    @Test
    void match_unknownHost_whenFileEmpty(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("known_hosts");
        Files.writeString(file, "");
        PublicKey key = freshEd25519Key();
        assertEquals(KnownHostsFile.Match.UNKNOWN,
            KnownHostsFile.match(file, "example.com", 22, key));
    }

    @Test
    void match_matchAfterAppend(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("known_hosts");
        PublicKey key = freshEd25519Key();

        KnownHostsFile.append(file, "example.com", 22, key);
        assertEquals(KnownHostsFile.Match.MATCH,
            KnownHostsFile.match(file, "example.com", 22, key));
    }

    @Test
    void match_mismatchWhenKeyChanges(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("known_hosts");
        PublicKey originalKey = freshEd25519Key();
        PublicKey replacementKey = freshEd25519Key();

        KnownHostsFile.append(file, "example.com", 22, originalKey);
        assertEquals(KnownHostsFile.Match.MISMATCH,
            KnownHostsFile.match(file, "example.com", 22, replacementKey));
    }

    @Test
    void match_unknownForDifferentHost(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("known_hosts");
        PublicKey key = freshEd25519Key();
        KnownHostsFile.append(file, "example.com", 22, key);
        assertEquals(KnownHostsFile.Match.UNKNOWN,
            KnownHostsFile.match(file, "other.example.com", 22, key));
    }

    @Test
    void match_nonStandardPortRoundTrip(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("known_hosts");
        PublicKey key = freshEd25519Key();
        KnownHostsFile.append(file, "example.com", 2222, key);
        assertEquals(KnownHostsFile.Match.MATCH,
            KnownHostsFile.match(file, "example.com", 2222, key));
    }

    @Test
    void append_createsFileAndParentDirectory(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("a/b/c/known_hosts");
        PublicKey key = freshEd25519Key();
        KnownHostsFile.append(file, "example.com", 22, key);
        assertTrue(Files.exists(file));
    }

    @Test
    void append_producesOpenSshFormattedLine(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("known_hosts");
        PublicKey key = freshEd25519Key();
        KnownHostsFile.append(file, "example.com", 22, key);

        String contents = Files.readString(file);
        String[] lines = contents.split("\n");
        assertEquals(1, lines.length);
        // Format: "<hostspec> ssh-<type> <base64>"
        String[] parts = lines[0].split(" ");
        assertEquals("example.com", parts[0]);
        assertTrue(parts[1].startsWith("ssh-"),
            "expected ssh- prefix on key type, got: " + parts[1]);
    }

    @Test
    void append_twoHostsOnSamePort(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("known_hosts");
        PublicKey a = freshEd25519Key();
        PublicKey b = freshEd25519Key();
        KnownHostsFile.append(file, "a.example.com", 22, a);
        KnownHostsFile.append(file, "b.example.com", 22, b);

        assertEquals(KnownHostsFile.Match.MATCH,
            KnownHostsFile.match(file, "a.example.com", 22, a));
        assertEquals(KnownHostsFile.Match.MATCH,
            KnownHostsFile.match(file, "b.example.com", 22, b));
        // Presenting b's key for a.example.com is a mismatch — that's
        // exactly what known_hosts exists to detect.
        assertEquals(KnownHostsFile.Match.MISMATCH,
            KnownHostsFile.match(file, "a.example.com", 22, b));
    }

    @Test
    void fingerprint_isSha256Format() throws Exception {
        PublicKey key = freshEd25519Key();
        String fp = KnownHostsFile.fingerprint(key);
        assertNotNull(fp);
        assertTrue(fp.startsWith("SHA256:"),
            "expected OpenSSH SHA256: prefix, got: " + fp);
    }

    @Test
    void fingerprint_deterministicForSameKey() throws Exception {
        PublicKey key = freshEd25519Key();
        assertEquals(KnownHostsFile.fingerprint(key), KnownHostsFile.fingerprint(key));
    }

    // -- helpers --------------------------------------------------------------

    private static PublicKey freshEd25519Key() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519", "BC");
        KeyPair kp = gen.generateKeyPair();
        return kp.getPublic();
    }
}
