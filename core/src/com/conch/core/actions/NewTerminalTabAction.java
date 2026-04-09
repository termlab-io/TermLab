package com.conch.core.actions;

import com.conch.core.terminal.ConchTerminalVirtualFile;
import com.conch.core.terminal.LocalPtySessionProvider;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class NewTerminalTabAction extends AnAction implements DumbAware {

    private final LocalPtySessionProvider ptyProvider = new LocalPtySessionProvider();

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        ConchTerminalVirtualFile terminalFile =
            new ConchTerminalVirtualFile("Terminal", ptyProvider);
        FileEditorManager.getInstance(project).openFile(terminalFile, true);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
