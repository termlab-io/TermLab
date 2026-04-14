package com.conch.minecraftadmin.toolwindow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsolePanelTest {

    @Test
    void render_empty() {
        ConsolePanel panel = new ConsolePanel(cmd -> "", cmd -> {});
        assertDoesNotThrow(() -> panel.appendLines(List.of()));
    }

    @Test
    void render_tailing_appendsLines() {
        ConsolePanel panel = new ConsolePanel(cmd -> "", cmd -> {});
        panel.appendLines(List.of("[17:42:01 INFO]: alice joined the game"));
        panel.appendLines(List.of("[17:42:05 INFO]: bob joined the game"));
        String text = panel.textAreaContents();
        assertTrue(text.contains("alice"));
        assertTrue(text.contains("bob"));
    }

    @Test
    void history_upDownRecallCycles() {
        ConsolePanel panel = new ConsolePanel(cmd -> "ok", cmd -> {});
        panel.sendForTest("list");
        panel.sendForTest("tps");
        panel.sendForTest("say hi");
        assertEquals("say hi", panel.historyUpForTest());
        assertEquals("tps", panel.historyUpForTest());
        assertEquals("list", panel.historyUpForTest());
        assertEquals("list", panel.historyUpForTest(),
            "reaching the top of the history clamps, doesn't wrap");
        assertEquals("tps", panel.historyDownForTest());
    }

    @Test
    void history_isCappedAt20() {
        ConsolePanel panel = new ConsolePanel(cmd -> "", cmd -> {});
        for (int i = 0; i < 25; i++) panel.sendForTest("cmd" + i);
        assertEquals(20, panel.historySize());
    }
}
