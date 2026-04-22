package com.termlab.core.terminal;

import com.intellij.openapi.util.SystemInfoRt;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Formats dropped file paths into terminal-ready text.
 *
 * <p>Windows uses double quotes for paths containing whitespace; POSIX shells
 * use single-quote escaping so spaces and shell metacharacters are preserved.
 */
public final class TerminalDroppedPathFormatter {

    private TerminalDroppedPathFormatter() {}

    public static @NotNull String formatDroppedPaths(@NotNull List<Path> paths) {
        TerminalShellKind shellKind = SystemInfoRt.isWindows
            ? TerminalShellKind.WINDOWS_NATIVE
            : TerminalShellKind.POSIX;
        return formatDroppedPaths(paths, shellKind);
    }

    public static @NotNull String formatDroppedPaths(@NotNull List<Path> paths,
                                                     @NotNull TerminalShellKind shellKind) {
        if (paths.isEmpty()) return "";
        return paths.stream()
            .map(Path::toAbsolutePath)
            .map(Path::toString)
            .map(path -> quotePath(path, shellKind))
            .collect(Collectors.joining(" ", "", " "));
    }

    private static @NotNull String quotePath(@NotNull String path,
                                             @NotNull TerminalShellKind shellKind) {
        if (shellKind == TerminalShellKind.WINDOWS_NATIVE) {
            return needsWindowsQuotes(path) ? "\"" + path.replace("\"", "\\\"") + "\"" : path;
        }
        return "'" + path.replace("'", "'\"'\"'") + "'";
    }

    private static boolean needsWindowsQuotes(@NotNull String path) {
        for (int i = 0; i < path.length(); i++) {
            if (Character.isWhitespace(path.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
