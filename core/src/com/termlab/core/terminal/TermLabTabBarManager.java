package com.termlab.core.terminal;

import com.intellij.ide.actions.DistractionFreeModeController;
import com.intellij.ide.ui.UISettings;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;

import javax.swing.*;

/**
 * Keeps the editor tab bar hidden for a single open tab and visible for
 * multiple open tabs, including while distraction free mode is enabled.
 */
public final class TermLabTabBarManager {
    private static final String BEFORE_EDITOR_TAB_PLACEMENT_KEY =
        "BEFORE." + DistractionFreeModeController.DISTRACTION_MODE_PROPERTY_KEY + "EDITOR_TAB_PLACEMENT";

    private TermLabTabBarManager() {}

    public static void normalizeDistractionFreeModeTabPlacement() {
        if (DistractionFreeModeController.isDistractionFreeModeEnabled()) {
            PropertiesComponent.getInstance().setValue(BEFORE_EDITOR_TAB_PLACEMENT_KEY, String.valueOf(SwingConstants.TOP));
        }
    }

    public static void schedulePreferredTabSettings(Project project) {
        if (project.isDisposed()) {
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                applyPreferredTabSettingsOnEdt(project);
            }
        }, project.getDisposed());
    }

    public static void applyPreferredTabSettings(Project project) {
        if (project.isDisposed()) {
            return;
        }

        if (ApplicationManager.getApplication().isDispatchThread()) {
            applyPreferredTabSettingsOnEdt(project);
            return;
        }

        schedulePreferredTabSettings(project);
    }

    private static void applyPreferredTabSettingsOnEdt(Project project) {
        UISettings settings = UISettings.getInstance();
        int openTabCount = FileEditorManager.getInstance(project).getOpenFiles().length;
        int desiredPlacement = openTabCount > 1 ? SwingConstants.TOP : UISettings.TABS_NONE;

        if (settings.getEditorTabPlacement() != desiredPlacement) {
            settings.setEditorTabPlacement(desiredPlacement);
            settings.fireUISettingsChanged();
        }
    }
}
