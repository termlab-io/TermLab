package com.conch.core.terminal;

import com.conch.sdk.TerminalSessionProvider;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ProcessTtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class LocalPtySessionProvider implements TerminalSessionProvider {

    @Override public @NotNull String getId() { return "com.conch.local-pty"; }
    @Override public @NotNull String getDisplayName() { return "Local Terminal"; }
    @Override public @Nullable Icon getIcon() { return null; }
    @Override public boolean canQuickOpen() { return true; }

    @Override
    public @Nullable TtyConnector createSession(@NotNull SessionContext context) {
        try {
            String shell = System.getenv("SHELL");
            if (shell == null || shell.isBlank()) shell = "/bin/zsh";

            String workDir = context.getWorkingDirectory();
            if (workDir == null) workDir = System.getProperty("user.home");

            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("TERM", "xterm-256color");
            env.put("COLORTERM", "truecolor");
            env.put("TERM_PROGRAM", "Conch");

            PtyProcess process = new PtyProcessBuilder()
                .setCommand(new String[]{shell, "-l"})
                .setDirectory(workDir)
                .setEnvironment(env)
                .setInitialColumns(220)
                .setInitialRows(50)
                .start();

            return new ProcessTtyConnector(process, StandardCharsets.UTF_8) {
                @Override
                public String getName() {
                    return "Local";
                }
            };
        } catch (IOException e) {
            throw new RuntimeException("Failed to start local PTY: " + e.getMessage(), e);
        }
    }
}
