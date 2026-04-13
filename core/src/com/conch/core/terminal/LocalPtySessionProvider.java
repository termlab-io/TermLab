package com.conch.core.terminal;

import com.conch.core.settings.ConchTerminalConfig;
import com.conch.sdk.TerminalSessionProvider;
import com.intellij.openapi.util.SystemInfo;
import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

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
                shell = defaultShellForPlatform();
            }

            String rawArgs = normalize(settings.shellArguments);
            List<String> parsedArgs = parseCommandArgs(rawArgs);

            // Default to a login shell on Unix when the user hasn't
            // specified arguments, so .zprofile / .bash_profile runs
            // and seeds PATH / LANG / aliases — the same behavior
            // Terminal.app, iTerm2, and Alacritty default to. Without
            // this, a Finder-launched Conch gets launchd's stripped
            // env (no Homebrew on PATH, no LANG, etc.) and the shell
            // inside never gets a chance to fix it.
            if (parsedArgs.isEmpty() && !SystemInfo.isWindows && isLoginCapableShell(shell)) {
                parsedArgs = List.of("-l");
            }

            List<String> command = new ArrayList<>();
            command.add(shell);
            command.addAll(parsedArgs);

            String workDir = context.getWorkingDirectory();
            if (workDir == null) workDir = System.getProperty("user.home");

            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("TERM", "xterm-256color");
            env.put("COLORTERM", "truecolor");
            env.put("TERM_PROGRAM", "Conch");
            applyFinderLaunchFallbacks(env);

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

    /**
     * Finder-launched macOS apps (and .desktop-launched Linux apps)
     * inherit a minimal env from launchd / the desktop session: no
     * {@code LANG}, no {@code LC_CTYPE}, and a PATH reduced to
     * {@code /usr/bin:/bin:/usr/sbin:/sbin}. Terminal-launched runs
     * inherit the user's full shell env. This method papers over the
     * difference by filling in sensible defaults when something is
     * missing, without clobbering anything the user has set.
     *
     * <ul>
     *   <li><b>Locale:</b> default {@code LANG} / {@code LC_ALL} /
     *     {@code LC_CTYPE} to {@code en_US.UTF-8} if absent. Matches
     *     what Terminal.app does when its "Set locale environment
     *     variables on startup" option is enabled (default on).</li>
     *   <li><b>PATH:</b> on macOS, prepend the common user-install
     *     directories (Homebrew x86 + Apple Silicon, ~/.local/bin,
     *     ~/.cargo/bin) so `which brew`, `which git` etc. work even
     *     before the login shell's profile runs. The user's
     *     .zprofile / .bash_profile (which `-l` triggers) will then
     *     further extend this.</li>
     * </ul>
     */
    private static void applyFinderLaunchFallbacks(@NotNull Map<String, String> env) {
        env.computeIfAbsent("LANG", k -> "en_US.UTF-8");
        env.computeIfAbsent("LC_ALL", k -> env.getOrDefault("LANG", "en_US.UTF-8"));
        env.computeIfAbsent("LC_CTYPE", k -> env.getOrDefault("LANG", "en_US.UTF-8"));

        if (SystemInfo.isMac || SystemInfo.isLinux) {
            String currentPath = env.getOrDefault("PATH", "");
            String augmented = augmentPathWithUserBinDirs(currentPath);
            if (!augmented.equals(currentPath)) {
                env.put("PATH", augmented);
            }
        }
    }

    /**
     * Prepend common user tool directories to {@code PATH} if they
     * exist on disk and aren't already present. Only directories that
     * actually exist are added, so this stays minimal for users who
     * don't have Homebrew / Cargo installed.
     */
    private static @NotNull String augmentPathWithUserBinDirs(@NotNull String currentPath) {
        String home = System.getProperty("user.home");
        List<String> candidates = new ArrayList<>();
        if (SystemInfo.isMac) {
            // Apple Silicon Homebrew lives under /opt, Intel Homebrew
            // under /usr/local. Both get checked; whichever exists
            // gets added.
            candidates.add("/opt/homebrew/bin");
            candidates.add("/opt/homebrew/sbin");
            candidates.add("/usr/local/bin");
            candidates.add("/usr/local/sbin");
        }
        if (home != null) {
            candidates.add(home + "/.local/bin");
            candidates.add(home + "/.cargo/bin");
        }

        // Baseline POSIX paths, always safe to include.
        candidates.add("/usr/bin");
        candidates.add("/bin");
        candidates.add("/usr/sbin");
        candidates.add("/sbin");

        StringBuilder prepended = new StringBuilder();
        for (String dir : candidates) {
            if (!Files.isDirectory(Path.of(dir))) continue;
            if (pathContains(currentPath, dir)) continue;
            if (prepended.length() > 0) prepended.append(':');
            prepended.append(dir);
        }

        if (prepended.length() == 0) {
            return currentPath;
        }
        if (currentPath.isEmpty()) {
            return prepended.toString();
        }
        return prepended + ":" + currentPath;
    }

    private static boolean pathContains(@NotNull String pathVar, @NotNull String candidate) {
        if (pathVar.isEmpty()) return false;
        for (String entry : pathVar.split(":")) {
            if (entry.equals(candidate)) return true;
        }
        return false;
    }

    /**
     * True when the shell binary looks like one that understands the
     * {@code -l} login flag. Covers bash, zsh, sh, fish, dash, tcsh,
     * ksh. Excludes non-shell binaries and Windows cmd / PowerShell
     * (neither of which accepts {@code -l}).
     */
    private static boolean isLoginCapableShell(@NotNull String shellPath) {
        String name = Path.of(shellPath).getFileName().toString().toLowerCase();
        return switch (name) {
            case "bash", "zsh", "sh", "fish", "dash", "tcsh", "csh", "ksh" -> true;
            default -> false;
        };
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
     * Platform-aware default shell when neither the user setting nor
     * {@code $SHELL} is available.
     * <ul>
     *   <li><b>Windows</b>: prefer {@code %COMSPEC%} (usually {@code cmd.exe}).
     *       PowerShell is not the default — users who want it can set it
     *       explicitly in Terminal settings.</li>
     *   <li><b>macOS</b>: {@code /bin/zsh} (Apple's default since 10.15).</li>
     *   <li><b>Linux/BSD</b>: {@code /bin/bash} if present, else
     *       {@code /bin/sh} (POSIX guaranteed).</li>
     * </ul>
     */
    private static @NotNull String defaultShellForPlatform() {
        if (SystemInfo.isWindows) {
            String comspec = normalize(System.getenv("COMSPEC"));
            return comspec.isEmpty() ? "cmd.exe" : comspec;
        }
        if (SystemInfo.isMac) {
            return "/bin/zsh";
        }
        // Linux / BSD / other Unix: pick bash if it exists, fall back to sh.
        if (Files.isExecutable(Path.of("/bin/bash"))) {
            return "/bin/bash";
        }
        return "/bin/sh";
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
