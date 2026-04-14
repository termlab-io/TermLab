package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One RCON half of a poll tick. On success, {@code players} / {@code playersOnline} /
 * {@code playersMax} / {@code tps} are populated. On failure, {@code errorMessage}
 * carries the reason and numeric fields are sentinel.
 */
public record RconTickResult(
    @NotNull List<Player> players,
    int playersOnline,
    int playersMax,
    double tps,
    @Nullable String errorMessage
) {
    public RconTickResult { players = List.copyOf(players); }

    public static @NotNull RconTickResult ok(
        @NotNull List<Player> players, int online, int max, double tps
    ) {
        return new RconTickResult(players, online, max, tps, null);
    }

    public static @NotNull RconTickResult error(@NotNull String message) {
        return new RconTickResult(List.of(), 0, 0, Double.NaN, message);
    }

    public boolean healthy() { return errorMessage == null; }
}
