package com.conch.core.actions;

import com.conch.core.workspace.WorkspaceManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

public final class SaveWorkspaceAction extends AnAction implements DumbAware {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        String name = Messages.showInputDialog(
            project, "Workspace name:", "Save Workspace As", null
        );

        if (name != null && !name.isBlank()) {
            WorkspaceManager.getInstance(project).saveAs(name.trim());
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
