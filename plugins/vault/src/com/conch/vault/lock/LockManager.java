package com.conch.vault.lock;

import com.conch.vault.crypto.VaultCorruptedException;
import com.conch.vault.crypto.WrongPasswordException;
import com.conch.vault.model.Vault;
import com.conch.vault.persistence.VaultFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process owner of the unlocked vault.
 *
 * <p>This is the single source of truth for "is the vault currently unlocked,
 * and if so, what are its contents". It:
 * <ul>
 *   <li>Holds a {@link Vault} reference when unlocked, null when locked.</li>
 *   <li>Triggers AES-GCM decryption on {@link #unlock(byte[])}.</li>
 *   <li>Zeroes the reference and notifies listeners on {@link #lock()}.</li>
 *   <li>Broadcasts state transitions to registered {@link VaultStateListener}s
 *       (status-bar widget, palette contributor, shutdown hook, etc.).</li>
 * </ul>
 *
 * <p>Thread-safety: state-transition methods are synchronized on {@code this}.
 * Listeners are invoked while the lock is held, so they must be fast and
 * must not re-enter the LockManager. Listener registration uses a
 * {@link CopyOnWriteArrayList} so it can be modified concurrently.
 *
 * <p>Device-secret binding is optional at the LockManager level: call
 * {@link #unlock(byte[])} for password-only or
 * {@link #unlock(byte[], byte[])} for device-bound. The vault plugin's
 * application service wiring chooses which to use based on whether a
 * DeviceSecret is available (it always is on supported platforms, because
 * IntelliJ's PasswordSafe falls back to an encrypted file if no OS keychain
 * is present).
 */
public final class LockManager {

    private final Path vaultPath;
    private final List<VaultStateListener> listeners = new CopyOnWriteArrayList<>();

    private VaultState state = VaultState.LOCKED;
    private @Nullable Vault unlockedVault;

    public LockManager(@NotNull Path vaultPath) {
        this.vaultPath = vaultPath;
    }

    public @NotNull Path getVaultPath() {
        return vaultPath;
    }

    public synchronized boolean isLocked() {
        return state == VaultState.LOCKED;
    }

    public synchronized @NotNull VaultState getState() {
        return state;
    }

    /**
     * @return the unlocked vault, or {@code null} if the vault is not in
     *         state {@link VaultState#UNLOCKED}.
     */
    public synchronized @Nullable Vault getVault() {
        return state == VaultState.UNLOCKED ? unlockedVault : null;
    }

    /**
     * Decrypt the vault file with the given password.
     *
     * @throws WrongPasswordException  if the password is wrong
     * @throws VaultCorruptedException if the file is structurally broken
     * @throws IOException             if the file can't be read
     */
    public synchronized void unlock(@NotNull byte[] password)
        throws IOException, WrongPasswordException, VaultCorruptedException {
        if (state == VaultState.UNLOCKED) return;
        setState(VaultState.UNLOCKING);
        try {
            unlockedVault = VaultFile.load(vaultPath, password);
            setState(VaultState.UNLOCKED);
        } catch (Exception e) {
            unlockedVault = null;
            setState(VaultState.LOCKED);
            throw e;
        }
    }

    /** Device-bound variant. */
    public synchronized void unlock(@NotNull byte[] password, @NotNull byte[] deviceSecret)
        throws IOException, WrongPasswordException, VaultCorruptedException {
        if (state == VaultState.UNLOCKED) return;
        setState(VaultState.UNLOCKING);
        try {
            unlockedVault = VaultFile.load(vaultPath, password, deviceSecret);
            setState(VaultState.UNLOCKED);
        } catch (Exception e) {
            unlockedVault = null;
            setState(VaultState.LOCKED);
            throw e;
        }
    }

    /**
     * Seal the vault: drop the in-memory reference and notify listeners.
     * Safe to call when already locked (no-op).
     *
     * <p>Note: this does NOT zero the underlying account data — the Vault
     * model uses immutable records, and the GC will reclaim the memory once
     * no other references remain. True defense-in-depth zeroing of the
     * String-backed password fields is tracked as a post-v1 hardening pass
     * in the vault plan.
     */
    public synchronized void lock() {
        if (state == VaultState.LOCKED) return;
        setState(VaultState.SEALING);
        unlockedVault = null;
        setState(VaultState.LOCKED);
    }

    public void addListener(@NotNull VaultStateListener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull VaultStateListener listener) {
        listeners.remove(listener);
    }

    private void setState(@NotNull VaultState newState) {
        this.state = newState;
        for (VaultStateListener l : listeners) {
            try {
                l.onStateChanged(newState);
            } catch (Exception ignored) {
                // A buggy listener must not prevent the state transition.
            }
        }
    }
}
