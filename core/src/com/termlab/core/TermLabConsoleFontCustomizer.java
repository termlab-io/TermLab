package com.termlab.core;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.FontPreferences;
import com.intellij.openapi.editor.colors.ModifiableFontPreferences;
import com.intellij.openapi.editor.colors.impl.AppConsoleFontOptions;
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl;
import com.termlab.core.settings.TermLabTerminalConfig;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Migrates legacy console-font sources into the dedicated Terminal settings
 * storage used by TermLabTerminalAppearanceConfigurable.
 */
public final class TermLabConsoleFontCustomizer implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(TermLabConsoleFontCustomizer.class);
    private static final Path CONSOLE_FONT_OPTIONS_FILE = PathManager.getOptionsDir().resolve("console-font.xml");
    private static final float DEFAULT_CONSOLE_LINE_SPACING = 0.8f;

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        applyConsoleFontSettings();
    }

    static void applyConsoleFontSettings() {
        try {
            if (Files.exists(CONSOLE_FONT_OPTIONS_FILE)) {
                return;
            }

            AppConsoleFontOptions options = AppConsoleFontOptions.getInstance();
            FontPreferences legacyPreferences = legacyTermLabFontPreferences();
            if (legacyPreferences != null) {
                options.setUseEditorFont(false);
                options.update(legacyPreferences);
                LOG.info("TermLab: migrated legacy terminal appearance settings into console-font.xml");
                return;
            }

            EditorColorsScheme globalScheme = EditorColorsManager.getInstance().getGlobalScheme();
            globalScheme.setConsoleLineSpacing(DEFAULT_CONSOLE_LINE_SPACING);
            options.setUseEditorFont(false);
            options.update(globalScheme.getConsoleFontPreferences());
            LOG.info("TermLab: migrated existing Console Font settings into console-font.xml");
        } catch (Throwable t) {
            LOG.warn("TermLab: failed to configure Console Font settings", t);
        }
    }

    private static FontPreferences legacyTermLabFontPreferences() {
        TermLabTerminalConfig.State state = TermLabTerminalConfig.getInstance().getState();
        boolean hasLegacyFontFamily = state.fontFamily != null && !state.fontFamily.isBlank();
        boolean hasLegacySize = state.fontSize != 14;
        boolean hasLegacyLineSpacing = Math.abs(state.lineSpacing - DEFAULT_CONSOLE_LINE_SPACING) > 0.001f;
        if (!hasLegacyFontFamily && !hasLegacySize && !hasLegacyLineSpacing) {
            return null;
        }

        FontPreferences basePreferences = EditorColorsManager.getInstance().getGlobalScheme().getFontPreferences();
        FontPreferencesImpl preferences = new FontPreferencesImpl();
        basePreferences.copyTo(preferences);

        if (hasLegacyFontFamily || hasLegacySize) {
            String family = hasLegacyFontFamily ? state.fontFamily : basePreferences.getFontFamily();
            float size = hasLegacySize ? state.fontSize : basePreferences.getSize2D(basePreferences.getFontFamily());
            ModifiableFontPreferences modifiable = preferences;
            modifiable.clearFonts();
            modifiable.register(family, size);
        }
        preferences.setLineSpacing(state.lineSpacing);
        return preferences;
    }
}
