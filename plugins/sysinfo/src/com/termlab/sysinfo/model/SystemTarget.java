package com.termlab.sysinfo.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record SystemTarget(
    @NotNull Kind kind,
    @NotNull String label,
    @Nullable UUID hostId
) {
    public enum Kind {
        LOCAL,
        SSH
    }

    public static @NotNull SystemTarget local() {
        return new SystemTarget(Kind.LOCAL, "Local", null);
    }

    public static @NotNull SystemTarget ssh(@NotNull UUID hostId, @NotNull String label) {
        return new SystemTarget(Kind.SSH, label, hostId);
    }

    public @NotNull String key() {
        return kind == Kind.LOCAL ? "local" : "ssh:" + hostId;
    }

    @Override
    public String toString() {
        return label;
    }
}
