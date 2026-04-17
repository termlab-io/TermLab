package com.termlab.sftp.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.termlab.sftp.persistence.TermLabSftpConfig;
import com.termlab.sftp.toolwindow.SftpToolWindow;
import com.termlab.sftp.toolwindow.SftpToolWindowFactory;
import org.jetbrains.annotations.NotNull;

public final class FocusLocalSftpPathAction extends AnAction {

    public FocusLocalSftpPathAction() {
        super("Focus Local SFTP Path", "Focus the local path field in the SFTP pane", null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SftpToolWindowFactory.ID);
        if (toolWindow == null) return;

        toolWindow.show(() -> {
            SftpToolWindow panel = SftpToolWindowFactory.find(project);
            if (panel == null) return;
            if (panel.getViewMode() == TermLabSftpConfig.ViewMode.REMOTE_ONLY) {
                panel.setViewMode(TermLabSftpConfig.ViewMode.BOTH);
            }
            panel.localPane().focusPathField();
        });
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
