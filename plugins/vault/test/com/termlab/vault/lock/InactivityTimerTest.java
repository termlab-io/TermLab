package com.termlab.vault.lock;

import com.termlab.vault.model.Vault;
import com.termlab.vault.persistence.VaultFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InactivityTimerTest {

    @Test
    void zeroMinutes_disablesAutoLock(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        VaultFile.save(file, new Vault(), "pw".getBytes());
        LockManager lm = new LockManager(file);
        lm.unlock("pw".getBytes());

        InactivityTimer timer = new InactivityTimer(lm);
        try {
            timer.start(0);
            assertFalse(timer.isArmed(), "timer should not arm when minutes <= 0");
        } finally {
            timer.shutdown();
        }
    }

    @Test
    void scheduledTask_fires_andLocksVault(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        VaultFile.save(file, new Vault(), "pw".getBytes());
        LockManager lm = new LockManager(file);
        lm.unlock("pw".getBytes());
        assertFalse(lm.isLocked());

        InactivityTimer timer = new InactivityTimer(lm);
        try {
            timer.scheduleForTest(100, TimeUnit.MILLISECONDS);
            // Wait up to 2s for the task to fire.
            long deadline = System.currentTimeMillis() + 2000;
            while (!lm.isLocked() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(lm.isLocked(), "vault should have auto-locked by now");
        } finally {
            timer.shutdown();
        }
    }

    @Test
    void markActivity_resetsTimer(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        VaultFile.save(file, new Vault(), "pw".getBytes());
        LockManager lm = new LockManager(file);
        lm.unlock("pw".getBytes());

        InactivityTimer timer = new InactivityTimer(lm);
        try {
            timer.scheduleForTest(500, TimeUnit.MILLISECONDS);
            // Repeatedly reset the timer so auto-lock never fires.
            for (int i = 0; i < 5; i++) {
                Thread.sleep(100);
                timer.scheduleForTest(500, TimeUnit.MILLISECONDS);
            }
            assertFalse(lm.isLocked(), "activity should have kept the vault unlocked");
        } finally {
            timer.shutdown();
        }
    }

    @Test
    void stop_disarms(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("vault.enc");
        VaultFile.save(file, new Vault(), "pw".getBytes());
        LockManager lm = new LockManager(file);
        lm.unlock("pw".getBytes());

        InactivityTimer timer = new InactivityTimer(lm);
        try {
            timer.scheduleForTest(5, TimeUnit.SECONDS);
            assertTrue(timer.isArmed());
            timer.stop();
            assertFalse(timer.isArmed());
        } finally {
            timer.shutdown();
        }
    }
}
