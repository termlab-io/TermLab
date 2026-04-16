package com.termlab.core.actions;

import com.termlab.core.terminal.TermLabTerminalVirtualFile;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public final class CloseTerminalTabAction extends AnAction implements DumbAware {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        FileEditorManager manager = FileEditorManager.getInstance(project);
        VirtualFile selectedFile = manager.getSelectedEditor() != null
            ? manager.getSelectedEditor().getFile()
            : null;

        if (selectedFile instanceof TermLabTerminalVirtualFile) {
            manager.closeFile(selectedFile);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean enabled = false;
        if (project != null) {
            FileEditorManager manager = FileEditorManager.getInstance(project);
            var editor = manager.getSelectedEditor();
            enabled = editor != null && editor.getFile() instanceof TermLabTerminalVirtualFile;
        }
        e.getPresentation().setEnabledAndVisible(enabled);
    }
}
