package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CrashDetectorTest {

    @Test
    void running_toCrashed_firesOnce() {
        CrashDetector d = new CrashDetector();
        assertFalse(d.observe(McServerStatus.RUNNING, clockAt(0)));
        assertTrue(d.observe(McServerStatus.CRASHED, clockAt(5)));
        // Dedupe within the same transition: subsequent CRASHED ticks do nothing.
        assertFalse(d.observe(McServerStatus.CRASHED, clockAt(10)));
    }

    @Test
    void running_toStopped_withoutUserRequest_fires() {
        CrashDetector d = new CrashDetector();
        d.observe(McServerStatus.RUNNING, clockAt(0));
        assertTrue(d.observe(McServerStatus.STOPPED, clockAt(5)));
    }

    @Test
    void running_toStopped_withRecentUserRequest_doesNotFire() {
        CrashDetector d = new CrashDetector();
        d.observe(McServerStatus.RUNNING, clockAt(0));
        d.recordUserStop(clockAt(4));
        assertFalse(d.observe(McServerStatus.STOPPED, clockAt(5)));
    }

    @Test
    void userStopGraceExpiresAfter10Seconds() {
        CrashDetector d = new CrashDetector();
        d.observe(McServerStatus.RUNNING, clockAt(0));
        d.recordUserStop(clockAt(0));
        assertTrue(d.observe(McServerStatus.STOPPED, clockAt(11)),
            "stop observed 11s after user-requested stop should fire");
    }

    @Test
    void unknown_neverFires() {
        CrashDetector d = new CrashDetector();
        d.observe(McServerStatus.RUNNING, clockAt(0));
        assertFalse(d.observe(McServerStatus.UNKNOWN, clockAt(5)));
        assertFalse(d.observe(McServerStatus.UNKNOWN, clockAt(60)));
    }

    @Test
    void restartAfterCrash_firesAgain() {
        CrashDetector d = new CrashDetector();
        d.observe(McServerStatus.RUNNING, clockAt(0));
        assertTrue(d.observe(McServerStatus.CRASHED, clockAt(5)));
        d.observe(McServerStatus.STARTING, clockAt(10));
        d.observe(McServerStatus.RUNNING, clockAt(15));
        // Second crash in a new transition — fires again.
        assertTrue(d.observe(McServerStatus.CRASHED, clockAt(20)));
    }

    @Test
    void initialCrashWithoutPriorRunning_doesNotFire() {
        CrashDetector d = new CrashDetector();
        // First observation is already a crashed state — no transition, no balloon.
        assertFalse(d.observe(McServerStatus.CRASHED, clockAt(0)));
    }

    private static Instant clockAt(long seconds) {
        return Instant.ofEpochSecond(1_700_000_000L + seconds);
    }
}
