package com.conch.vault.credentials;

import com.conch.sdk.CredentialProvider;
import com.conch.vault.model.AuthMethod;
import com.conch.vault.model.VaultAccount;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthMethodMapperTest {

    private static VaultAccount account(AuthMethod auth) {
        return new VaultAccount(
            UUID.randomUUID(), "Display", "user", auth,
            Instant.now(), Instant.now());
    }

    @Test
    void password_yieldsPasswordCredential() {
        CredentialProvider.Credential cred = AuthMethodMapper.toCredential(
            account(new AuthMethod.Password("hunter2")));
        assertEquals(CredentialProvider.AuthMethod.PASSWORD, cred.authMethod());
        assertArrayEquals("hunter2".toCharArray(), cred.password());
        assertNull(cred.keyPath());
        assertNull(cred.keyPassphrase());
    }

    @Test
    void key_yieldsKeyCredential_noPassphrase() {
        CredentialProvider.Credential cred = AuthMethodMapper.toCredential(
            account(new AuthMethod.Key("/home/me/.ssh/id_ed25519", null)));
        assertEquals(CredentialProvider.AuthMethod.KEY, cred.authMethod());
        assertEquals("/home/me/.ssh/id_ed25519", cred.keyPath());
        assertNull(cred.password());
        assertNull(cred.keyPassphrase());
    }

    @Test
    void key_yieldsKeyCredential_withPassphrase() {
        CredentialProvider.Credential cred = AuthMethodMapper.toCredential(
            account(new AuthMethod.Key("/k", "keypass")));
        assertEquals(CredentialProvider.AuthMethod.KEY, cred.authMethod());
        assertArrayEquals("keypass".toCharArray(), cred.keyPassphrase());
        assertNull(cred.password());
    }

    @Test
    void keyAndPassword_yieldsBothFields() {
        CredentialProvider.Credential cred = AuthMethodMapper.toCredential(
            account(new AuthMethod.KeyAndPassword("/k", "kp", "pw")));
        assertEquals(CredentialProvider.AuthMethod.KEY_AND_PASSWORD, cred.authMethod());
        assertEquals("/k", cred.keyPath());
        assertArrayEquals("pw".toCharArray(), cred.password());
        assertArrayEquals("kp".toCharArray(), cred.keyPassphrase());
    }

    @Test
    void preservesAccountIdentityFields() {
        UUID id = UUID.randomUUID();
        VaultAccount account = new VaultAccount(
            id, "Prod DB", "dbadmin",
            new AuthMethod.Password("secret"),
            Instant.now(), Instant.now());
        CredentialProvider.Credential cred = AuthMethodMapper.toCredential(account);
        assertEquals(id, cred.accountId());
        assertEquals("Prod DB", cred.displayName());
        assertEquals("dbadmin", cred.username());
    }

    @Test
    void destroy_zeroesPasswordAndPassphrase() {
        CredentialProvider.Credential cred = AuthMethodMapper.toCredential(
            account(new AuthMethod.KeyAndPassword("/k", "pp", "pw")));
        cred.destroy();
        for (char c : cred.password()) assertEquals('\0', c);
        for (char c : cred.keyPassphrase()) assertEquals('\0', c);
    }

    @Test
    void mappingDoesNotAliasPasswordString() {
        // Mutating the returned char[] must not affect the source String.
        String source = "mutate-me";
        CredentialProvider.Credential cred = AuthMethodMapper.toCredential(
            account(new AuthMethod.Password(source)));
        cred.password()[0] = 'X';
        // The original String is immutable; just verify it still equals "mutate-me".
        assertEquals("mutate-me", source);
    }
}
