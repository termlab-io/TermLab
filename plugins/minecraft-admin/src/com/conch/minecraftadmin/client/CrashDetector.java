package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;

/**
 * Stateful transition tracker that decides when the "server crashed"
 * balloon should fire. Per-profile instance; not thread-safe — owned by
 * exactly one {@link ServerPoller}.
 *
 * <p>Rules:
 * <ul>
 *   <li>Fires once when the previous tick saw {@link McServerStatus#RUNNING}
 *       and the current tick sees {@link McServerStatus#CRASHED}.</li>
 *   <li>Fires once when the previous tick saw {@link McServerStatus#RUNNING}
 *       and the current tick sees {@link McServerStatus#STOPPED}, <em>unless</em>
 *       the user called {@link #recordUserStop} within {@link #USER_STOP_GRACE}
 *       of the transition.</li>
 *   <li>Never fires on {@link McServerStatus#UNKNOWN}. Network or AMP hiccups
 *       surface as the "AMP offline" pill instead.</li>
 *   <li>Dedupes within one transition: a balloon fires at most once per
 *       RUNNING → terminal sequence.</li>
 * </ul>
 */
public final class CrashDetector {

    public static final Duration USER_STOP_GRACE = Duration.ofSeconds(10);

    private McServerStatus previous = McServerStatus.UNKNOWN;
    private boolean firedForCurrentTransition = false;
    private Instant lastUserStop = Instant.EPOCH;

    /**
     * Observe the latest status and return {@code true} if the balloon
     * should fire on this tick.
     */
    public boolean observe(@NotNull McServerStatus status, @NotNull Instant sampledAt) {
        McServerStatus prior = this.previous;
        this.previous = status;

        if (status == McServerStatus.UNKNOWN) {
            // Don't reset the transition flag — UNKNOWN is "we can't tell".
            return false;
        }

        if (status == McServerStatus.RUNNING || status == McServerStatus.STARTING) {
            // Entering a healthy state resets the transition — next crash fires again.
            firedForCurrentTransition = false;
            return false;
        }

        if (prior != McServerStatus.RUNNING) return false;

        if (status == McServerStatus.CRASHED) {
            if (firedForCurrentTransition) return false;
            firedForCurrentTransition = true;
            return true;
        }

        if (status == McServerStatus.STOPPED) {
            if (firedForCurrentTransition) return false;
            Duration sinceUserStop = Duration.between(lastUserStop, sampledAt);
            if (!sinceUserStop.isNegative() && sinceUserStop.compareTo(USER_STOP_GRACE) <= 0) {
                // Graceful, user-requested stop — treat as intentional.
                firedForCurrentTransition = true;
                return false;
            }
            firedForCurrentTransition = true;
            return true;
        }

        return false;
    }

    /**
     * Record that the user asked to stop the server at this moment. The
     * detector uses this to suppress the next STOPPED transition for
     * {@link #USER_STOP_GRACE}.
     */
    public void recordUserStop(@NotNull Instant at) {
        this.lastUserStop = at;
    }
}
