package com.termlab.core.actions;

import com.termlab.core.terminal.LocalPtySessionProvider;
import com.termlab.core.terminal.TermLabTerminalVirtualFile;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.ex.FileEditorOpenMode;
import com.intellij.openapi.fileEditor.ex.FileEditorOpenRequest;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Opens a brand-new local terminal directly in its own detached editor window.
 */
public final class NewTerminalPopOutAction extends AnAction implements DumbAware {
    private final LocalPtySessionProvider ptyProvider = new LocalPtySessionProvider();

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        TermLabTerminalVirtualFile terminalFile =
            new TermLabTerminalVirtualFile("Terminal", ptyProvider);
        PopOutTerminalTabAction.configurePopOutWindowSize(project, terminalFile);

        FileEditorOpenRequest request = new FileEditorOpenRequest()
            .withOpenMode(FileEditorOpenMode.NEW_WINDOW)
            .withRequestFocus(true)
            .withSelectAsCurrent(true);

        FileEditorManagerEx.getInstanceEx(project).openFile(terminalFile, request);
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
