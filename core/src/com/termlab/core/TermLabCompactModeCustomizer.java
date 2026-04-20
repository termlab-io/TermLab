package com.termlab.core;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Enables Compact Mode for fresh installs without overwriting existing UI
 * density preferences.
 */
public final class TermLabCompactModeCustomizer implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(TermLabCompactModeCustomizer.class);
    private static final Path UI_SETTINGS_FILE = PathManager.getOptionsDir().resolve("ui.lnf.xml");

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        if (hasExplicitDensityPreference()) {
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            UISettings settings = UISettings.getInstance();
            if (settings.getCompactMode()) {
                return;
            }

            settings.setCompactMode(true);
            settings.fireUISettingsChanged();
            LOG.info("TermLab: enabled Compact Mode by default for fresh install");
        });
    }

    private static boolean hasExplicitDensityPreference() {
        if (!Files.exists(UI_SETTINGS_FILE)) {
            return false;
        }
        try {
            String xml = Files.readString(UI_SETTINGS_FILE);
            return xml.contains("name=\"UI_DENSITY\"") || xml.contains("name=\"COMPACT_MODE\"");
        } catch (Exception e) {
            LOG.warn("TermLab: failed to inspect ui.lnf.xml for density preference", e);
            return true;
        }
    }
}
