package com.termlab.core.actions;

import com.termlab.core.terminal.TermLabTerminalVirtualFile;
import com.termlab.core.terminal.LocalPtySessionProvider;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public final class SplitTerminalHorizontalAction extends AnAction implements DumbAware {
    private final LocalPtySessionProvider ptyProvider = new LocalPtySessionProvider();

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(project);
        EditorWindow current = e.getData(EditorWindow.DATA_KEY);
        if (current == null) {
            current = manager.getCurrentWindow();
        }
        if (current == null) return;

        TermLabTerminalVirtualFile terminalFile = new TermLabTerminalVirtualFile("Terminal", ptyProvider);
        EditorWindow newWindow = current.split(
            JSplitPane.HORIZONTAL_SPLIT,
            true,
            terminalFile,
            true,
            true,
            true
        );
        TerminalSplitSupport.normalizeNewSplit(newWindow);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
