package com.termlab.core.terminal;

import com.termlab.core.settings.TermLabTerminalConfig;
import com.intellij.execution.process.ColoredOutputTypeRegistryImpl;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.FontPreferences;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.ui.AwtTransformers;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * Terminal settings that:
 * - Use the current IntelliJ editor color scheme for colors (follows theme plugins)
 * - Use IntelliJ's Console Font settings for terminal font family/size/line spacing
 * - Use TermLabTerminalConfig for terminal-specific behavior and extra spacing
 */
public final class TermLabTerminalSettings extends DefaultSettingsProvider {

    @Override
    public @NotNull TextStyle getDefaultStyle() {
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        java.awt.Color fgColor = scheme.getAttributes(ConsoleViewContentType.NORMAL_OUTPUT_KEY).getForegroundColor();
        if (fgColor == null) fgColor = scheme.getDefaultForeground();
        java.awt.Color bgColor = scheme.getColor(ConsoleViewContentType.CONSOLE_BACKGROUND_KEY);
        if (bgColor == null) bgColor = scheme.getDefaultBackground();

        return new TextStyle(
            new TerminalColor(AwtTransformers.fromAwtColor(fgColor)),
            new TerminalColor(AwtTransformers.fromAwtColor(bgColor))
        );
    }

    @Override
    public ColorPalette getTerminalColorPalette() {
        return new ThemeAwareColorPalette();
    }

    @Override
    public TextStyle getSelectionColor() {
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        java.awt.Color selFg = scheme.getColor(com.intellij.openapi.editor.colors.EditorColors.SELECTION_FOREGROUND_COLOR);
        java.awt.Color selBg = scheme.getColor(com.intellij.openapi.editor.colors.EditorColors.SELECTION_BACKGROUND_COLOR);
        if (selFg == null) selFg = scheme.getDefaultForeground();
        if (selBg == null) selBg = new java.awt.Color(33, 66, 131);

        return new TextStyle(
            new TerminalColor(AwtTransformers.fromAwtColor(selFg)),
            new TerminalColor(AwtTransformers.fromAwtColor(selBg))
        );
    }

    /**
     * Theme-aware palette that reads ANSI colors from the current editor color scheme.
     * Works with any theme plugin (Darcula, Light, Dracula, Nord, etc.)
     */
    private static final class ThemeAwareColorPalette extends ColorPalette {
        @Override
        protected Color getForegroundByColorIndex(int colorIndex) {
            TextAttributes attrs = getAttributesForIndex(colorIndex);
            java.awt.Color c = attrs != null ? attrs.getForegroundColor() : null;
            if (c == null && attrs != null) c = attrs.getBackgroundColor();
            if (c == null) c = EditorColorsManager.getInstance().getGlobalScheme().getDefaultForeground();
            return AwtTransformers.fromAwtColor(c);
        }

        @Override
        protected Color getBackgroundByColorIndex(int colorIndex) {
            TextAttributes attrs = getAttributesForIndex(colorIndex);
            java.awt.Color c = attrs != null ? attrs.getBackgroundColor() : null;
            if (c == null && attrs != null) c = attrs.getForegroundColor();
            if (c == null) c = EditorColorsManager.getInstance().getGlobalScheme().getDefaultBackground();
            return AwtTransformers.fromAwtColor(c);
        }

        private TextAttributes getAttributesForIndex(int colorIndex) {
            try {
                TextAttributesKey key = ColoredOutputTypeRegistryImpl.getAnsiColorKey(colorIndex);
                if (key != null) {
                    return EditorColorsManager.getInstance().getGlobalScheme().getAttributes(key);
                }
            } catch (Throwable ignored) {}
            return null;
        }
    }

    // Font with cascading fallback
    private static final String[] FONT_CANDIDATES = {
        "JetBrains Mono", "Menlo", "Monaco", "Courier New", "Courier"
    };

    @Override
    public @NotNull Font getTerminalFont() {
        TermLabTerminalConfig.State s = TermLabTerminalConfig.getInstance().getState();
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        FontPreferences prefs = scheme.getConsoleFontPreferences();
        String family = prefs.getFontFamily();
        if (family == null || family.isEmpty()) {
            family = bestMonospace();
        }

        int size = scheme.getConsoleFontSize();
        Font font = new Font(family, Font.PLAIN, size);

        if (Math.abs(s.characterSpacing) > 0.001f) {
            java.util.Map<java.awt.font.TextAttribute, Object> attrs = new java.util.HashMap<>(font.getAttributes());
            attrs.put(java.awt.font.TextAttribute.TRACKING, s.characterSpacing);
            font = font.deriveFont(attrs);
        }
        return font;
    }

    @Override
    public float getTerminalFontSize() {
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        FontPreferences prefs = scheme.getConsoleFontPreferences();
        String family = prefs.getFontFamily();
        if (family == null || family.isEmpty()) {
            family = bestMonospace();
        }
        return prefs.hasSize(family) ? prefs.getSize(family) : scheme.getConsoleFontSize();
    }

    @Override
    public float getLineSpacing() {
        return EditorColorsManager.getInstance().getGlobalScheme().getConsoleLineSpacing();
    }

    @Override public boolean useAntialiasing() { return true; }
    @Override public boolean audibleBell() { return TermLabTerminalConfig.getInstance().getState().audibleBell; }
    @Override public boolean copyOnSelect() { return TermLabTerminalConfig.getInstance().getState().copyOnSelect; }
    @Override public boolean enableMouseReporting() { return TermLabTerminalConfig.getInstance().getState().enableMouseReporting; }
    @Override public int getBufferMaxLinesCount() { return TermLabTerminalConfig.getInstance().getState().scrollbackLines; }
    @Override public boolean scrollToBottomOnTyping() { return true; }

    private static String bestMonospace() {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        java.util.Set<String> available = java.util.Set.of(ge.getAvailableFontFamilyNames());
        for (String name : FONT_CANDIDATES) {
            if (available.contains(name)) {
                return name;
            }
        }
        return Font.MONOSPACED;
    }
}
