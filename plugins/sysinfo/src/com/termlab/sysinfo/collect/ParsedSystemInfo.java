package com.termlab.sysinfo.collect;

import com.termlab.sysinfo.model.DiskInfo;
import com.termlab.sysinfo.model.DiskIoInfo;
import com.termlab.sysinfo.model.NetworkInfo;
import com.termlab.sysinfo.model.OsKind;
import com.termlab.sysinfo.model.ProcessInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record ParsedSystemInfo(
    @NotNull OsKind osKind,
    @NotNull String hostname,
    @NotNull String kernel,
    @NotNull String architecture,
    @Nullable CpuTimes cpuTimes,
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
}
