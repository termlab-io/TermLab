package com.termlab.share.conversion;

import com.termlab.share.model.BundledKeyMaterial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyFileImporterTest {

    @Test
    void missingFile_returnsWarning(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope");
        var result = KeyFileImporter.read(missing, null);
        assertInstanceOf(KeyFileImporter.Result.Warning.class, result);
        assertTrue(((KeyFileImporter.Result.Warning) result).message().contains("not found"));
    }

    @Test
    void unencryptedOpenSshKey_returnsOk(@TempDir Path tmp) throws Exception {
        Path key = tmp.resolve("id_ed25519");
        Files.writeString(key, """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZWQ=
            -----END OPENSSH PRIVATE KEY-----
            """);

        var result = KeyFileImporter.read(key, null);
        assertInstanceOf(KeyFileImporter.Result.Ok.class, result);
        BundledKeyMaterial m = ((KeyFileImporter.Result.Ok) result).material();
        assertNotNull(m.id());
        assertNotNull(m.privateKeyBase64());
        assertTrue("id_ed25519".equals(m.originalFilename()));
    }

    @Test
    void encryptedKeyWithoutPassphrase_needsPassphrase(@TempDir Path tmp) throws Exception {
        Path key = tmp.resolve("id_rsa");
        Files.writeString(key, """
            -----BEGIN RSA PRIVATE KEY-----
            Proc-Type: 4,ENCRYPTED
            DEK-Info: AES-128-CBC,abcdef0123456789

            ciphertextbase64=
            -----END RSA PRIVATE KEY-----
            """);

        var result = KeyFileImporter.read(key, null);
        assertInstanceOf(KeyFileImporter.Result.NeedsPassphrase.class, result);
    }

    @Test
    void encryptedKeyWithPassphrase_returnsOk(@TempDir Path tmp) throws Exception {
        Path key = tmp.resolve("id_rsa");
        Files.writeString(key, """
            -----BEGIN RSA PRIVATE KEY-----
            Proc-Type: 4,ENCRYPTED
            DEK-Info: AES-128-CBC,abcdef0123456789

            ciphertextbase64=
            -----END RSA PRIVATE KEY-----
            """);

        var result = KeyFileImporter.read(key, "mysecret");
        assertInstanceOf(KeyFileImporter.Result.Ok.class, result);
    }

    @Test
    void notAKey_returnsWarning(@TempDir Path tmp) throws Exception {
        Path key = tmp.resolve("random.txt");
        Files.writeString(key, "this is not a private key");

        var result = KeyFileImporter.read(key, null);
        assertInstanceOf(KeyFileImporter.Result.Warning.class, result);
    }
}
