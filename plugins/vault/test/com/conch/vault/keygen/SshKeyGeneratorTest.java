package com.conch.vault.keygen;

import com.conch.vault.model.VaultKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SshKeyGeneratorTest {

    @Test
    void ed25519_roundTrip(@TempDir Path tmp) throws Exception {
        SshKeyGenerator gen = new SshKeyGenerator(tmp);
        VaultKey entry = gen.generate(KeyGenAlgorithm.ED25519, "test key", "test@example.com");

        assertFiles(entry, "ed25519");
        assertPublicKeyLineStartsWith(entry.publicPath(), "ssh-ed25519 ");
        assertCommentPresent(entry.publicPath(), "test@example.com");
        assertFingerprintShape(entry.fingerprint());
    }

    @Test
    void ecdsa_p256_roundTrip(@TempDir Path tmp) throws Exception {
        SshKeyGenerator gen = new SshKeyGenerator(tmp);
        VaultKey entry = gen.generate(KeyGenAlgorithm.ECDSA_P256, "unnamed", "");

        assertFiles(entry, "ecdsa_p256");
        assertPublicKeyLineStartsWith(entry.publicPath(), "ecdsa-sha2-nistp256 ");
        assertFingerprintShape(entry.fingerprint());
    }

    @Test
    void ecdsa_p384_roundTrip(@TempDir Path tmp) throws Exception {
        SshKeyGenerator gen = new SshKeyGenerator(tmp);
        VaultKey entry = gen.generate(KeyGenAlgorithm.ECDSA_P384, "laptop-key", "laptop");

        assertFiles(entry, "ecdsa_p384");
        assertPublicKeyLineStartsWith(entry.publicPath(), "ecdsa-sha2-nistp384 ");
        assertCommentPresent(entry.publicPath(), "laptop");
    }

    @Test
    void rsa_3072_roundTrip(@TempDir Path tmp) throws Exception {
        SshKeyGenerator gen = new SshKeyGenerator(tmp);
        VaultKey entry = gen.generate(KeyGenAlgorithm.RSA_3072, "rsa-test-key", "rsa-test");

        assertFiles(entry, "rsa_3072");
        assertPublicKeyLineStartsWith(entry.publicPath(), "ssh-rsa ");
        assertFingerprintShape(entry.fingerprint());
    }

    @Test
    void privateKey_hasPemMarkers(@TempDir Path tmp) throws Exception {
        SshKeyGenerator gen = new SshKeyGenerator(tmp);
        VaultKey entry = gen.generate(KeyGenAlgorithm.ED25519, "unnamed", "");

        String pem = Files.readString(Paths.get(entry.privatePath()));
        assertTrue(pem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"),
            "private key should start with OpenSSH PEM header");
        assertTrue(pem.trim().endsWith("-----END OPENSSH PRIVATE KEY-----"),
            "private key should end with OpenSSH PEM footer");
    }

    @Test
    void privateKey_isOwnerOnlyOnPosix(@TempDir Path tmp) throws Exception {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;  // Skip on non-POSIX filesystems.
        }
        SshKeyGenerator gen = new SshKeyGenerator(tmp);
        VaultKey entry = gen.generate(KeyGenAlgorithm.ED25519, "unnamed", "");

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(Paths.get(entry.privatePath()));
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
        assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
        assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
    }

    @Test
    void delete_removesBothFiles(@TempDir Path tmp) throws Exception {
        SshKeyGenerator gen = new SshKeyGenerator(tmp);
        VaultKey entry = gen.generate(KeyGenAlgorithm.ED25519, "unnamed", "");

        assertTrue(Files.exists(Paths.get(entry.privatePath())));
        assertTrue(Files.exists(Paths.get(entry.publicPath())));
        gen.delete(entry);
        assertFalse(Files.exists(Paths.get(entry.privatePath())));
        assertFalse(Files.exists(Paths.get(entry.publicPath())));
    }

    @Test
    void delete_isIdempotent(@TempDir Path tmp) throws Exception {
        SshKeyGenerator gen = new SshKeyGenerator(tmp);
        VaultKey entry = gen.generate(KeyGenAlgorithm.ED25519, "unnamed", "");
        gen.delete(entry);
        assertDoesNotThrow(() -> gen.delete(entry));
    }

    @Test
    void twoGenerations_differentFiles(@TempDir Path tmp) throws Exception {
        SshKeyGenerator gen = new SshKeyGenerator(tmp);
        VaultKey a = gen.generate(KeyGenAlgorithm.ED25519, "a", "a");
        VaultKey b = gen.generate(KeyGenAlgorithm.ED25519, "b", "b");
        assertNotEquals(a.privatePath(), b.privatePath());
        assertNotEquals(a.publicPath(), b.publicPath());
        assertNotEquals(a.id(), b.id());
    }

    /**
     * End-to-end interop with the real {@code ssh-keygen}. Verifies that
     * OpenSSH accepts the files we produce by computing the public key's
     * fingerprint and comparing it to ours. Skipped on systems where
     * {@code ssh-keygen} isn't on PATH (mostly Windows CI).
     */
    @Test
    void realSshKeygen_acceptsPublicKeys(@TempDir Path tmp) throws Exception {
        if (!isSshKeygenAvailable()) return;

        SshKeyGenerator gen = new SshKeyGenerator(tmp);
        for (KeyGenAlgorithm a : KeyGenAlgorithm.values()) {
            VaultKey entry = gen.generate(a, "interop-test", "test");
            String output = runSshKeygen("-l", "-f", entry.publicPath());
            // ssh-keygen prints: "<bits> SHA256:<hash> <comment> (<type>)"
            assertTrue(output.contains(entry.fingerprint()),
                a + ": ssh-keygen fingerprint mismatch.\n"
                    + "  ours:        " + entry.fingerprint() + "\n"
                    + "  ssh-keygen:  " + output);
        }
    }

    private static boolean isSshKeygenAvailable() {
        try {
            Process p = new ProcessBuilder("ssh-keygen", "-V").redirectErrorStream(true).start();
            p.waitFor();
            return true;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String runSshKeygen(String... args) throws IOException, InterruptedException {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("ssh-keygen");
        java.util.Collections.addAll(cmd, args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        int rc = p.waitFor();
        String output = new String(out);
        assertEquals(0, rc, "ssh-keygen exited " + rc + ": " + output);
        return output;
    }

    // -- assertions -----------------------------------------------------------

    private static void assertFiles(VaultKey entry, String algoIdFragment) throws IOException {
        Path priv = Paths.get(entry.privatePath());
        Path pub = Paths.get(entry.publicPath());
        assertTrue(Files.isRegularFile(priv), "private key should exist: " + priv);
        assertTrue(Files.isRegularFile(pub), "public key should exist: " + pub);
        assertTrue(priv.getFileName().toString().contains(algoIdFragment),
            "filename should reference the algorithm: " + priv);
        assertEquals(priv.getFileName().toString() + ".pub", pub.getFileName().toString());
    }

    private static void assertPublicKeyLineStartsWith(String publicPath, String prefix) throws IOException {
        String line = Files.readString(Paths.get(publicPath)).trim();
        assertTrue(line.startsWith(prefix),
            "public key line should start with '" + prefix + "', got: " + line);
    }

    private static void assertCommentPresent(String publicPath, String comment) throws IOException {
        String line = Files.readString(Paths.get(publicPath)).trim();
        assertTrue(line.endsWith(comment),
            "public key line should end with comment '" + comment + "', got: " + line);
    }

    private static void assertFingerprintShape(String fingerprint) {
        assertNotNull(fingerprint);
        assertTrue(fingerprint.startsWith("SHA256:"),
            "fingerprint should start with SHA256:, got: " + fingerprint);
        // SHA-256 is 32 bytes = 43 base64 chars (no padding).
        String b64 = fingerprint.substring("SHA256:".length());
        assertEquals(43, b64.length(), "SHA-256 fingerprint body length");
    }
}
