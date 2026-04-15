package com.conch.editor.scratch;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Save the document of the currently-selected editor. Conch strips the
 * platform's built-in {@code SaveAll} action (via
 * {@code ConchToolbarStripper}), which takes the {@code Cmd+S} /
 * {@code Ctrl+S} keybinding with it. This action replaces it with a
 * focused "save the current file" flow.
 *
 * <p>For marked scratch {@link LightVirtualFile}s the save is routed
 * through {@link SaveAsHelper}, which opens the unified file picker so
 * the user can pick a local or remote destination. For all other files
 * (local or SFTP-backed via the VFS), the default save path writes
 * through {@link FileDocumentManager#saveDocument}.
 */
public final class SaveCurrentFileAction extends AnAction {

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
        return editor.getFile();
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        VirtualFile file = activeFile(e);
        if (file == null) return;
        if (file instanceof LightVirtualFile lvf
            && lvf.getUserData(ScratchMarker.KEY) == Boolean.TRUE) {
            SaveAsHelper.saveAs(project, lvf);
            return;
        }
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) return;
        // Wrap in a write action because saveDocument expects write access
        // (the platform's default save path runs inside runWriteAction for
        // the same reason — see FileDocumentManagerImpl.saveDocument).
        ApplicationManager.getApplication().runWriteAction(() -> {
            FileDocumentManager.getInstance().saveDocument(document);
        });
    }
}
