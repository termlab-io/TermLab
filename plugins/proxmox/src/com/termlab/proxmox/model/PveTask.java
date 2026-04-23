package com.termlab.proxmox.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record PveTask(
    @NotNull String upid,
    @NotNull String node,
    @NotNull String status,
    @Nullable String exitStatus
) {
    public boolean complete() {
        return "stopped".equalsIgnoreCase(status);
    }

    public boolean successful() {
        return complete() && (exitStatus == null || "OK".equalsIgnoreCase(exitStatus));
    }
}
