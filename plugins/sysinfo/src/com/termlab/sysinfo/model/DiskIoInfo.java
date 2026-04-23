package com.termlab.sysinfo.model;

import org.jetbrains.annotations.NotNull;

public record DiskIoInfo(
    @NotNull String name,
    long readBytes,
    long writeBytes,
    double readBytesPerSecond,
    double writeBytesPerSecond
) {
    public @NotNull DiskIoInfo withRates(double readRate, double writeRate) {
        return new DiskIoInfo(name, readBytes, writeBytes, readRate, writeRate);
    }
}
