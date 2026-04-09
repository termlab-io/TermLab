package com.conch.core;

import com.conch.core.settings.ConchTerminalConfig;
import com.conch.core.workspace.WorkspaceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public final class ConchProjectCloseListener implements ProjectManagerListener {
    @Override
    public void projectClosing(@NotNull Project project) {
        WorkspaceManager.getInstance(project).save();

        // Save Project View visibility and flush config to disk
        ToolWindowManager twm = ToolWindowManager.getInstance(project);
        ToolWindow projectView = twm.getToolWindow("Project");
        ConchTerminalConfig config = ConchTerminalConfig.getInstance();
        if (projectView != null) {
            config.getState().projectViewVisible = projectView.isVisible();
        }
        config.save();
    }
}
