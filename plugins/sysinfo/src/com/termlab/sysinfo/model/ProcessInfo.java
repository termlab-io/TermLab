package com.termlab.sysinfo.model;

import org.jetbrains.annotations.NotNull;

public record ProcessInfo(
    long pid,
    @NotNull String user,
    double cpuPercent,
    double memoryPercent,
    long rssKb,
    long vszKb,
    @NotNull String command
) {
}
