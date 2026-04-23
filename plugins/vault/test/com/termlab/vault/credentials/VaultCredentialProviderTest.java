package com.termlab.vault.credentials;

import com.termlab.sdk.CredentialProvider;
import com.termlab.vault.lock.LockManager;
import com.termlab.vault.model.AuthMethod;
import com.termlab.vault.model.Vault;
import com.termlab.vault.model.VaultAccount;
import com.termlab.vault.persistence.VaultFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VaultCredentialProviderTest {

    private static LockManager unlockedLockManager(Path tmp, VaultAccount... accounts) throws Exception {
        Path file = tmp.resolve("vault.enc");
        Vault vault = new Vault();
        for (VaultAccount a : accounts) vault.accounts.add(a);
        VaultFile.save(file, vault, "pw".getBytes());
        LockManager lm = new LockManager(file);
        lm.unlock("pw".getBytes());
        return lm;
    }

    @Test
    void displayName_isTermLabVault() {
        VaultCredentialProvider provider = new VaultCredentialProvider(
            new LockManager(Path.of("/nonexistent")));
        assertEquals("Credential Vault", provider.getDisplayName());
    }

    @Test
    void isAvailable_reflectsLockManagerState(@TempDir Path tmp) throws Exception {
        LockManager lm = unlockedLockManager(tmp);
        VaultCredentialProvider provider = new VaultCredentialProvider(lm);
        assertTrue(provider.isAvailable());

        lm.lock();
        assertFalse(provider.isAvailable());
    }

    @Test
    void getCredential_returnsNullWhenLocked(@TempDir Path tmp) throws Exception {
        LockManager lm = unlockedLockManager(tmp,
            new VaultAccount(UUID.randomUUID(), "DB", "admin",
                new AuthMethod.Password("secret"),
                Instant.now(), Instant.now()));
        lm.lock();

        VaultCredentialProvider provider = new VaultCredentialProvider(lm);
        assertNull(provider.getCredential(UUID.randomUUID()));
    }

    @Test
    void getCredential_returnsNullWhenAccountMissing(@TempDir Path tmp) throws Exception {
        LockManager lm = unlockedLockManager(tmp);
        VaultCredentialProvider provider = new VaultCredentialProvider(lm);
        assertNull(provider.getCredential(UUID.randomUUID()));
    }

    @Test
    void getCredential_returnsMappedCredentialWhenFound(@TempDir Path tmp) throws Exception {
        UUID id = UUID.randomUUID();
        LockManager lm = unlockedLockManager(tmp,
            new VaultAccount(id, "DB", "admin",
                new AuthMethod.Password("secret"),
                Instant.now(), Instant.now()));
        VaultCredentialProvider provider = new VaultCredentialProvider(lm);

        CredentialProvider.Credential cred = provider.getCredential(id);
        assertNotNull(cred);
        assertEquals(id, cred.accountId());
        assertEquals("DB", cred.displayName());
        assertEquals("admin", cred.username());
        assertEquals(CredentialProvider.AuthMethod.PASSWORD, cred.authMethod());
        assertArrayEquals("secret".toCharArray(), cred.password());
    }

    @Test
    void getCredential_filtersByUuid_whenMultipleAccounts(@TempDir Path tmp) throws Exception {
        UUID targetId = UUID.randomUUID();
        LockManager lm = unlockedLockManager(tmp,
            new VaultAccount(UUID.randomUUID(), "Other", "u1",
                new AuthMethod.Password("pw1"),
                Instant.now(), Instant.now()),
            new VaultAccount(targetId, "Target", "u2",
                new AuthMethod.Password("pw2"),
                Instant.now(), Instant.now()),
            new VaultAccount(UUID.randomUUID(), "Third", "u3",
                new AuthMethod.Password("pw3"),
                Instant.now(), Instant.now()));

        VaultCredentialProvider provider = new VaultCredentialProvider(lm);
        CredentialProvider.Credential cred = provider.getCredential(targetId);
        assertNotNull(cred);
        assertEquals("Target", cred.displayName());
        assertArrayEquals("pw2".toCharArray(), cred.password());
    }

    @Test
    void listCredentials_returnsAccountsAndKeys(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        Vault vault = new Vault();
        vault.accounts.add(new VaultAccount(
            UUID.randomUUID(), "DB", "admin",
            new AuthMethod.Password("secret"),
            Instant.now(), Instant.now()));
        vault.accounts.add(new VaultAccount(
            UUID.randomUUID(), "PVE", "root@pam!termlab",
            new AuthMethod.ApiToken("pve-secret"),
            Instant.now(), Instant.now()));
        vault.accounts.add(new VaultAccount(
            UUID.randomUUID(), "Recovery Codes", "",
            new AuthMethod.SecureNote("one\ntwo"),
            Instant.now(), Instant.now()));
        vault.keys.add(new com.termlab.vault.model.VaultKey(
            UUID.randomUUID(),
            "Work Laptop",
            "ed25519",
            "SHA256:abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ",
            "dustin@laptop",
            tmp.resolve("id_ed25519").toString(),
            tmp.resolve("id_ed25519.pub").toString(),
            Instant.now()));
        VaultFile.save(file, vault, "pw".getBytes());

        LockManager lm = new LockManager(file);
        lm.unlock("pw".getBytes());

        VaultCredentialProvider provider = new VaultCredentialProvider(lm);
        var descriptors = provider.listCredentials();
        assertEquals(4, descriptors.size());

        var kinds = descriptors.stream()
            .map(CredentialProvider.CredentialDescriptor::kind)
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(kinds.contains(CredentialProvider.Kind.ACCOUNT_PASSWORD));
        assertTrue(kinds.contains(CredentialProvider.Kind.API_TOKEN));
        assertTrue(kinds.contains(CredentialProvider.Kind.SECURE_NOTE));
        assertTrue(kinds.contains(CredentialProvider.Kind.SSH_KEY));
    }

    @Test
    void listCredentials_whenLocked_isEmpty(@TempDir Path tmp) throws Exception {
        LockManager lm = unlockedLockManager(tmp);
        lm.lock();
        VaultCredentialProvider provider = new VaultCredentialProvider(lm);
        assertTrue(provider.listCredentials().isEmpty());
    }

    @Test
    void getCredential_returnsKeyCredentialForVaultKey(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        Vault vault = new Vault();
        UUID keyId = UUID.randomUUID();
        vault.keys.add(new com.termlab.vault.model.VaultKey(
            keyId,
            "GitHub",
            "ed25519",
            "SHA256:xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
            "ci@github",
            "/tmp/key",
            "/tmp/key.pub",
            Instant.now()));
        VaultFile.save(file, vault, "pw".getBytes());

        LockManager lm = new LockManager(file);
        lm.unlock("pw".getBytes());

        VaultCredentialProvider provider = new VaultCredentialProvider(lm);
        CredentialProvider.Credential cred = provider.getCredential(keyId);
        assertNotNull(cred);
        assertEquals("GitHub", cred.displayName());
        assertNull(cred.username(), "SSH keys have no embedded username");
        assertEquals(CredentialProvider.AuthMethod.KEY, cred.authMethod());
        assertEquals("/tmp/key", cred.keyPath());
        assertNull(cred.password());
    }

    // promptForCredential() opens an AccountPickerDialog which requires a live
    // IntelliJ application context, so there's no unit test for it here — the
    // flow is covered by the Phase 4 manual smoke test. The unit-testable
    // surface is AuthMethodMapper (covered separately) and the getCredential /
    // isAvailable / getDisplayName / listCredentials methods above.
}
