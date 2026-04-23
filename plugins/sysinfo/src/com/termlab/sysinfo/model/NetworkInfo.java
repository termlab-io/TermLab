package com.termlab.sysinfo.model;

import org.jetbrains.annotations.NotNull;

public record NetworkInfo(
    @NotNull String name,
    long rxBytes,
    long txBytes,
    double rxBytesPerSecond,
    double txBytesPerSecond
) {
    public NetworkInfo withRates(double rxRate, double txRate) {
        return new NetworkInfo(name, rxBytes, txBytes, rxRate, txRate);
    }
}
