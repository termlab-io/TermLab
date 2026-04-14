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

        // Players and TPS: prefer AMP when healthy; fall back to RCON when AMP is
        // unhealthy or AMP didn't populate the metrics (playersMax == 0 / tps NaN).
        // Player NAMES (the list) always come from RCON — AMP only reports counts.
        int playersOnline;
        int playersMax;
        double tps;
        if (amp.healthy() && amp.status() != null) {
            playersOnline = amp.status().playersOnline();
            playersMax = amp.status().playersMax();
            tps = amp.status().tps();
            // Fall back to RCON if AMP didn't fill in TPS (non-Minecraft module case)
            if (Double.isNaN(tps) && rcon.healthy()) {
                tps = rcon.tps();
            }
            // Fall back to RCON if AMP didn't report a player count (max=0 means no data)
            if (playersMax == 0 && rcon.healthy()) {
                playersOnline = rcon.playersOnline();
                playersMax = rcon.playersMax();
            }
        } else if (rcon.healthy()) {
            playersOnline = rcon.playersOnline();
            playersMax = rcon.playersMax();
            tps = rcon.tps();
        } else {
            playersOnline = 0;
            playersMax = 0;
            tps = Double.NaN;
        }

        return new ServerState(
            status,
            playersOnline,
            playersMax,
            rcon.players(),
            tps,
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
