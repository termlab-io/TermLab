package com.termlab.editor.scratch;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFilePreCloseCheck;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Prompts for unsaved TermLab scratches before the editor tab is closed. */
public final class ScratchFilePreCloseCheck implements VirtualFilePreCloseCheck {

    @Override
    public boolean canCloseFile(@NotNull VirtualFile file) {
        if (file.getUserData(ScratchMarker.SKIP_CLOSE_CONFIRMATION_KEY) == Boolean.TRUE) return true;
        if (file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == Boolean.TRUE) return true;
        if (file.getUserData(FileEditorManagerKeys.REOPEN_WINDOW) == Boolean.TRUE) return true;

        FileDocumentManager documentManager = FileDocumentManager.getInstance();
        boolean isScratch = ScratchMarker.isMarkedScratch(file);
        boolean isModified = documentManager.isFileModified(file);
        if (!isScratch && !isModified) {
            return true;
        }

        Project project = findProjectFor(file);
        if (project == null || project.isDisposed()) {
            return true;
        }

        if (!isModified) {
            markScratchCloseHandled(file);
            return true;
        }

        Document document = documentManager.getDocument(file);
        if (document == null) {
            return !isScratch;
        }

        int choice = Messages.showYesNoCancelDialog(
            project,
            buildPrompt(file),
            "Save Changes?",
            "Save",
            "Discard",
            "Cancel",
            Messages.getQuestionIcon()
        );

        if (choice == Messages.CANCEL) {
            return false;
        }

        if (choice == Messages.NO) {
            if (isScratch) {
                markScratchCloseHandled(file);
            } else {
                ApplicationManager.getApplication().runWriteAction(() -> documentManager.reloadFromDisk(document));
            }
            return true;
        }

        if (isScratch) {
            SaveAsHelper.saveAs(project, file);
            return false;
        }

        ApplicationManager.getApplication().runWriteAction(() -> documentManager.saveDocument(document));
        return !documentManager.isFileModified(file);
    }

    private static void markScratchCloseHandled(@NotNull VirtualFile file) {
        if (!ScratchMarker.isMarkedScratch(file)) return;
        file.putUserData(ScratchMarker.PRE_CLOSE_HANDLED_KEY, Boolean.TRUE);
        file.putUserData(ScratchMarker.PENDING_CLOSE_HANDLING_KEY, Boolean.TRUE);
        file.putUserData(ScratchMarker.PENDING_CLOSE_WAS_MODIFIED_KEY, Boolean.FALSE);
    }

    private static @Nullable Project findProjectFor(@NotNull VirtualFile file) {
        Project fallback = null;
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        for (Project project : projects) {
            if (project.isDisposed()) continue;
            if (fallback == null) fallback = project;
            if (FileEditorManager.getInstance(project).isFileOpen(file)) {
                return project;
            }
        }
        return projects.length == 1 ? fallback : null;
    }

    private static @NotNull String buildPrompt(@NotNull VirtualFile file) {
        return "Save changes to " + file.getName() + " before closing?";
    }
}
