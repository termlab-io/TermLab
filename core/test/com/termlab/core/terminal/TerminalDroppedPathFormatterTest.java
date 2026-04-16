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
        ));

        assertEquals("'/tmp/hello world.png' '/tmp/it'\"'\"'s-here.txt' ", formatted);
    }
}
