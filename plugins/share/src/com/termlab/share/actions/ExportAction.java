package com.termlab.share.actions;

import com.termlab.share.ui.ExportDialog;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public class ExportAction extends AnAction {

    private final ExportDialog.EntryPoint entryPoint;

    public ExportAction() {
        this(ExportDialog.EntryPoint.HOSTS);
    }

    public ExportAction(ExportDialog.EntryPoint entryPoint) {
        super("Export TermLab Bundle...", "Export SSH hosts and tunnels as an encrypted bundle", AllIcons.Actions.Upload);
        this.entryPoint = entryPoint;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        new ExportDialog(e.getProject(), entryPoint).show();
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
