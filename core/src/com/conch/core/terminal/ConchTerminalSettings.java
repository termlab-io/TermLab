package com.conch.core.terminal;

import com.conch.core.settings.ConchTerminalConfig;
import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * Terminal settings with dark color scheme matching Darcula theme.
 * Delegates to {@link ConchTerminalConfig} for user-configurable values.
 */
public final class ConchTerminalSettings extends DefaultSettingsProvider {

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
        ConchTerminalConfig.State s = getConfigState();
        Color fg = parseColor(s.foreground, new Color(187, 187, 187));
        Color bg = parseColor(s.background, new Color(43, 43, 43));
        return new TextStyle(
            new TerminalColor(fg),
            new TerminalColor(bg)
        );
    }

    @Override
    public ColorPalette getTerminalColorPalette() {
        return DARK_PALETTE;
    }

    @Override
    public TextStyle getSelectionColor() {
        ConchTerminalConfig.State s = getConfigState();
        Color selFg = parseColor(s.selectionForeground, new Color(255, 255, 255));
        Color selBg = parseColor(s.selectionBackground, new Color(33, 66, 131));
        return new TextStyle(
            new TerminalColor(selFg),
            new TerminalColor(selBg)
        );
    }

    // Font with cascading fallback
    private static final String[] FONT_CANDIDATES = {
        "JetBrains Mono", "Menlo", "Monaco", "Courier New", "Courier"
    };

    @Override
    public @NotNull Font getTerminalFont() {
        ConchTerminalConfig.State s = getConfigState();
        String family = !s.fontFamily.isEmpty() ? s.fontFamily : bestMonospace();
        return new Font(family, Font.PLAIN, s.fontSize);
    }

    @Override
    public float getTerminalFontSize() {
        return getConfigState().fontSize;
    }

    @Override public boolean useAntialiasing() { return true; }

    @Override
    public boolean audibleBell() {
        return getConfigState().audibleBell;
    }

    @Override
    public boolean copyOnSelect() {
        return getConfigState().copyOnSelect;
    }

    @Override
    public boolean enableMouseReporting() {
        return getConfigState().enableMouseReporting;
    }

    @Override
    public int getBufferMaxLinesCount() {
        return getConfigState().scrollbackLines;
    }

    @Override public boolean scrollToBottomOnTyping() { return true; }

    private static ConchTerminalConfig.State getConfigState() {
        ConchTerminalConfig config = ConchTerminalConfig.getInstance();
        ConchTerminalConfig.State state = config != null ? config.getState() : null;
        return state != null ? state : new ConchTerminalConfig.State();
    }

    private static String bestMonospace() {
        java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
        java.util.Set<String> available = java.util.Set.of(ge.getAvailableFontFamilyNames());
        for (String name : FONT_CANDIDATES) {
            if (available.contains(name)) {
                return name;
            }
        }
        return Font.MONOSPACED;
    }

    private static Color parseColor(String hex, Color fallback) {
        try {
            java.awt.Color c = java.awt.Color.decode(hex);
            return new Color(c.getRed(), c.getGreen(), c.getBlue());
        } catch (Exception e) {
            return fallback;
        }
    }
}
