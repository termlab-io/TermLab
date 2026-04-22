package com.termlab.core.actions;

import com.termlab.core.terminal.TermLabMultiExecManager;
import com.termlab.core.terminal.TermLabTerminalVirtualFile;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public final class ExitMultiExecAction extends AnAction {
    @Override
    public void update(@NotNull AnActionEvent e) {
        TermLabTerminalVirtualFile file = MultiExecActionSupport.resolveTerminalFile(e);
        boolean visible = file != null
            && MultiExecActionSupport.isSshTerminal(file)
            && e.getProject() != null
            && TermLabMultiExecManager.getInstance(e.getProject()).isActive();
        e.getPresentation().setVisible(visible);
        e.getPresentation().setEnabled(visible);
        e.getPresentation().setText("Exit Multi-execution Mode");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (e.getProject() == null) {
            return;
        }
        TermLabMultiExecManager.getInstance(e.getProject()).deactivate();
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
