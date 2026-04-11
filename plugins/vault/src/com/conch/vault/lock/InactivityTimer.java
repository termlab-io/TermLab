package com.conch.vault.lock;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Auto-lock timer driven by user activity.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #start(int)} — arm the timer after unlock with the
 *       user-configured timeout (in minutes). Resets on every subsequent
 *       {@link #markActivity()}.</li>
 *   <li>{@link #markActivity()} — call whenever the user interacts with
 *       Conch. Cancels and reschedules the auto-lock task.</li>
 *   <li>{@link #stop()} — disarm (called by the LockManager on lock, or when
 *       the user explicitly locks via action).</li>
 *   <li>{@link #shutdown()} — release the backing executor. Called on plugin
 *       unload / application exit.</li>
 * </ol>
 *
 * <p>The backing executor is a single daemon thread so it doesn't prevent
 * JVM shutdown. All public methods are synchronized on {@code this}.
 *
 * <p>Passing {@code 0} or a negative value to {@link #start(int)} disables
 * auto-lock entirely (the vault stays unlocked until the user manually locks
 * it or quits).
 */
public final class InactivityTimer {

    private final LockManager lockManager;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pending;
    private int currentMinutes;

    public InactivityTimer(@NotNull LockManager lockManager) {
        this.lockManager = lockManager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ConchVaultInactivityTimer");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start(int minutes) {
        this.currentMinutes = minutes;
        markActivity();
    }

    public synchronized void markActivity() {
        if (currentMinutes <= 0) return;
        if (pending != null) pending.cancel(false);
        pending = scheduler.schedule(lockManager::lock, currentMinutes, TimeUnit.MINUTES);
    }

    public synchronized void stop() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        currentMinutes = 0;
    }

    /** Release the executor. After this call, the timer is dead. */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * For tests: schedule the auto-lock task with a sub-minute delay.
     * Not part of the plugin's public API — the normal path is
     * {@link #start(int)} with a whole-minute timeout.
     */
    public synchronized void scheduleForTest(long delay, @NotNull TimeUnit unit) {
        if (pending != null) pending.cancel(false);
        pending = scheduler.schedule(lockManager::lock, delay, unit);
    }

    /** For tests: true when an auto-lock is currently scheduled. */
    public synchronized boolean isArmed() {
        return pending != null && !pending.isDone();
    }
}
