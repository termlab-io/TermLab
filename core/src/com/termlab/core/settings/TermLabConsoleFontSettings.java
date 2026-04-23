package com.termlab.core.settings;

import com.intellij.ide.ui.UISettingsUtils;
import com.intellij.openapi.editor.colors.FontPreferences;
import com.intellij.openapi.editor.colors.impl.AppConsoleFontOptions;
import com.intellij.openapi.editor.colors.impl.AppEditorFontOptions;
import org.jetbrains.annotations.NotNull;

import java.awt.Font;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.Map;

public final class TermLabConsoleFontSettings {

    private TermLabConsoleFontSettings() {}

    public static @NotNull FontPreferences getEffectiveFontPreferences() {
        AppConsoleFontOptions consoleOptions = AppConsoleFontOptions.getInstance();
        return consoleOptions.isUseEditorFont()
            ? AppEditorFontOptions.getInstance().getFontPreferences()
            : consoleOptions.getFontPreferences();
    }

    public static float getScaledFontSize() {
        FontPreferences prefs = getEffectiveFontPreferences();
        String family = prefs.getFontFamily();
        float baseSize = prefs.hasSize(family) ? prefs.getSize2D(family) : 13f;
        return UISettingsUtils.getInstance().scaleFontSize(baseSize);
    }

    public static float getLineSpacing() {
        return getEffectiveFontPreferences().getLineSpacing();
    }

    public static @NotNull Font createTerminalFont() {
        FontPreferences prefs = getEffectiveFontPreferences();
        Font font = new Font(prefs.getFontFamily(), Font.PLAIN, 1).deriveFont(getScaledFontSize());
        if (prefs.useLigatures()) {
            Map<TextAttribute, Object> attributes = new HashMap<>(font.getAttributes());
            attributes.put(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON);
            font = font.deriveFont(attributes);
        }
        return font;
    }
}
