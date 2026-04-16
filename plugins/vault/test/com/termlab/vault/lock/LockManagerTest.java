package com.termlab.vault.lock;

import com.termlab.vault.crypto.WrongPasswordException;
import com.termlab.vault.model.AuthMethod;
import com.termlab.vault.model.Vault;
import com.termlab.vault.model.VaultAccount;
import com.termlab.vault.persistence.VaultFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LockManagerTest {

    @Test
    void unlock_thenLock_emitsStateChanges(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        VaultFile.save(file, new Vault(), "pw".getBytes());

        LockManager lm = new LockManager(file);
        List<VaultState> states = new ArrayList<>();
        lm.addListener(states::add);

        assertTrue(lm.isLocked());
        assertNull(lm.getVault());

        lm.unlock("pw".getBytes());
        assertFalse(lm.isLocked());
        assertNotNull(lm.getVault());
        assertEquals(VaultState.UNLOCKED, lm.getState());

        lm.lock();
        assertTrue(lm.isLocked());
        assertNull(lm.getVault());

        assertEquals(
            List.of(VaultState.UNLOCKING, VaultState.UNLOCKED, VaultState.SEALING, VaultState.LOCKED),
            states
        );
    }

    @Test
    void unlock_wrongPassword_staysLockedAndEmitsLockedState(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        VaultFile.save(file, new Vault(), "right".getBytes());

        LockManager lm = new LockManager(file);
        List<VaultState> states = new ArrayList<>();
        lm.addListener(states::add);

        assertThrows(WrongPasswordException.class, () -> lm.unlock("wrong".getBytes()));

        assertTrue(lm.isLocked());
        assertNull(lm.getVault());
        assertEquals(List.of(VaultState.UNLOCKING, VaultState.LOCKED), states);
    }

    @Test
    void unlock_whenAlreadyUnlocked_isNoOp(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        VaultFile.save(file, new Vault(), "pw".getBytes());

        LockManager lm = new LockManager(file);
        lm.unlock("pw".getBytes());

        List<VaultState> states = new ArrayList<>();
        lm.addListener(states::add);

        lm.unlock("pw".getBytes());
        assertTrue(states.isEmpty(), "no state transitions on redundant unlock");
    }

    @Test
    void lock_whenAlreadyLocked_isNoOp(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        VaultFile.save(file, new Vault(), "pw".getBytes());

        LockManager lm = new LockManager(file);
        List<VaultState> states = new ArrayList<>();
        lm.addListener(states::add);

        lm.lock();
        assertTrue(states.isEmpty());
    }

    @Test
    void unlock_loadsAccounts(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        Vault v = new Vault();
        v.accounts.add(new VaultAccount(
            UUID.randomUUID(), "DB", "admin",
            new AuthMethod.Password("secret"),
            Instant.now(), Instant.now()));
        VaultFile.save(file, v, "pw".getBytes());

        LockManager lm = new LockManager(file);
        lm.unlock("pw".getBytes());

        Vault loaded = lm.getVault();
        assertNotNull(loaded);
        assertEquals(1, loaded.accounts.size());
        assertEquals("DB", loaded.accounts.get(0).displayName());
    }

    @Test
    void unlock_deviceBound_roundTrip(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        byte[] deviceSecret = new byte[32];
        for (int i = 0; i < 32; i++) deviceSecret[i] = (byte) i;
        VaultFile.save(file, new Vault(), "pw".getBytes(), deviceSecret);

        LockManager lm = new LockManager(file);
        lm.unlock("pw".getBytes(), deviceSecret);
        assertNotNull(lm.getVault());
    }

    @Test
    void listener_exception_doesNotPreventStateTransition(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        VaultFile.save(file, new Vault(), "pw".getBytes());

        LockManager lm = new LockManager(file);
        lm.addListener(state -> { throw new RuntimeException("buggy listener"); });

        assertDoesNotThrow(() -> lm.unlock("pw".getBytes()));
        assertFalse(lm.isLocked());
    }

    @Test
    void save_persistsChangesUsingCachedPassword(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        VaultFile.save(file, new Vault(), "pw".getBytes());

        LockManager lm = new LockManager(file);
        lm.unlock("pw".getBytes());

        // Mutate the unlocked vault and save without reprompting.
        lm.getVault().accounts.add(new VaultAccount(
            UUID.randomUUID(), "added", "u",
            new AuthMethod.Password("secret"),
            Instant.now(), Instant.now()));
        lm.save();

        // Lock, then unlock again and verify the addition persisted.
        lm.lock();
        lm.unlock("pw".getBytes());
        assertEquals(1, lm.getVault().accounts.size());
        assertEquals("added", lm.getVault().accounts.get(0).displayName());
    }

    @Test
    void save_deviceBound_persistsChanges(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        byte[] deviceSecret = new byte[32];
        for (int i = 0; i < 32; i++) deviceSecret[i] = (byte) (i * 3);
        VaultFile.save(file, new Vault(), "pw".getBytes(), deviceSecret);

        LockManager lm = new LockManager(file);
        lm.unlock("pw".getBytes(), deviceSecret);
        lm.getVault().accounts.add(new VaultAccount(
            UUID.randomUUID(), "dbc", "u",
            new AuthMethod.Password("s"),
            Instant.now(), Instant.now()));
        lm.save();

        lm.lock();
        lm.unlock("pw".getBytes(), deviceSecret);
        assertEquals(1, lm.getVault().accounts.size());
    }

    @Test
    void save_whenLocked_throws(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        VaultFile.save(file, new Vault(), "pw".getBytes());
        LockManager lm = new LockManager(file);
        assertThrows(IllegalStateException.class, lm::save);
    }

    @Test
    void createVault_writesFileAndUnlocks(@TempDir Path tmp) throws Exception {
        Path vaultPath = tmp.resolve("vault.enc");
        LockManager lm = new LockManager(vaultPath);
        byte[] password = "hunter22hunter22".getBytes();

        try {
            lm.createVault(password);
            assertFalse(lm.isLocked());
            assertTrue(VaultFile.exists(vaultPath));
            Vault vault = lm.getVault();
            assertNotNull(vault);
            assertTrue(vault.accounts.isEmpty());
            assertTrue(vault.keys.isEmpty());
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    @Test
    void createVault_failsIfFileAlreadyExists(@TempDir Path tmp) throws Exception {
        Path vaultPath = tmp.resolve("vault.enc");
        LockManager lm1 = new LockManager(vaultPath);
        byte[] password = "hunter22hunter22".getBytes();
        try {
            lm1.createVault(password);
            LockManager lm2 = new LockManager(vaultPath);
            assertThrows(IllegalStateException.class, () -> lm2.createVault(password));
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    @Test
    void withUnlocked_runsOperationAndAutoLocks(@TempDir Path tmp) throws Exception {
        Path vaultPath = tmp.resolve("vault.enc");
        LockManager lm = new LockManager(vaultPath);
        byte[] password = "hunter22hunter22".getBytes();

        try {
            lm.createVault(password);
            lm.lock();
            assertTrue(lm.isLocked());

            String result = lm.withUnlocked(password, vault -> {
                assertNotNull(vault);
                return "ok-" + vault.accounts.size();
            });

            assertEquals("ok-0", result);
            assertTrue(lm.isLocked(), "withUnlocked should auto-lock when it was locked on entry");
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    @Test
    void withUnlocked_doesNotLockIfAlreadyUnlocked(@TempDir Path tmp) throws Exception {
        Path vaultPath = tmp.resolve("vault.enc");
        LockManager lm = new LockManager(vaultPath);
        byte[] password = "hunter22hunter22".getBytes();

        try {
            lm.createVault(password);
            assertFalse(lm.isLocked());

            lm.withUnlocked(password, vault -> null);

            assertFalse(lm.isLocked(), "withUnlocked must not lock when already unlocked on entry");
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    @Test
    void removeListener_stopsNotifications(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        VaultFile.save(file, new Vault(), "pw".getBytes());

        LockManager lm = new LockManager(file);
        List<VaultState> states = new ArrayList<>();
        VaultStateListener listener = states::add;
        lm.addListener(listener);
        lm.removeListener(listener);

        lm.unlock("pw".getBytes());
        assertTrue(states.isEmpty());
    }
}
