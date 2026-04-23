package com.termlab.sysinfo.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;

public record SystemSnapshot(
    @NotNull Instant timestamp,
    @NotNull OsKind osKind,
    @NotNull String hostname,
    @NotNull String kernel,
    @NotNull String architecture,
    long sampleMillis,
    @Nullable Double cpuUsagePercent,
    @NotNull String loadAverage,
    @NotNull String uptime,
    long memoryTotalKb,
    long memoryUsedKb,
    @NotNull List<DiskInfo> disks,
    @NotNull List<DiskIoInfo> diskIo,
    @NotNull List<NetworkInfo> networks,
    @NotNull List<ProcessInfo> processes
) {
    public double memoryUsedPercent() {
        if (memoryTotalKb <= 0) return 0.0;
        return Math.max(0.0, Math.min(100.0, memoryUsedKb * 100.0 / memoryTotalKb));
    }
}
