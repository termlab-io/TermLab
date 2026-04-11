package com.conch.ssh.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SshResolvedCredentialTest {

    @Test
    void passwordFactory_populatesFields() {
        char[] pw = "secret".toCharArray();
        SshResolvedCredential cred = SshResolvedCredential.password("root", pw);
        assertEquals("root", cred.username());
        assertEquals(SshResolvedCredential.Mode.PASSWORD, cred.mode());
        assertSame(pw, cred.password());
        assertNull(cred.keyPath());
        assertNull(cred.keyPassphrase());
    }

    @Test
    void keyFactory_populatesFields() {
        Path keyPath = Path.of("/tmp/id_ed25519");
        SshResolvedCredential cred = SshResolvedCredential.key("root", keyPath, null);
        assertEquals(SshResolvedCredential.Mode.KEY, cred.mode());
        assertEquals(keyPath, cred.keyPath());
        assertNull(cred.password());
        assertNull(cred.keyPassphrase());
    }

    @Test
    void keyAndPasswordFactory_populatesFields() {
        char[] pw = "server-pw".toCharArray();
        char[] pp = "key-pp".toCharArray();
        Path keyPath = Path.of("/tmp/id_ed25519");

        SshResolvedCredential cred = SshResolvedCredential.keyAndPassword("root", keyPath, pp, pw);
        assertEquals(SshResolvedCredential.Mode.KEY_AND_PASSWORD, cred.mode());
        assertEquals(keyPath, cred.keyPath());
        assertSame(pw, cred.password());
        assertSame(pp, cred.keyPassphrase());
    }

    @Test
    void close_zeroesPasswordAndPassphrase() {
        char[] pw = "secret".toCharArray();
        char[] pp = "keypass".toCharArray();
        SshResolvedCredential cred = SshResolvedCredential.keyAndPassword(
            "root", Path.of("/tmp/key"), pp, pw);
        cred.close();

        for (char c : pw) assertEquals('\0', c, "password not zeroed");
        for (char c : pp) assertEquals('\0', c, "passphrase not zeroed");
    }

    @Test
    void close_handlesNullFields() {
        SshResolvedCredential cred = SshResolvedCredential.key(
            "root", Path.of("/tmp/key"), null);
        assertDoesNotThrow(cred::close);
    }

    @Test
    void doubleClose_isSafe() {
        SshResolvedCredential cred = SshResolvedCredential.password(
            "root", "pw".toCharArray());
        cred.close();
        assertDoesNotThrow(cred::close);
    }

    @Test
    void readAfterClose_throws() {
        SshResolvedCredential cred = SshResolvedCredential.password(
            "root", "pw".toCharArray());
        cred.close();
        assertThrows(IllegalStateException.class, cred::username);
        assertThrows(IllegalStateException.class, cred::mode);
        assertThrows(IllegalStateException.class, cred::password);
        assertThrows(IllegalStateException.class, cred::keyPath);
        assertThrows(IllegalStateException.class, cred::keyPassphrase);
    }
}
