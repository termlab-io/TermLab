package com.termlab.core;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sets TermLab's fresh-install Console Font defaults without overwriting
 * users who have already saved console font preferences.
 */
public final class TermLabConsoleFontCustomizer implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(TermLabConsoleFontCustomizer.class);
    private static final Path CONSOLE_FONT_OPTIONS_FILE = PathManager.getOptionsDir().resolve("console-font.xml");
    private static final float DEFAULT_CONSOLE_LINE_SPACING = 0.8f;

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        if (Files.exists(CONSOLE_FONT_OPTIONS_FILE)) {
            return;
        }

        try {
            Class<?> optionsClass = Class.forName("com.intellij.openapi.editor.colors.impl.AppConsoleFontOptions");
            Object options = optionsClass.getMethod("getInstance").invoke(null);
            if (options == null) {
                return;
            }

            // Start from the current editor font defaults, but make Console Font
            // independently editable so its line spacing can default to 0.8.
            optionsClass.getMethod("setUseEditorFont", boolean.class).invoke(options, false);

            Object preferences = optionsClass.getMethod("getFontPreferences").invoke(options);
            if (preferences == null) {
                return;
            }

            Method setLineSpacing = preferences.getClass().getMethod("setLineSpacing", float.class);
            setLineSpacing.invoke(preferences, DEFAULT_CONSOLE_LINE_SPACING);

            LOG.info("TermLab: defaulted Console Font line spacing to " + DEFAULT_CONSOLE_LINE_SPACING);
        } catch (Throwable t) {
            LOG.warn("TermLab: failed to configure default Console Font line spacing", t);
        }
    }
}
