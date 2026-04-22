package com.termlab.core;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.FontPreferences;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Keeps TermLab on the editable Color Scheme -> Console Font path.
 *
 * Earlier builds stored console font settings in the standalone app-level
 * console font service, which makes the color-scheme page read-only. On
 * startup we migrate that old mode back into the global color scheme so the
 * console-font controls stay editable and terminal font settings still live
 * in one place.
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
            Class<?> optionsClass = Class.forName("com.intellij.openapi.editor.colors.impl.AppConsoleFontOptions");
            Object options = optionsClass.getMethod("getInstance").invoke(null);
            if (options == null) {
                return;
            }
            Method isUseEditorFont = optionsClass.getMethod("isUseEditorFont");
            Method getFontPreferences = optionsClass.getMethod("getFontPreferences");
            Method setUseEditorFont = optionsClass.getMethod("setUseEditorFont", boolean.class);
            EditorColorsScheme globalScheme = EditorColorsManager.getInstance().getGlobalScheme();
            boolean hasSavedStandaloneOptions = Files.exists(CONSOLE_FONT_OPTIONS_FILE);
            boolean usingEditorFont = Boolean.TRUE.equals(isUseEditorFont.invoke(options));

            if (!usingEditorFont) {
                Object storedPreferences = getFontPreferences.invoke(options);
                if (storedPreferences instanceof FontPreferences prefs) {
                    globalScheme.setConsoleFontPreferences(prefs);
                }
                setUseEditorFont.invoke(options, true);
                LOG.info("TermLab: migrated standalone Console Font settings into the active color scheme");
            }

            if (!hasSavedStandaloneOptions) {
                globalScheme.setConsoleLineSpacing(DEFAULT_CONSOLE_LINE_SPACING);
                LOG.info("TermLab: defaulted Console Font line spacing to " + DEFAULT_CONSOLE_LINE_SPACING);
            }

        } catch (Throwable t) {
            LOG.warn("TermLab: failed to configure Console Font settings", t);
        }
    }
}
