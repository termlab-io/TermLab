package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.ServerState;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Sink for poller output. The tool window implements this; the poller
 * calls it on the EDT (or a caller-supplied dispatcher for tests).
 */
public interface StateListener {
    void onStateUpdate(@NotNull ServerState state);
    void onConsoleLines(@NotNull List<String> lines);
    void onCrashDetected(@NotNull ServerState state);
}
