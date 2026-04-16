package com.termlab.core;

import com.termlab.core.settings.TermLabTerminalConfig;
import com.termlab.core.workspace.WorkspaceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import org.jetbrains.annotations.NotNull;

public final class TermLabProjectCloseListener implements ProjectManagerListener {
    @Override
    public void projectClosing(@NotNull Project project) {
        WorkspaceManager.getInstance(project).save();

        // Flush TermLab config to disk on close.
        TermLabTerminalConfig config = TermLabTerminalConfig.getInstance();
        config.save();
    }
}
