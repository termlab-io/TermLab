package com.termlab.share.actions;

import com.termlab.share.ui.ImportDialog;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public final class ImportAction extends AnAction {

    public ImportAction() {
        super("Import TermLab Bundle...", "Import SSH hosts, tunnels, and credentials from a bundle", null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        new ImportDialog(e.getProject()).run();
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
