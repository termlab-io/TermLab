package com.conch.core.actions;

import com.conch.core.workspace.WorkspaceManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class LoadWorkspaceAction extends AnAction implements DumbAware {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        WorkspaceManager manager = WorkspaceManager.getInstance(project);
        List<String> workspaces = manager.listWorkspaces();

        if (workspaces.isEmpty()) return;

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(workspaces)
            .setTitle("Load Workspace")
            .setItemChosenCallback(name -> {
                FileEditorManager editorManager = FileEditorManager.getInstance(project);
                for (VirtualFile file : editorManager.getOpenFiles()) {
                    editorManager.closeFile(file);
                }
                manager.restore(name + ".json");
            })
            .createPopup()
            .showCenteredInCurrentWindow(project);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
