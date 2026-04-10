package com.conch.core.terminal;

import com.conch.core.settings.ConchTerminalConfig;
import com.intellij.execution.process.ColoredOutputTypeRegistryImpl;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
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
 * - Use our own ConchTerminalConfig for font, line spacing, cursor, and behavior
 */
public final class ConchTerminalSettings extends DefaultSettingsProvider {

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
        ConchTerminalConfig.State s = ConchTerminalConfig.getInstance().getState();
        String family = !s.fontFamily.isEmpty() ? s.fontFamily : bestMonospace();
        Font font = new Font(family, Font.PLAIN, s.fontSize);

        if (Math.abs(s.characterSpacing) > 0.001f) {
            java.util.Map<java.awt.font.TextAttribute, Object> attrs = new java.util.HashMap<>(font.getAttributes());
            attrs.put(java.awt.font.TextAttribute.TRACKING, s.characterSpacing);
            font = font.deriveFont(attrs);
        }
        return font;
    }

    @Override public float getTerminalFontSize() { return ConchTerminalConfig.getInstance().getState().fontSize; }
    @Override public float getLineSpacing() { return ConchTerminalConfig.getInstance().getState().lineSpacing; }
    @Override public boolean useAntialiasing() { return true; }
    @Override public boolean audibleBell() { return ConchTerminalConfig.getInstance().getState().audibleBell; }
    @Override public boolean copyOnSelect() { return ConchTerminalConfig.getInstance().getState().copyOnSelect; }
    @Override public boolean enableMouseReporting() { return ConchTerminalConfig.getInstance().getState().enableMouseReporting; }
    @Override public int getBufferMaxLinesCount() { return ConchTerminalConfig.getInstance().getState().scrollbackLines; }
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
