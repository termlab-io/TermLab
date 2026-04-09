package com.conch.core.terminal;

import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

public final class ConchTerminalSettings extends DefaultSettingsProvider {
    @Override public @NotNull Font getTerminalFont() {
        return new Font("JetBrains Mono", Font.PLAIN, 14);
    }
    @Override public float getTerminalFontSize() { return 14.0f; }
    @Override public boolean audibleBell() { return false; }
    @Override public boolean enableMouseReporting() { return true; }
    @Override public int getBufferMaxLinesCount() { return 10000; }
    @Override public boolean scrollToBottomOnTyping() { return true; }
}
