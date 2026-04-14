package com.conch.minecraftadmin.client;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Chunk of new console lines returned by AMP's {@code Core/GetUpdates}.
 * Each line is already formatted ("[17:42:01 INFO]: alice joined the game").
 */
public record ConsoleUpdate(@NotNull List<String> lines) {
    public ConsoleUpdate { lines = List.copyOf(lines); }
    public static @NotNull ConsoleUpdate empty() { return new ConsoleUpdate(List.of()); }
}
