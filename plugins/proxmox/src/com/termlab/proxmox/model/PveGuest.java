package com.termlab.proxmox.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record PveGuest(
    int vmid,
    @NotNull String name,
    @NotNull String node,
    @NotNull PveGuestType type,
    @NotNull PveGuestStatus status,
    double cpuPercent,
    int maxCpu,
    long memoryBytes,
    long maxMemoryBytes,
    long diskBytes,
    long maxDiskBytes,
    long uptimeSeconds,
    @Nullable String template
) {
    public @NotNull String key() {
        return node + ":" + type.apiName() + ":" + vmid;
    }

    public double memoryPercent() {
        return maxMemoryBytes <= 0 ? 0.0 : (memoryBytes * 100.0) / maxMemoryBytes;
    }

    public double diskPercent() {
        return maxDiskBytes <= 0 ? 0.0 : (diskBytes * 100.0) / maxDiskBytes;
    }

    public boolean isRunning() {
        return status == PveGuestStatus.RUNNING;
    }

    public boolean isStopped() {
        return status == PveGuestStatus.STOPPED;
    }
}
