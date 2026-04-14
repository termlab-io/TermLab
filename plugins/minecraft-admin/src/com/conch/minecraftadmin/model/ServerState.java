package com.conch.minecraftadmin.model;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Immutable snapshot of one server's state at one point in time. Every
 * field is either fully populated or explicitly marked unavailable
 * (via {@code NaN}, {@code 0}, or {@code Optional.empty()}). The UI
 * renders directly from this record — never from the clients — so the
 * EDT always sees a coherent view.
 *
 * @param status         latest lifecycle state from AMP, or UNKNOWN
 * @param playersOnline  count from RCON {@code list}; 0 if RCON is down
 * @param playersMax     max-player cap from RCON {@code list}; 0 if RCON is down
 * @param players        list of online players (defensive copy)
 * @param tps            latest TPS from RCON {@code tps}; NaN if unavailable
 * @param cpuPercent     AMP CPU% for the instance; NaN if AMP is unreachable
 * @param ramUsedMb      AMP RAM used; 0 if AMP is unreachable
 * @param ramMaxMb       AMP RAM limit; 0 if AMP is unreachable
 * @param uptime         AMP-reported uptime since last start
 * @param ampError       last AMP error message, empty if healthy
 * @param rconError      last RCON error message, empty if healthy
 * @param sampledAt      wall-clock time when the snapshot was built
 */
public record ServerState(
    @NotNull McServerStatus status,
    int playersOnline,
    int playersMax,
    @NotNull List<Player> players,
    double tps,
    double cpuPercent,
    long ramUsedMb,
    long ramMaxMb,
    @NotNull Duration uptime,
    @NotNull Optional<String> ampError,
    @NotNull Optional<String> rconError,
    @NotNull Instant sampledAt
) {
    public ServerState {
        // Defensive copy so callers can't mutate out from under the EDT.
        players = List.copyOf(players);
    }

    public static @NotNull ServerState unknown(@NotNull Instant sampledAt) {
        return new ServerState(
            McServerStatus.UNKNOWN,
            0, 0, List.of(),
            Double.NaN, Double.NaN, 0L, 0L, Duration.ZERO,
            Optional.empty(), Optional.empty(),
            sampledAt);
    }

    /**
     * Factory for the "waiting on vault unlock" state. Both halves are
     * marked failed with a message telling the user to click Refresh,
     * and the status is {@link McServerStatus#LOCKED} so the status
     * strip and lifecycle buttons can render a distinct "🔒 Vault locked"
     * state instead of the normal AMP/RCON offline pills.
     */
    public static @NotNull ServerState vaultLocked(@NotNull Instant sampledAt) {
        return new ServerState(
            McServerStatus.LOCKED,
            0, 0, List.of(),
            Double.NaN, Double.NaN, 0L, 0L, Duration.ZERO,
            Optional.of("Vault is locked — click Refresh to unlock"),
            Optional.of("Vault is locked — click Refresh to unlock"),
            sampledAt);
    }

    public boolean isAmpHealthy() {
        return ampError.isEmpty();
    }

    public boolean isRconHealthy() {
        return rconError.isEmpty();
    }

    public @NotNull ServerState withAmpError(@NotNull String message) {
        return new ServerState(
            status, playersOnline, playersMax, players, tps, cpuPercent,
            ramUsedMb, ramMaxMb, uptime,
            Optional.of(message), rconError, sampledAt);
    }

    public @NotNull ServerState withRconError(@NotNull String message) {
        return new ServerState(
            status, playersOnline, playersMax, players, tps, cpuPercent,
            ramUsedMb, ramMaxMb, uptime,
            ampError, Optional.of(message), sampledAt);
    }
}
