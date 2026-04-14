package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.ServerState;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Pure function that combines one AMP tick result and one RCON tick
 * result into a {@link ServerState} snapshot. Kept separate from
 * {@link ServerPoller} so the merge logic is trivially unit-testable.
 */
public final class ServerStateMerger {

    private ServerStateMerger() {}

    public static @NotNull ServerState merge(
        @NotNull AmpTickResult amp,
        @NotNull RconTickResult rcon,
        @NotNull Instant sampledAt
    ) {
        McServerStatus status = amp.healthy() && amp.status() != null
            ? amp.status().status()
            : McServerStatus.UNKNOWN;
        double cpu = amp.healthy() && amp.status() != null ? amp.status().cpuPercent() : Double.NaN;
        long ramUsed = amp.healthy() && amp.status() != null ? amp.status().ramUsedMb() : 0L;
        long ramMax = amp.healthy() && amp.status() != null ? amp.status().ramMaxMb() : 0L;
        Duration uptime = amp.healthy() && amp.status() != null ? amp.status().uptime() : Duration.ZERO;

        return new ServerState(
            status,
            rcon.playersOnline(),
            rcon.playersMax(),
            rcon.players(),
            rcon.healthy() ? rcon.tps() : Double.NaN,
            cpu, ramUsed, ramMax, uptime,
            amp.healthy() ? Optional.empty() : Optional.of(amp.errorMessage()),
            rcon.healthy() ? Optional.empty() : Optional.of(rcon.errorMessage()),
            sampledAt
        );
    }

    /** Convenience for "no data yet". */
    public static @NotNull ServerState initial(@NotNull Instant sampledAt) {
        return ServerState.unknown(sampledAt);
    }

    /** Unused field-access helper kept for symmetry with rcon/amp result types. */
    @SuppressWarnings("unused")
    private static List<?> nothing() { return List.of(); }
}
