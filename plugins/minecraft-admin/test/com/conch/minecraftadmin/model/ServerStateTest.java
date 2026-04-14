package com.conch.minecraftadmin.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ServerStateTest {

    @Test
    void unknownInitial_hasSensibleDefaults() {
        ServerState state = ServerState.unknown(Instant.now());
        assertEquals(McServerStatus.UNKNOWN, state.status());
        assertEquals(0, state.playersOnline());
        assertEquals(0, state.playersMax());
        assertTrue(state.players().isEmpty());
        assertTrue(Double.isNaN(state.tps()));
        assertTrue(Double.isNaN(state.cpuPercent()));
        assertEquals(0L, state.ramUsedMb());
        assertEquals(0L, state.ramMaxMb());
        assertEquals(Duration.ZERO, state.uptime());
        assertTrue(state.ampError().isEmpty());
        assertTrue(state.rconError().isEmpty());
    }

    @Test
    void record_isImmutable_listDefensivelyCopied() {
        var mutable = new java.util.ArrayList<Player>();
        mutable.add(new Player("alice", 42));
        ServerState state = new ServerState(
            McServerStatus.RUNNING, 1, 20, mutable, 20.0, 5.0, 1000, 4000,
            Duration.ofMinutes(3), Optional.empty(), Optional.empty(), Instant.now());
        mutable.add(new Player("bob", 99));
        assertEquals(1, state.players().size(),
            "ServerState must not expose the caller's mutable list");
    }

    @Test
    void isAmpHealthy_falseWhenAmpErrorPresent() {
        ServerState state = ServerState.unknown(Instant.now())
            .withAmpError("connection refused");
        assertFalse(state.isAmpHealthy());
        assertTrue(state.ampError().isPresent());
    }

    @Test
    void isRconHealthy_falseWhenRconErrorPresent() {
        ServerState state = ServerState.unknown(Instant.now())
            .withRconError("auth failed");
        assertFalse(state.isRconHealthy());
    }

    @Test
    void vaultLocked_setsLockedStatusAndBothErrors() {
        Instant now = Instant.now();
        ServerState state = ServerState.vaultLocked(now);
        assertEquals(McServerStatus.LOCKED, state.status());
        assertTrue(state.ampError().isPresent());
        assertTrue(state.rconError().isPresent());
        assertTrue(state.ampError().get().toLowerCase().contains("vault"));
        assertTrue(state.rconError().get().toLowerCase().contains("vault"));
        assertEquals(now, state.sampledAt());
    }
}
