package com.conch.core.terminal;

import com.intellij.openapi.diagnostic.Logger;
import com.pty4j.PtyProcess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.function.Consumer;

/**
 * Polls the foreground process of a PTY and reports title changes.
 * Uses macOS/Linux ps command to get the foreground process name.
 * This is how terminal emulators like Terminal.app and iTerm2 get tab titles
 * without relying on OSC escape sequences from the shell.
 */
public final class ForegroundProcessPoller {
    private static final Logger LOG = Logger.getInstance(ForegroundProcessPoller.class);
    private static final long POLL_INTERVAL_MS = 1000;

    private final long childPid;
    private final Consumer<String> titleListener;
    private volatile boolean running = true;
    private String lastTitle = "";

    public ForegroundProcessPoller(long childPid, @NotNull Consumer<String> titleListener) {
        this.childPid = childPid;
        this.titleListener = titleListener;
    }

    public void start() {
        Thread poller = new Thread(() -> {
            while (running) {
                try {
                    String title = getForegroundProcessTitle();
                    if (title != null && !title.equals(lastTitle)) {
                        lastTitle = title;
                        titleListener.accept(title);
                    }
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    // Ignore transient errors
                }
            }
        }, "Conch-fg-process-poller-" + childPid);
        poller.setDaemon(true);
        poller.start();
    }

    public void stop() {
        running = false;
    }

    /**
     * Get the name of the foreground process in the PTY's session.
     * Uses: ps -o comm= -p $(ps -o tpgid= -p PID)
     * which gets the terminal's foreground process group leader's command name.
     */
    private @Nullable String getForegroundProcessTitle() {
        try {
            // Get the foreground process group ID of the child's terminal
            ProcessBuilder pb = new ProcessBuilder(
                "/bin/sh", "-c",
                "ps -o comm= -p $(ps -o tpgid= -p " + childPid + " | tr -d ' ')"
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line = reader.readLine();
                proc.waitFor();
                if (line != null && !line.isBlank()) {
                    // Strip path prefix (e.g., /usr/bin/vim -> vim)
                    String name = line.trim();
                    int lastSlash = name.lastIndexOf('/');
                    if (lastSlash >= 0) {
                        name = name.substring(lastSlash + 1);
                    }
                    // Strip leading dash (login shell: -bash -> bash)
                    if (name.startsWith("-")) {
                        name = name.substring(1);
                    }
                    return name;
                }
            }
        } catch (Exception e) {
            // Process may have exited
        }
        return null;
    }
}
