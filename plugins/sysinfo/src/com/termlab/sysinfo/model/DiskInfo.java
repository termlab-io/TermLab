package com.termlab.sysinfo.model;

import org.jetbrains.annotations.NotNull;

public record DiskInfo(
    @NotNull String mount,
    long totalKb,
    long usedKb,
    long availableKb,
    int usedPercent
) {
}
