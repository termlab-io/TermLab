package com.conch.minecraftadmin.model;

/**
 * Life-cycle state of an AMP-managed Minecraft instance. Mapped from
 * AMP's numeric state code in {@code AmpClient} and consumed by the UI
 * to enable/disable lifecycle buttons and by {@code CrashDetector} to
 * decide whether to fire the crash-balloon notification.
 */
public enum McServerStatus {
    /** Instance is accepting connections. */
    RUNNING,
    /** Instance is starting up (pre-ready). */
    STARTING,
    /** Instance has been asked to stop but has not yet stopped. */
    STOPPING,
    /** Instance is off (user-initiated or clean shutdown). */
    STOPPED,
    /** AMP reports the instance exited abnormally. */
    CRASHED,
    /** State is unobservable right now (AMP unreachable, auth failed, etc.). */
    UNKNOWN,
    /** Precondition missing — vault is locked and no credentials can be resolved. */
    LOCKED
}
