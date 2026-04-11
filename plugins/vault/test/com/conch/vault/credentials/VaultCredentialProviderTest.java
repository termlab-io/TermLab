package com.conch.vault.credentials;

import com.conch.sdk.CredentialProvider;
import com.conch.vault.lock.LockManager;
import com.conch.vault.model.AuthMethod;
import com.conch.vault.model.Vault;
import com.conch.vault.model.VaultAccount;
import com.conch.vault.persistence.VaultFile;
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
    void displayName_isConchVault() {
        VaultCredentialProvider provider = new VaultCredentialProvider(
            new LockManager(Path.of("/nonexistent")));
        assertEquals("Conch Vault", provider.getDisplayName());
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
    void promptForCredential_isNullPlaceholder(@TempDir Path tmp) throws Exception {
        // Phase-4 picker not yet implemented — SDK contract allows null
        // ("user cancelled or no picker available").
        LockManager lm = unlockedLockManager(tmp);
        VaultCredentialProvider provider = new VaultCredentialProvider(lm);
        assertNull(provider.promptForCredential());
    }
}
