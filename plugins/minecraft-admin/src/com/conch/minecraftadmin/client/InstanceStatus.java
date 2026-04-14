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
    @NotNull Duration uptime
) {
    public static @NotNull InstanceStatus unknown() {
        return new InstanceStatus(McServerStatus.UNKNOWN, Double.NaN, 0L, 0L, Duration.ZERO);
    }
}
