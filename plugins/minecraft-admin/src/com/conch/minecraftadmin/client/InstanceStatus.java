package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * Snapshot of one AMP instance's runtime state. Fields beyond {@code status}
 * are populated best-effort; if AMP doesn't include them, they default to
 * NaN / 0 / ZERO so the caller can still render a partial snapshot.
 */
public record InstanceStatus(
    @NotNull McServerStatus status,
    double cpuPercent,
    long ramUsedMb,
    long ramMaxMb,
    @NotNull Duration uptime,
    int playersOnline,    // from AMP Metrics."Active Users".RawValue, 0 if missing
    int playersMax,       // from AMP Metrics."Active Users".MaxValue, 0 if missing
    double tps            // from AMP Metrics.TPS.RawValue, NaN if missing
) {
    public static @NotNull InstanceStatus unknown() {
        return new InstanceStatus(
            McServerStatus.UNKNOWN, Double.NaN, 0L, 0L, Duration.ZERO,
            0, 0, Double.NaN);
    }
}
