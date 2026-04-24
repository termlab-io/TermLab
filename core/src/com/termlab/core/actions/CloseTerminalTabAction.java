package com.termlab.core.actions;

import com.termlab.core.terminal.TermLabTerminalVirtualFile;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public final class CloseTerminalTabAction extends AnAction implements DumbAware {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        EditorWindow window = e.getData(EditorWindow.DATA_KEY);
        if (window == null) {
            window = FileEditorManagerEx.getInstanceEx(project).getCurrentWindow();
        }
        if (window == null) return;

        VirtualFile selectedFile = window.getSelectedFile();
        if (selectedFile instanceof TermLabTerminalVirtualFile) {
            window.closeFile(selectedFile);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean enabled = false;
        if (project != null) {
            EditorWindow window = e.getData(EditorWindow.DATA_KEY);
            if (window == null) {
                window = FileEditorManagerEx.getInstanceEx(project).getCurrentWindow();
            }
            enabled = window != null && window.getSelectedFile() instanceof TermLabTerminalVirtualFile;
        }
        e.getPresentation().setEnabledAndVisible(enabled);
    }
}
