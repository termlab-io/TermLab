package com.conch.core;

import com.conch.core.settings.ConchTerminalConfig;
import com.conch.core.workspace.WorkspaceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import org.jetbrains.annotations.NotNull;

public final class ConchProjectCloseListener implements ProjectManagerListener {
    @Override
    public void projectClosing(@NotNull Project project) {
        WorkspaceManager.getInstance(project).save();

        // Flush Conch config to disk on close.
        ConchTerminalConfig config = ConchTerminalConfig.getInstance();
        config.save();
    }
}
