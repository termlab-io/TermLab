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
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayersPanelTest {

    private static ServerState withPlayers(List<Player> players) {
        return new ServerState(
            McServerStatus.RUNNING, players.size(), 20, players,
            20.0, 10.0, 1000, 4000, Duration.ofMinutes(1),
            Optional.empty(), Optional.empty(), Instant.now());
    }

    @Test
    void render_empty() {
        PlayersPanel panel = new PlayersPanel(name -> {}, name -> {}, name -> {});
        assertDoesNotThrow(() -> panel.update(withPlayers(List.of())));
        assertEquals(0, panel.rowCount());
    }

    @Test
    void render_onePlayer() {
        PlayersPanel panel = new PlayersPanel(name -> {}, name -> {}, name -> {});
        assertDoesNotThrow(() -> panel.update(withPlayers(List.of(new Player("alice", 42)))));
        assertEquals(1, panel.rowCount());
    }

    @Test
    void render_manyPlayers() {
        PlayersPanel panel = new PlayersPanel(name -> {}, name -> {}, name -> {});
        List<Player> players = List.of(
            new Player("alice", 42),
            new Player("bob", 99),
            new Player("carol", Player.PING_UNKNOWN));
        assertDoesNotThrow(() -> panel.update(withPlayers(players)));
        assertEquals(3, panel.rowCount());
    }

    @Test
    void render_unknownPing_showsDash() {
        PlayersPanel panel = new PlayersPanel(name -> {}, name -> {}, name -> {});
        panel.update(withPlayers(List.of(new Player("carol", Player.PING_UNKNOWN))));
        assertEquals("—", panel.pingAt(0));
    }
}
