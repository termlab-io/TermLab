package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.Player;
import com.conch.minecraftadmin.model.ServerState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerStateMergerTest {

    private static final Instant NOW = Instant.parse("2026-04-12T00:00:00Z");

    @Test
    void bothHealthy_buildsFullSnapshot() {
        ServerState s = ServerStateMerger.merge(
            AmpTickResult.ok(new InstanceStatus(McServerStatus.RUNNING, 45.0, 2000, 4000, Duration.ofMinutes(5))),
            RconTickResult.ok(List.of(new Player("alice", 42)), 1, 20, 19.98),
            NOW);
        assertEquals(McServerStatus.RUNNING, s.status());
        assertEquals(1, s.playersOnline());
        assertEquals(20, s.playersMax());
        assertEquals(45.0, s.cpuPercent(), 0.001);
        assertEquals(2000, s.ramUsedMb());
        assertEquals(4000, s.ramMaxMb());
        assertEquals(19.98, s.tps(), 0.001);
        assertTrue(s.isAmpHealthy());
        assertTrue(s.isRconHealthy());
    }

    @Test
    void ampDown_rconUp_keepsRconFieldsAndRecordsAmpError() {
        ServerState s = ServerStateMerger.merge(
            AmpTickResult.error("connection refused"),
            RconTickResult.ok(List.of(new Player("alice", 42)), 1, 20, 20.0),
            NOW);
        assertEquals(McServerStatus.UNKNOWN, s.status());
        assertTrue(Double.isNaN(s.cpuPercent()));
        assertEquals(1, s.playersOnline());
        assertEquals(20.0, s.tps(), 0.001);
        assertFalse(s.isAmpHealthy());
        assertTrue(s.ampError().isPresent());
        assertTrue(s.isRconHealthy());
    }

    @Test
    void ampUp_rconDown_keepsAmpFieldsAndRecordsRconError() {
        ServerState s = ServerStateMerger.merge(
            AmpTickResult.ok(new InstanceStatus(McServerStatus.RUNNING, 45.0, 2000, 4000, Duration.ofMinutes(5))),
            RconTickResult.error("connection reset"),
            NOW);
        assertEquals(McServerStatus.RUNNING, s.status());
        assertEquals(45.0, s.cpuPercent(), 0.001);
        assertEquals(0, s.playersOnline());
        assertTrue(Double.isNaN(s.tps()));
        assertTrue(s.isAmpHealthy());
        assertFalse(s.isRconHealthy());
    }

    @Test
    void bothDown_fullyUnknown() {
        ServerState s = ServerStateMerger.merge(
            AmpTickResult.error("no route to host"),
            RconTickResult.error("connect timeout"),
            NOW);
        assertEquals(McServerStatus.UNKNOWN, s.status());
        assertTrue(Double.isNaN(s.cpuPercent()));
        assertTrue(Double.isNaN(s.tps()));
        assertFalse(s.isAmpHealthy());
        assertFalse(s.isRconHealthy());
    }

    @Test
    void sampledAtIsPreserved() {
        ServerState s = ServerStateMerger.merge(
            AmpTickResult.error("x"), RconTickResult.error("y"), NOW);
        assertEquals(NOW, s.sampledAt());
    }
}
