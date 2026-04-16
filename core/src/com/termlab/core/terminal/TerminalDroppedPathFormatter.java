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
        if (paths.isEmpty()) return "";
        return paths.stream()
            .map(Path::toAbsolutePath)
            .map(Path::toString)
            .map(TerminalDroppedPathFormatter::quotePath)
            .collect(Collectors.joining(" ", "", " "));
    }

    private static @NotNull String quotePath(@NotNull String path) {
        if (SystemInfoRt.isWindows) {
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
