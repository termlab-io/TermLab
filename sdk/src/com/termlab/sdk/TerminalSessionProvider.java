package com.termlab.sdk;

import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Extension point for plugins that provide terminal session backends.
 * Implementations supply a TtyConnector that JediTerm renders.
 * The core ships a local PTY provider; plugins add SSH, Docker, serial, etc.
 */
public interface TerminalSessionProvider {

    /** Unique identifier for this provider (e.g., "com.termlab.local-pty"). */
    @NotNull String getId();

    /** Human-readable name shown in UI (e.g., "Local Terminal", "SSH"). */
    @NotNull String getDisplayName();

    /** Icon for tab bar and menus. */
    @Nullable Icon getIcon();

    /**
     * Whether this provider can open a session immediately without user input.
     * Local PTY returns true. SSH (needs host selection) returns false.
     */
    boolean canQuickOpen();

    /**
     * Create a new terminal session. May show UI to collect parameters
     * (e.g., host picker for SSH). Returns null if the user cancels.
     *
     * @param context provides access to project and application services
     * @return a connected TtyConnector, or null if cancelled
     */
    @Nullable TtyConnector createSession(@NotNull SessionContext context);

    /**
     * Context passed to createSession. Wraps project and app services
     * without requiring plugins to depend on IntelliJ Platform directly.
     */
    interface SessionContext {
        /** The working directory to start the session in (for local PTY). */
        @Nullable String getWorkingDirectory();
    }
}
