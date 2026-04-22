package com.termlab.core.terminal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalDroppedPathFormatterTest {

    @Test
    void formatDroppedPaths_emptyListReturnsEmptyString() {
        assertEquals("", TerminalDroppedPathFormatter.formatDroppedPaths(List.of()));
    }

    @Test
    void formatDroppedPaths_posixAlwaysSingleQuotesAndTrailingSpace() {
        String formatted = TerminalDroppedPathFormatter.formatDroppedPaths(List.of(
            Path.of("/tmp/hello world.png"),
            Path.of("/tmp/it's-here.txt")
        ), TerminalShellKind.POSIX);

        assertEquals("'/tmp/hello world.png' '/tmp/it'\"'\"'s-here.txt' ", formatted);
    }

    @Test
    void formatDroppedPaths_windowsNativeUsesWindowsQuoting() {
        String formatted = TerminalDroppedPathFormatter.formatDroppedPaths(List.of(
            Path.of("/tmp/hello world.txt"),
            Path.of("/tmp/plain.txt")
        ), TerminalShellKind.WINDOWS_NATIVE);

        assertEquals("\"/tmp/hello world.txt\" /tmp/plain.txt ", formatted);
    }

    @Test
    void formatDroppedPaths_windowsGitBashUsesPosixQuoting() {
        String formatted = TerminalDroppedPathFormatter.formatDroppedPaths(List.of(
            Path.of("/tmp/hello world.txt")
        ), TerminalShellKind.POSIX);

        assertEquals("'/tmp/hello world.txt' ", formatted);
    }
}
