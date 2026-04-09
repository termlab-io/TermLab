package com.conch.core.terminal;

import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * Terminal settings with dark color scheme matching Darcula theme.
 */
public final class ConchTerminalSettings extends DefaultSettingsProvider {

    private static final Color BG = new Color(43, 43, 43);       // Darcula background
    private static final Color FG = new Color(187, 187, 187);    // Darcula foreground
    private static final Color SEL_BG = new Color(33, 66, 131);  // Selection blue
    private static final Color SEL_FG = new Color(255, 255, 255);

    // Dark ANSI palette matching Darcula
    private static final Color[] DARK_COLORS = {
        new Color(0, 0, 0),        // Black
        new Color(205, 49, 49),    // Red
        new Color(13, 188, 121),   // Green
        new Color(229, 229, 16),   // Yellow
        new Color(36, 114, 200),   // Blue
        new Color(188, 63, 188),   // Magenta
        new Color(17, 168, 205),   // Cyan
        new Color(229, 229, 229),  // White
        new Color(102, 102, 102),  // Bright Black
        new Color(241, 76, 76),    // Bright Red
        new Color(35, 209, 139),   // Bright Green
        new Color(245, 245, 67),   // Bright Yellow
        new Color(59, 142, 234),   // Bright Blue
        new Color(214, 112, 214),  // Bright Magenta
        new Color(41, 184, 219),   // Bright Cyan
        new Color(255, 255, 255),  // Bright White
    };

    private static final ColorPalette DARK_PALETTE = new ColorPalette() {
        @Override
        protected Color getForegroundByColorIndex(int colorIndex) {
            return DARK_COLORS[colorIndex];
        }

        @Override
        protected Color getBackgroundByColorIndex(int colorIndex) {
            return DARK_COLORS[colorIndex];
        }
    };

    @Override
    public @NotNull TextStyle getDefaultStyle() {
        return new TextStyle(
            new TerminalColor(FG),
            new TerminalColor(BG)
        );
    }

    @Override
    public ColorPalette getTerminalColorPalette() {
        return DARK_PALETTE;
    }

    @Override
    public TextStyle getSelectionColor() {
        return new TextStyle(
            new TerminalColor(SEL_FG),
            new TerminalColor(SEL_BG)
        );
    }

    // Font with cascading fallback
    private static final String[] FONT_CANDIDATES = {
        "JetBrains Mono", "Menlo", "Monaco", "Courier New", "Courier"
    };

    @Override public @NotNull Font getTerminalFont() {
        java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
        java.util.Set<String> available = java.util.Set.of(ge.getAvailableFontFamilyNames());
        for (String name : FONT_CANDIDATES) {
            if (available.contains(name)) {
                return new Font(name, Font.PLAIN, 14);
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, 14);
    }

    @Override public float getTerminalFontSize() { return 14.0f; }
    @Override public boolean useAntialiasing() { return true; }
    @Override public boolean audibleBell() { return false; }
    @Override public boolean copyOnSelect() { return false; }
    @Override public boolean enableMouseReporting() { return true; }
    @Override public int getBufferMaxLinesCount() { return 10000; }
    @Override public boolean scrollToBottomOnTyping() { return true; }
}
