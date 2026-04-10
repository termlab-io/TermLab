package com.conch.core.terminal;

import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Hides the editor tab bar when only one tab is open.
 * Shows it when multiple tabs are open.
 */
public final class ConchTabBarManager implements FileEditorManagerListener {
    private final Project project;

    public ConchTabBarManager(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        updateTabBarVisibility(source);
    }

    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        // Delay because the file count hasn't been updated yet when this fires
        SwingUtilities.invokeLater(() -> updateTabBarVisibility(source));
    }

    @Override
    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        // no-op
    }

    private void updateTabBarVisibility(@NotNull FileEditorManager manager) {
        int openCount = manager.getOpenFiles().length;
        UISettings settings = UISettings.getInstance();
        int current = settings.getEditorTabPlacement();

        if (openCount <= 1 && current != UISettings.TABS_NONE) {
            settings.setEditorTabPlacement(UISettings.TABS_NONE);
            settings.fireUISettingsChanged();
        } else if (openCount > 1 && current == UISettings.TABS_NONE) {
            settings.setEditorTabPlacement(SwingConstants.TOP);
            settings.fireUISettingsChanged();
        }
    }

    /**
     * Initial check — call after startup to set the correct state.
     */
    public void initialize() {
        FileEditorManager manager = FileEditorManager.getInstance(project);
        updateTabBarVisibility(manager);
    }
}
