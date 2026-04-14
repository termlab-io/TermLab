package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.Player;
import com.conch.minecraftadmin.model.ServerState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class StatusStripPanelTest {

    private static ServerState healthy() {
        return new ServerState(
            McServerStatus.RUNNING, 2, 20,
            List.of(new Player("alice", 42), new Player("bob", 99)),
            19.98, 45.0, 2000, 4000, Duration.ofMinutes(3),
            Optional.empty(), Optional.empty(), Instant.now());
    }

    @Test
    void render_healthyState() {
        StatusStripPanel panel = new StatusStripPanel();
        assertDoesNotThrow(() -> panel.update(healthy()));
    }

    @Test
    void render_ampDown_rconUp() {
        StatusStripPanel panel = new StatusStripPanel();
        assertDoesNotThrow(() -> panel.update(healthy().withAmpError("connection refused")));
    }

    @Test
    void render_rconDown_ampUp() {
        StatusStripPanel panel = new StatusStripPanel();
        assertDoesNotThrow(() -> panel.update(healthy().withRconError("auth failed")));
    }

    @Test
    void render_bothDown() {
        StatusStripPanel panel = new StatusStripPanel();
        ServerState bothDown = healthy()
            .withAmpError("connection refused")
            .withRconError("connection reset");
        assertDoesNotThrow(() -> panel.update(bothDown));
    }

    @Test
    void render_nanTps_doesNotThrow() {
        StatusStripPanel panel = new StatusStripPanel();
        ServerState nan = new ServerState(
            McServerStatus.RUNNING, 0, 20, List.of(),
            Double.NaN, 10.0, 100, 1000, Duration.ZERO,
            Optional.empty(), Optional.empty(), Instant.now());
        assertDoesNotThrow(() -> panel.update(nan));
    }

    @Test
    void render_unknownInitial_doesNotThrow() {
        StatusStripPanel panel = new StatusStripPanel();
        assertDoesNotThrow(() -> panel.update(ServerState.unknown(Instant.now())));
    }
}
