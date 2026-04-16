package com.termlab.vault.lock;

import com.termlab.vault.crypto.SecureBytes;
import com.termlab.vault.crypto.VaultCorruptedException;
import com.termlab.vault.crypto.WrongPasswordException;
import com.termlab.vault.model.Vault;
import com.termlab.vault.persistence.VaultFile;
import com.termlab.vault.persistence.VaultPaths;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

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

    /**
     * Path supplier — called on every access so the vault location can be
     * changed at runtime via {@link com.termlab.vault.settings.TermLabVaultConfig}
     * without recreating the service or restarting the app.
     */
    private final Supplier<Path> vaultPathSupplier;

    private final List<VaultStateListener> listeners = new CopyOnWriteArrayList<>();

    private VaultState state = VaultState.LOCKED;
    private @Nullable Vault unlockedVault;

    // Credentials cached for save() so the user isn't reprompted on every
    // account edit. Both are zeroed on lock() so they don't linger.
    private @Nullable SecureBytes cachedPassword;
    private @Nullable SecureBytes cachedDeviceSecret;

    /**
     * No-arg constructor used by the IntelliJ application-service framework.
     * Resolves the vault path lazily from {@link VaultPaths#vaultFile()} on
     * each access, so path changes in Settings take effect on the next
     * unlock / save without a restart.
     */
    public LockManager() {
        this.vaultPathSupplier = VaultPaths::vaultFile;
    }

    /** Explicit path constructor — used by tests with {@code @TempDir}. */
    public LockManager(@NotNull Path vaultPath) {
        this.vaultPathSupplier = () -> vaultPath;
    }

    public @NotNull Path getVaultPath() {
        return vaultPathSupplier.get();
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
            unlockedVault = VaultFile.load(vaultPathSupplier.get(), password);
            // Cache a defensive copy so save() can re-encrypt without a reprompt.
            cachedPassword = SecureBytes.copyOf(password);
            cachedDeviceSecret = null;
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
            unlockedVault = VaultFile.load(vaultPathSupplier.get(), password, deviceSecret);
            cachedPassword = SecureBytes.copyOf(password);
            cachedDeviceSecret = SecureBytes.copyOf(deviceSecret);
            setState(VaultState.UNLOCKED);
        } catch (Exception e) {
            unlockedVault = null;
            setState(VaultState.LOCKED);
            throw e;
        }
    }

    /**
     * Create a brand new empty vault and unlock it using the provided
     * password.
     *
     * @throws IllegalStateException if the manager is not locked or a vault
     *                               file already exists at the configured path
     * @throws IOException           if writing the new vault file fails
     */
    public synchronized void createVault(@NotNull byte[] password) throws IOException {
        if (state != VaultState.LOCKED) {
            throw new IllegalStateException("cannot create vault when one is already unlocked");
        }
        Path path = vaultPathSupplier.get();
        if (VaultFile.exists(path)) {
            throw new IllegalStateException("vault file already exists at " + path);
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        VaultFile.save(path, new Vault(), password);
        try {
            unlock(password);
        } catch (WrongPasswordException | VaultCorruptedException impossible) {
            throw new IllegalStateException("freshly created vault failed to unlock", impossible);
        }
    }

    /**
     * Run a vault operation with the vault unlocked, auto-saving afterwards.
     * If the manager was locked on entry, it is re-locked in a finally block.
     */
    public synchronized <T> T withUnlocked(
        @NotNull byte[] password,
        @NotNull Function<Vault, T> operation
    ) throws IOException, WrongPasswordException, VaultCorruptedException {
        boolean wasLocked = isLocked();
        if (wasLocked) {
            unlock(password);
        }
        try {
            Vault vault = getVault();
            if (vault == null) {
                throw new IllegalStateException("vault is unexpectedly locked after unlock()");
            }
            T result = operation.apply(vault);
            save();
            return result;
        } finally {
            if (wasLocked) {
                lock();
            }
        }
    }

    /**
     * Persist the currently-unlocked vault using the cached credentials from
     * the most recent successful unlock.
     *
     * @throws IllegalStateException if the vault isn't unlocked
     * @throws IOException           if the file can't be written
     */
    public synchronized void save() throws IOException {
        if (state != VaultState.UNLOCKED || unlockedVault == null || cachedPassword == null) {
            throw new IllegalStateException("cannot save vault when not unlocked");
        }
        if (cachedDeviceSecret != null) {
            VaultFile.save(vaultPathSupplier.get(), unlockedVault, cachedPassword.bytes(), cachedDeviceSecret.bytes());
        } else {
            VaultFile.save(vaultPathSupplier.get(), unlockedVault, cachedPassword.bytes());
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
        if (cachedPassword != null) {
            cachedPassword.close();
            cachedPassword = null;
        }
        if (cachedDeviceSecret != null) {
            cachedDeviceSecret.close();
            cachedDeviceSecret = null;
        }
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
