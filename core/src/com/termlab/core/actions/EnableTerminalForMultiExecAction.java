package com.termlab.core.actions;

import com.termlab.core.terminal.TermLabMultiExecManager;
import com.termlab.core.terminal.TermLabTerminalVirtualFile;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public final class EnableTerminalForMultiExecAction extends AnAction {
    @Override
    public void update(@NotNull AnActionEvent e) {
        TermLabTerminalVirtualFile file = MultiExecActionSupport.resolveTerminalFile(e);
        boolean visible = false;
        boolean enabled = false;
        if (file != null && MultiExecActionSupport.isSshTerminal(file) && e.getProject() != null) {
            TermLabMultiExecManager manager = TermLabMultiExecManager.getInstance(e.getProject());
            visible = manager.isActive() && manager.isExcluded(file);
            enabled = visible;
        }
        e.getPresentation().setVisible(visible);
        e.getPresentation().setEnabled(enabled);
        e.getPresentation().setText("Enable This Terminal for MultiExec Mode");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (e.getProject() == null) {
            return;
        }
        TermLabTerminalVirtualFile file = MultiExecActionSupport.resolveTerminalFile(e);
        if (file == null) {
            return;
        }
        TermLabMultiExecManager.getInstance(e.getProject()).setExcluded(file, false);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
