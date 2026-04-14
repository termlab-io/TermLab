package com.conch.minecraftadmin.client;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One AMP half of a poll tick. Either {@code status} is present (AMP
 * reachable) or {@code errorMessage} is present (AMP call failed). Never
 * both.
 */
public record AmpTickResult(
    @Nullable InstanceStatus status,
    @Nullable String errorMessage
) {
    public static @NotNull AmpTickResult ok(@NotNull InstanceStatus status) {
        return new AmpTickResult(status, null);
    }
    public static @NotNull AmpTickResult error(@NotNull String message) {
        return new AmpTickResult(null, message);
    }
    public boolean healthy() { return errorMessage == null; }
}
