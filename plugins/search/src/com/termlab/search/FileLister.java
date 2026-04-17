package com.termlab.search;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.SystemInfo;
import com.termlab.sftp.client.SshSftpSession;
import com.termlab.sftp.search.FileListCache;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class FileLister {

    public enum Tool {
        BUNDLED_RG,
        RG,
        FD,
        FIND,
        WALK,
        SFTP_WALK
    }

    public record ListingResult(@NotNull Tool tool, @NotNull List<String> paths) {
    }

    private static final AtomicReference<Tool> LOCAL_TOOL = new AtomicReference<>();
    private static final Map<org.apache.sshd.client.session.ClientSession, Tool> REMOTE_TOOLS =
        java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private FileLister() {
    }

    public static @NotNull ListingResult listLocal(
        @NotNull Path root,
        @NotNull FileSearchFilter filter,
        @NotNull ProgressIndicator indicator
    ) throws IOException {
        Tool tool = probeLocalTool();
        return switch (tool) {
            case BUNDLED_RG -> new ListingResult(tool, runLocalProcess(root, buildRgCommand(bundledRgPath(), filter), indicator));
            case RG -> new ListingResult(tool, runLocalProcess(root, buildRgCommand("rg", filter), indicator));
            case FD -> new ListingResult(tool, runLocalProcess(root, buildFdCommand("fd", ".", filter), indicator));
            case FIND -> new ListingResult(tool, runLocalProcess(root, buildFindCommand(".", null, filter), indicator));
            case WALK -> new ListingResult(tool, walkLocal(root, null, filter, indicator));
            case SFTP_WALK -> throw new IllegalStateException("Local listing does not use SFTP walk");
        };
    }

    public static @NotNull ListingResult queryLocal(
        @NotNull Path root,
        @NotNull String query,
        @NotNull FileSearchFilter filter,
        @NotNull ProgressIndicator indicator
    ) throws IOException {
        Tool tool = probeLocalTool();
        return switch (tool) {
            case BUNDLED_RG -> new ListingResult(tool, filterBySubstring(
                runLocalProcess(root, buildRgCommand(bundledRgPath(), filter), indicator), query));
            case RG -> new ListingResult(tool, filterBySubstring(
                runLocalProcess(root, buildRgCommand("rg", filter), indicator), query));
            case FD -> new ListingResult(tool, runLocalProcess(root, buildFdCommand("fd", query, filter), indicator));
            case FIND -> new ListingResult(tool, runLocalProcess(root, buildFindCommand(".", query, filter), indicator));
            case WALK -> new ListingResult(tool, walkLocal(root, query, filter, indicator));
            case SFTP_WALK -> throw new IllegalStateException("Local listing does not use SFTP walk");
        };
    }

    public static @NotNull ListingResult listRemote(
        @NotNull SshSftpSession session,
        @NotNull String root,
        @NotNull FileSearchFilter filter,
        @NotNull ProgressIndicator indicator
    ) throws IOException {
        Tool tool = probeRemoteTool(session, indicator);
        return switch (tool) {
            case RG -> new ListingResult(tool, runRemoteProcess(session, root, buildRgCommand("rg", filter), indicator));
            case FD -> new ListingResult(tool, runRemoteProcess(session, root, buildFdCommand("fd", ".", filter), indicator));
            case FIND -> new ListingResult(tool, runRemoteProcess(session, root, buildFindCommand(".", null, filter), indicator));
            case SFTP_WALK -> new ListingResult(tool, walkRemote(session, root, null, filter, indicator));
            case BUNDLED_RG, WALK -> throw new IllegalStateException("Remote listing uses remote tools only");
        };
    }

    public static @NotNull ListingResult queryRemote(
        @NotNull SshSftpSession session,
        @NotNull String root,
        @NotNull String query,
        @NotNull FileSearchFilter filter,
        @NotNull ProgressIndicator indicator
    ) throws IOException {
        Tool tool = probeRemoteTool(session, indicator);
        return switch (tool) {
            case RG -> new ListingResult(tool, filterBySubstring(
                runRemoteProcess(session, root, buildRgCommand("rg", filter), indicator), query));
            case FD -> new ListingResult(tool, runRemoteProcess(session, root, buildFdCommand("fd", query, filter), indicator));
            case FIND -> new ListingResult(tool, runRemoteProcess(session, root, buildFindCommand(".", query, filter), indicator));
            case SFTP_WALK -> new ListingResult(tool, walkRemote(session, root, query, filter, indicator));
            case BUNDLED_RG, WALK -> throw new IllegalStateException("Remote listing uses remote tools only");
        };
    }

    public static @NotNull List<String> buildRgCommand(@NotNull String executable, @NotNull FileSearchFilter filter) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--files");
        command.add("--hidden");
        command.add("--no-ignore-vcs");
        command.addAll(filter.toListCommandFlags(Tool.RG));
        command.add(".");
        return List.copyOf(command);
    }

    public static @NotNull List<String> buildFdCommand(
        @NotNull String executable,
        @NotNull String query,
        @NotNull FileSearchFilter filter
    ) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--type");
        command.add("f");
        command.add("--hidden");
        command.add("--no-ignore");
        command.addAll(filter.toListCommandFlags(Tool.FD));
        command.add(query == null || query.isBlank() ? "." : query);
        command.add(".");
        return List.copyOf(command);
    }

    public static @NotNull List<String> buildFindCommand(
        @NotNull String root,
        @Nullable String query,
        @NotNull FileSearchFilter filter
    ) {
        List<String> command = new ArrayList<>();
        command.add("find");
        command.add(root);
        command.add("-type");
        command.add("f");
        command.addAll(filter.toListCommandFlags(Tool.FIND));
        if (query != null && !query.isBlank()) {
            command.add("-iname");
            command.add("*" + query + "*");
        }
        return List.copyOf(command);
    }

    private static @NotNull Tool probeLocalTool() {
        Tool cached = LOCAL_TOOL.get();
        if (cached != null) return cached;

        Tool detected;
        if (Files.isRegularFile(Path.of(bundledRgPath()))) {
            detected = Tool.BUNDLED_RG;
        } else if (commandExists(SystemInfo.isWindows ? List.of("where", "rg") : List.of("which", "rg"))) {
            detected = Tool.RG;
        } else if (commandExists(SystemInfo.isWindows ? List.of("where", "fd") : List.of("which", "fd"))) {
            detected = Tool.FD;
        } else if (!SystemInfo.isWindows) {
            detected = Tool.FIND;
        } else {
            detected = Tool.WALK;
        }
        LOCAL_TOOL.compareAndSet(null, detected);
        return Objects.requireNonNullElse(LOCAL_TOOL.get(), detected);
    }

    private static @NotNull Tool probeRemoteTool(
        @NotNull SshSftpSession session,
        @NotNull ProgressIndicator indicator
    ) throws IOException {
        Tool cached = REMOTE_TOOLS.get(session.session());
        if (cached != null) return cached;

        String command = "command -v rg >/dev/null 2>&1 && echo rg || "
            + "command -v fd >/dev/null 2>&1 && echo fd || "
            + "command -v find >/dev/null 2>&1 && echo find || echo none";
        List<String> output = runRemoteShell(session, "/", command, indicator);
        String detected = output.isEmpty() ? "none" : output.getFirst().trim();
        Tool tool = switch (detected) {
            case "rg" -> Tool.RG;
            case "fd" -> Tool.FD;
            case "find" -> Tool.FIND;
            default -> Tool.SFTP_WALK;
        };
        REMOTE_TOOLS.put(session.session(), tool);
        return tool;
    }

    private static boolean commandExists(@NotNull List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static @NotNull String bundledRgPath() {
        String binary = SystemInfo.isWindows ? "rg.exe" : "rg";
        return Path.of(PathManager.getBinPath(), binary).toString();
    }

    private static @NotNull List<String> runLocalProcess(
        @NotNull Path root,
        @NotNull List<String> command,
        @NotNull ProgressIndicator indicator
    ) throws IOException {
        Process process = new ProcessBuilder(command)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            return absolutize(root, readLines(reader, process, indicator));
        }
    }

    private static @NotNull List<String> runRemoteProcess(
        @NotNull SshSftpSession session,
        @NotNull String root,
        @NotNull List<String> command,
        @NotNull ProgressIndicator indicator
    ) throws IOException {
        String script = shellCd(root) + " && " + shellJoin(command);
        return absolutize(root, runRemoteShell(session, root, script, indicator));
    }

    private static @NotNull List<String> runRemoteShell(
        @NotNull SshSftpSession session,
        @NotNull String root,
        @NotNull String shellCommand,
        @NotNull ProgressIndicator indicator
    ) throws IOException {
        ChannelExec channel = session.session().createExecChannel("sh -lc " + shellQuote(shellCommand));
        try {
            channel.open().verify(Duration.ofSeconds(10));
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(channel.getInvertedOut(), StandardCharsets.UTF_8))) {
                return readLines(reader, channel, indicator);
            }
        } finally {
            channel.close(false);
        }
    }

    private static @NotNull List<String> readLines(
        @NotNull BufferedReader reader,
        @NotNull Process process,
        @NotNull ProgressIndicator indicator
    ) throws IOException {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (indicator.isCanceled()) {
                process.destroyForcibly();
                break;
            }
            if (!line.isBlank()) {
                lines.add(line);
                if (lines.size() > FileListCache.MAX_PATHS) {
                    process.destroyForcibly();
                    break;
                }
            }
        }
        return List.copyOf(lines);
    }

    private static @NotNull List<String> readLines(
        @NotNull BufferedReader reader,
        @NotNull ChannelExec channel,
        @NotNull ProgressIndicator indicator
    ) throws IOException {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (indicator.isCanceled()) {
                channel.close(false);
                break;
            }
            if (!line.isBlank()) {
                lines.add(line);
                if (lines.size() > FileListCache.MAX_PATHS) {
                    channel.close(false);
                    break;
                }
            }
        }
        channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L);
        return List.copyOf(lines);
    }

    private static @NotNull List<String> walkLocal(
        @NotNull Path root,
        @Nullable String query,
        @NotNull FileSearchFilter filter,
        @NotNull ProgressIndicator indicator
    ) throws IOException {
        List<String> results = new ArrayList<>();
        ArrayDeque<Path> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            if (indicator.isCanceled()) {
                break;
            }
            Path dir = stack.pop();
            try (var stream = Files.list(dir)) {
                for (Path child : stream.toList()) {
                    if (Files.isDirectory(child)) {
                        if (!filter.matchesDirectoryPath(root.relativize(child).toString())) continue;
                        stack.push(child);
                        continue;
                    }
                    String absolute = child.toString();
                    if (acceptPath(absolute, query, filter)) {
                        results.add(absolute);
                        if (results.size() > FileListCache.MAX_PATHS) {
                            return List.copyOf(results);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return List.copyOf(results);
    }

    private static @NotNull List<String> walkRemote(
        @NotNull SshSftpSession session,
        @NotNull String root,
        @Nullable String query,
        @NotNull FileSearchFilter filter,
        @NotNull ProgressIndicator indicator
    ) throws IOException {
        List<String> results = new ArrayList<>();
        ArrayDeque<String> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            if (indicator.isCanceled()) {
                break;
            }
            String dir = stack.pop();
            for (var entry : session.client().readDir(dir)) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) continue;
                String child = dir.endsWith("/") ? dir + name : dir + "/" + name;
                if (entry.getAttributes().isDirectory()) {
                    if (!filter.matchesDirectoryPath(child)) continue;
                    stack.push(child);
                    continue;
                }
                if (acceptPath(child, query, filter)) {
                    results.add(child);
                    if (results.size() > FileListCache.MAX_PATHS) {
                        return List.copyOf(results);
                    }
                }
            }
        }
        return List.copyOf(results);
    }

    private static boolean acceptPath(
        @NotNull String absolutePath,
        @Nullable String query,
        @NotNull FileSearchFilter filter
    ) {
        if (!filter.matchesPath(absolutePath)) return false;
        if (query == null || query.isBlank()) return true;
        return absolutePath.toLowerCase().contains(query.toLowerCase());
    }

    private static @NotNull List<String> absolutize(@NotNull Path root, @NotNull List<String> lines) {
        List<String> paths = new ArrayList<>(lines.size());
        for (String line : lines) {
            Path candidate = Path.of(line);
            paths.add(candidate.isAbsolute() ? candidate.normalize().toString() : root.resolve(line).normalize().toString());
        }
        return List.copyOf(paths);
    }

    private static @NotNull List<String> absolutize(@NotNull String root, @NotNull List<String> lines) {
        List<String> paths = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.startsWith("/")) {
                paths.add(line);
            } else if (".".equals(line)) {
                continue;
            } else if (root.endsWith("/")) {
                paths.add(root + line);
            } else {
                paths.add(root + "/" + line);
            }
        }
        return List.copyOf(paths);
    }

    private static @NotNull List<String> filterBySubstring(@NotNull List<String> paths, @NotNull String query) {
        String lower = query.toLowerCase();
        return paths.stream()
            .filter(path -> path.toLowerCase().contains(lower))
            .toList();
    }

    private static @NotNull String shellCd(@NotNull String root) {
        return "cd " + shellQuote(root);
    }

    private static @NotNull String shellJoin(@NotNull List<String> command) {
        return command.stream().map(FileLister::shellQuote).reduce((a, b) -> a + " " + b).orElse("");
    }

    public static @NotNull String shellQuote(@NotNull String input) {
        return "'" + input.replace("'", "'\"'\"'") + "'";
    }
}
