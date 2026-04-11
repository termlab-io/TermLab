package com.conch.vault.persistence;

import com.conch.vault.crypto.WrongPasswordException;
import com.conch.vault.model.AuthMethod;
import com.conch.vault.model.Vault;
import com.conch.vault.model.VaultAccount;
import com.conch.vault.model.VaultKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VaultFileTest {

    @Test
    void saveThenLoad_roundTripsEmptyVault(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        Vault original = new Vault();

        VaultFile.save(file, original, "pw".getBytes());

        Vault loaded = VaultFile.load(file, "pw".getBytes());
        assertEquals(0, loaded.accounts.size());
        assertEquals(Vault.VERSION, loaded.version);
    }

    @Test
    void saveThenLoad_roundTripsAccount(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        Vault original = new Vault();
        UUID accountId = UUID.randomUUID();
        original.accounts.add(new VaultAccount(
            accountId, "DB", "admin",
            new AuthMethod.Password("secret"),
            Instant.now(), Instant.now()));

        VaultFile.save(file, original, "pw".getBytes());
        Vault loaded = VaultFile.load(file, "pw".getBytes());

        assertEquals(1, loaded.accounts.size());
        assertEquals(accountId, loaded.accounts.get(0).id());
        assertEquals("DB", loaded.accounts.get(0).displayName());
    }

    @Test
    void load_wrongPassword_throws(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        VaultFile.save(file, new Vault(), "right".getBytes());
        assertThrows(WrongPasswordException.class,
            () -> VaultFile.load(file, "wrong".getBytes()));
    }

    @Test
    void save_atomicWrite_noPartialTmpFileSurvives(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        VaultFile.save(file, new Vault(), "pw".getBytes());
        assertFalse(Files.exists(tmp.resolve("vault.enc.tmp")));
        assertTrue(Files.exists(file));
    }

    @Test
    void save_createsMissingParentDirectory(@TempDir Path tmp) throws Exception {
        Path nested = tmp.resolve("a/b/c/vault.enc");
        VaultFile.save(nested, new Vault(), "pw".getBytes());
        assertTrue(Files.exists(nested));
    }

    @Test
    void exists_reportsCorrectly(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        assertFalse(VaultFile.exists(file));
        VaultFile.save(file, new Vault(), "pw".getBytes());
        assertTrue(VaultFile.exists(file));
    }

    @Test
    void deviceBound_roundTrip(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        byte[] deviceSecret = new byte[32];
        for (int i = 0; i < 32; i++) deviceSecret[i] = (byte) i;

        VaultFile.save(file, new Vault(), "pw".getBytes(), deviceSecret);
        Vault loaded = VaultFile.load(file, "pw".getBytes(), deviceSecret);
        assertNotNull(loaded);
    }

    @Test
    void vaultKeys_roundTrip(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        Vault original = new Vault();
        UUID keyId = UUID.randomUUID();
        original.keys.add(new VaultKey(
            keyId,
            "Work laptop",
            "ed25519",
            "SHA256:dGVzdGhhc2h0ZXN0aGFzaHRlc3RoYXNodGVzdGhh",
            "dustin@laptop",
            "/home/dustin/.ssh/conch_vault/id_ed25519_abc",
            "/home/dustin/.ssh/conch_vault/id_ed25519_abc.pub",
            Instant.parse("2026-04-11T10:00:00Z")));

        VaultFile.save(file, original, "pw".getBytes());
        Vault loaded = VaultFile.load(file, "pw".getBytes());

        assertEquals(1, loaded.keys.size());
        VaultKey loadedKey = loaded.keys.get(0);
        assertEquals(keyId, loadedKey.id());
        assertEquals("Work laptop", loadedKey.name());
        assertEquals("ed25519", loadedKey.algorithm());
        assertEquals("dustin@laptop", loadedKey.comment());
        assertTrue(loadedKey.publicPath().endsWith(".pub"));
    }
}
