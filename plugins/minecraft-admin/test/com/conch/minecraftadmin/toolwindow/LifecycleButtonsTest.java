package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.ServerState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleButtonsTest {

    private static ServerState withStatus(McServerStatus s) {
        return new ServerState(
            s, 0, 0, List.of(), Double.NaN, Double.NaN, 0, 0, Duration.ZERO,
            Optional.empty(), Optional.empty(), Instant.now());
    }

    @Test
    void running_enablesStopRestartBackup() {
        LifecycleButtons buttons = new LifecycleButtons(() -> {}, () -> {}, () -> {}, () -> {});
        buttons.update(withStatus(McServerStatus.RUNNING));
        assertFalse(buttons.startButton().isEnabled());
        assertTrue(buttons.stopButton().isEnabled());
        assertTrue(buttons.restartButton().isEnabled());
        assertTrue(buttons.backupButton().isEnabled());
    }

    @Test
    void stopped_enablesStartOnly() {
        LifecycleButtons buttons = new LifecycleButtons(() -> {}, () -> {}, () -> {}, () -> {});
        buttons.update(withStatus(McServerStatus.STOPPED));
        assertTrue(buttons.startButton().isEnabled());
        assertFalse(buttons.stopButton().isEnabled());
        assertFalse(buttons.restartButton().isEnabled());
        assertFalse(buttons.backupButton().isEnabled());
    }

    @Test
    void starting_disablesAll() {
        LifecycleButtons buttons = new LifecycleButtons(() -> {}, () -> {}, () -> {}, () -> {});
        buttons.update(withStatus(McServerStatus.STARTING));
        assertFalse(buttons.startButton().isEnabled());
        assertFalse(buttons.stopButton().isEnabled());
        assertFalse(buttons.restartButton().isEnabled());
        assertFalse(buttons.backupButton().isEnabled());
    }

    @Test
    void crashed_enablesStartAndBackup() {
        LifecycleButtons buttons = new LifecycleButtons(() -> {}, () -> {}, () -> {}, () -> {});
        buttons.update(withStatus(McServerStatus.CRASHED));
        assertTrue(buttons.startButton().isEnabled());
        assertFalse(buttons.stopButton().isEnabled());
        assertFalse(buttons.restartButton().isEnabled());
        assertTrue(buttons.backupButton().isEnabled());
    }
}
