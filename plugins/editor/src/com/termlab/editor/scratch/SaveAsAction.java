package com.termlab.editor.scratch;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Save As… action. Always opens the unified file picker for the
 * currently-selected editor's file, regardless of whether the file is
 * a scratch or a real file. Bound to Cmd+Alt+S / Ctrl+Alt+S.
 */
public final class SaveAsAction extends AnAction {

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(activeFile(e) != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private static @Nullable VirtualFile activeFile(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return null;
        FileEditor editor = FileEditorManager.getInstance(project).getSelectedEditor();
        if (editor == null) return null;
        VirtualFile file = editor.getFile();
        if (file == null) return null;
        if (FileDocumentManager.getInstance().getDocument(file) == null) return null;
        return file;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        VirtualFile file = activeFile(e);
        if (file == null) return;
        SaveAsHelper.saveAs(project, file);
    }
}
