package com.conch.core.actions;

import com.conch.core.terminal.ConchTerminalVirtualFile;
import com.conch.core.terminal.LocalPtySessionProvider;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class SplitTerminalHorizontalAction extends AnAction implements DumbAware {
    private final LocalPtySessionProvider ptyProvider = new LocalPtySessionProvider();

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(project);
        manager.createSplitter(javax.swing.SwingConstants.VERTICAL, manager.getCurrentWindow());

        ConchTerminalVirtualFile terminalFile =
            new ConchTerminalVirtualFile("Terminal", ptyProvider);
        manager.openFile(terminalFile, true);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
