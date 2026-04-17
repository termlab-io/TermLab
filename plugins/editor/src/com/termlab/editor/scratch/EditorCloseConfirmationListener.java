package com.termlab.editor.scratch;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Adds a lightweight save/discard/cancel prompt when TermLab editor tabs close.
 *
 * <p>The platform tab close button bypasses the standard pre-close checks, so
 * we hook {@link FileEditorManagerListener.Before#beforeFileClosed} instead and
 * emulate the expected editor behavior there.
 */
public final class EditorCloseConfirmationListener implements FileEditorManagerListener.Before {

    @Override
    public void beforeFileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        Project project = source.getProject();
        if (project.isDisposed()) return;
        if (file.getUserData(ScratchMarker.SKIP_CLOSE_CONFIRMATION_KEY) == Boolean.TRUE) return;

        FileDocumentManager documentManager = FileDocumentManager.getInstance();
        if (!documentManager.isFileModified(file)) return;

        Document document = documentManager.getDocument(file);
        if (document == null) return;

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
            reopenAfterClose(source, file);
            return;
        }

        if (choice == Messages.NO) {
            if (!(file instanceof LightVirtualFile && file.getUserData(ScratchMarker.KEY) == Boolean.TRUE)) {
                ApplicationManager.getApplication().runWriteAction(() -> documentManager.reloadFromDisk(document));
            }
            return;
        }

        if (file instanceof LightVirtualFile && file.getUserData(ScratchMarker.KEY) == Boolean.TRUE) {
            if (!SaveAsHelper.saveAs(project, file)) {
                reopenAfterClose(source, file);
            }
            return;
        }

        ApplicationManager.getApplication().runWriteAction(() -> documentManager.saveDocument(document));
        if (documentManager.isFileModified(file)) {
            reopenAfterClose(source, file);
        }
    }

    private static void reopenAfterClose(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!source.getProject().isDisposed()) {
                source.openFile(file, true);
            }
        });
    }

    private static @NotNull String buildPrompt(@NotNull VirtualFile file) {
        return "Save changes to " + file.getName() + " before closing?";
    }
}
