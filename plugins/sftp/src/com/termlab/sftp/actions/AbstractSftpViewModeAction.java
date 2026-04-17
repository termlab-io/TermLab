package com.termlab.sftp.actions;

import com.termlab.sftp.persistence.TermLabSftpConfig;
import com.termlab.sftp.toolwindow.SftpToolWindow;
import com.termlab.sftp.toolwindow.SftpToolWindowFactory;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

abstract class AbstractSftpViewModeAction extends ToggleAction {

    protected AbstractSftpViewModeAction(@NotNull String text, @NotNull String description) {
        super(text, description, null);
    }

    protected abstract @NotNull TermLabSftpConfig.ViewMode mode();

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return TermLabSftpConfig.getInstance().getViewMode() == mode();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        if (!state) return;

        TermLabSftpConfig.getInstance().setViewMode(mode());

        Project project = e.getProject();
        if (project == null) return;

        SftpToolWindow panel = SftpToolWindowFactory.find(project);
        if (panel != null) {
            panel.setViewMode(mode());
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
