package com.conch.core.terminal;

import com.conch.core.settings.ConchTerminalConfig;
import com.conch.sdk.TerminalSessionProvider;
import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LocalPtySessionProvider implements TerminalSessionProvider {

    @Override public @NotNull String getId() { return "com.conch.local-pty"; }
    @Override public @NotNull String getDisplayName() { return "Local Terminal"; }
    @Override public @Nullable Icon getIcon() { return null; }
    @Override public boolean canQuickOpen() { return true; }

    @Override
    public @Nullable TtyConnector createSession(@NotNull SessionContext context) {
        try {
            ConchTerminalConfig.State settings = ConchTerminalConfig.getInstance().getState();

            String shell = normalize(settings.shellProgram);
            if (shell.isEmpty()) {
                shell = normalize(System.getenv("SHELL"));
            }
            if (shell.isEmpty()) {
                shell = "/bin/zsh";
            }

            String rawArgs = normalize(settings.shellArguments);
            List<String> command = new ArrayList<>();
            command.add(shell);
            command.addAll(parseCommandArgs(rawArgs));

            String workDir = context.getWorkingDirectory();
            if (workDir == null) workDir = System.getProperty("user.home");

            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("TERM", "xterm-256color");
            env.put("COLORTERM", "truecolor");
            env.put("TERM_PROGRAM", "Conch");

            PtyProcess process = new PtyProcessBuilder()
                .setCommand(command.toArray(String[]::new))
                .setDirectory(workDir)
                .setEnvironment(env)
                .setInitialColumns(80)
                .setInitialRows(24)
                .start();

            return new LocalPtyTtyConnector(process);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start local PTY: " + e.getMessage(), e);
        }
    }

    private static @NotNull List<String> parseCommandArgs(@NotNull String raw) {
        List<String> args = new ArrayList<>();
        if (raw.isBlank()) {
            return args;
        }

        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;

        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);

            if (escaped) {
                current.append(ch);
                escaped = false;
                continue;
            }

            if (ch == '\\' && !inSingle) {
                escaped = true;
                continue;
            }

            if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }

            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }

            if (Character.isWhitespace(ch) && !inSingle && !inDouble) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(ch);
        }

        if (escaped) {
            current.append('\\');
        }
        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args;
    }

    private static @NotNull String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * TtyConnector that wraps a pty4j PtyProcess with proper resize support.
     * Unlike ProcessTtyConnector (which has a no-op resize), this calls
     * PtyProcess.setWinSize() so tmux, vim, etc. see the correct terminal size.
     */
    static final class LocalPtyTtyConnector implements TtyConnector {
        private final PtyProcess pty;
        private final InputStreamReader reader;
        private final OutputStream writer;

        LocalPtyTtyConnector(PtyProcess pty) {
            this.pty = pty;
            this.reader = new InputStreamReader(pty.getInputStream(), StandardCharsets.UTF_8);
            this.writer = pty.getOutputStream();
        }

        /** Get the PID of the child process for foreground process polling. */
        PtyProcess getPty() {
            return pty;
        }

        @Override
        public int read(char[] buf, int offset, int length) throws IOException {
            return reader.read(buf, offset, length);
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            writer.write(bytes);
            writer.flush();
        }

        @Override
        public void write(String string) throws IOException {
            write(string.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public boolean isConnected() {
            return pty.isAlive();
        }

        @Override
        public void resize(@NotNull TermSize termSize) {
            pty.setWinSize(new WinSize(termSize.getColumns(), termSize.getRows()));
        }

        @Override
        public int waitFor() throws InterruptedException {
            return pty.waitFor();
        }

        @Override
        public boolean ready() throws IOException {
            return reader.ready();
        }

        @Override
        public String getName() {
            return "Local";
        }

        @Override
        public void close() {
            pty.destroy();
        }
    }
}
