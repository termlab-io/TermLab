package com.conch.vault.lock;

import com.conch.vault.crypto.WrongPasswordException;
import com.conch.vault.model.AuthMethod;
import com.conch.vault.model.Vault;
import com.conch.vault.model.VaultAccount;
import com.conch.vault.persistence.VaultFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
