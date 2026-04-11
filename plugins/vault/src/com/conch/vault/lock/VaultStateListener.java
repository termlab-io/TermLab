package com.conch.vault.lock;

/**
 * Observer for {@link VaultState} transitions. Registered with a
 * {@link LockManager} and called on every state change.
 *
 * <p>Called on whichever thread triggered the transition (unlock thread,
 * inactivity-timer thread, or EDT). Implementations that touch Swing must
 * marshal to the EDT themselves.
 */
@FunctionalInterface
public interface VaultStateListener {
    void onStateChanged(VaultState newState);
}
