package com.conch.minecraftadmin.model;

import org.jetbrains.annotations.NotNull;

/**
 * One online player. Ping is best-effort: {@code -1} means "unknown"
 * because Paper's stock RCON {@code list} command returns names only
 * and we may not have a secondary ping source.
 */
public record Player(@NotNull String name, int pingMs) {
    public static final int PING_UNKNOWN = -1;

    public Player {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("player name must be non-blank");
        }
    }

    public static @NotNull Player unknownPing(@NotNull String name) {
        return new Player(name, PING_UNKNOWN);
    }
}
