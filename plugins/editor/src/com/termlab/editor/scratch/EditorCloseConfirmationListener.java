package com.termlab.editor.scratch;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.FileEditorManagerKeys;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Adds a lightweight save/discard/cancel prompt when TermLab editor tabs close.
 *
 * <p>Tab drag/reorder operations can temporarily close and reopen editors while
 * relocating them. To avoid prompting or tearing down scratch files during that
 * transient move, we mark the close in {@link #beforeFileClosed} and only act
 * after the close if the file is still actually closed on the next EDT turn.
 */
public final class EditorCloseConfirmationListener
    implements FileEditorManagerListener.Before, FileEditorManagerListener {

    @Override
    public void beforeFileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        Project project = source.getProject();
        if (project.isDisposed()) return;
        if (file.getUserData(ScratchMarker.SKIP_CLOSE_CONFIRMATION_KEY) == Boolean.TRUE) return;
        if (file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == Boolean.TRUE) return;
        if (file.getUserData(FileEditorManagerKeys.REOPEN_WINDOW) == Boolean.TRUE) return;
        if (file.getUserData(ScratchMarker.PRE_CLOSE_HANDLED_KEY) == Boolean.TRUE) return;

        FileDocumentManager documentManager = FileDocumentManager.getInstance();
        boolean isScratch = ScratchMarker.isMarkedScratch(file);
        boolean isModified = documentManager.isFileModified(file);
        if (!isModified && !isScratch) {
            return;
        }

        if (isModified) {
            Document document = documentManager.getDocument(file);
            if (document == null) {
                if (isScratch) {
                    throw new ProcessCanceledException();
                }
                return;
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
                throw new ProcessCanceledException();
            }

            if (choice == Messages.NO) {
                if (isScratch) {
                    markPreCloseHandled(file);
                } else {
                    ApplicationManager.getApplication().runWriteAction(() -> documentManager.reloadFromDisk(document));
                }
                return;
            }

            if (isScratch) {
                SaveAsHelper.saveAs(project, file);
                throw new ProcessCanceledException();
            }

            ApplicationManager.getApplication().runWriteAction(() -> documentManager.saveDocument(document));
            if (documentManager.isFileModified(file)) {
                throw new ProcessCanceledException();
            }
            return;
        }

        if (isScratch) {
            markPreCloseHandled(file);
        }
    }

    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        if (file.getUserData(ScratchMarker.PENDING_CLOSE_HANDLING_KEY) != Boolean.TRUE) {
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> handlePostClose(source, file));
    }

    @Override
    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        // No-op: we only care about the explicit close lifecycle.
    }

    private static void handlePostClose(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        boolean preCloseHandled = file.getUserData(ScratchMarker.PRE_CLOSE_HANDLED_KEY) == Boolean.TRUE;
        boolean wasModified = file.getUserData(ScratchMarker.PENDING_CLOSE_WAS_MODIFIED_KEY) == Boolean.TRUE;
        clearPendingCloseState(file);

        Project project = source.getProject();
        if (project.isDisposed()) return;
        if (source.isFileOpen(file)) return;
        if (file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == Boolean.TRUE) return;
        if (file.getUserData(FileEditorManagerKeys.REOPEN_WINDOW) == Boolean.TRUE) return;

        boolean isScratch = ScratchMarker.isMarkedScratch(file);
        FileDocumentManager documentManager = FileDocumentManager.getInstance();

        if (preCloseHandled) {
            if (isScratch) {
                deleteScratchAfterClose(file);
            }
            return;
        }

        if (!wasModified) {
            if (isScratch) {
                deleteScratchAfterClose(file);
            }
            return;
        }

        Document document = documentManager.getDocument(file);
        if (document == null) {
            if (isScratch) {
                reopenAfterClose(source, file);
            }
            return;
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
            reopenAfterClose(source, file);
            return;
        }

        if (choice == Messages.NO) {
            if (isScratch) {
                deleteScratchAfterClose(file);
            } else {
                ApplicationManager.getApplication().runWriteAction(() -> documentManager.reloadFromDisk(document));
            }
            return;
        }

        if (isScratch) {
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

    private static void markPreCloseHandled(@NotNull VirtualFile file) {
        file.putUserData(ScratchMarker.PRE_CLOSE_HANDLED_KEY, Boolean.TRUE);
        file.putUserData(ScratchMarker.PENDING_CLOSE_HANDLING_KEY, Boolean.TRUE);
        file.putUserData(ScratchMarker.PENDING_CLOSE_WAS_MODIFIED_KEY, Boolean.FALSE);
    }

    private static void clearPendingCloseState(@NotNull VirtualFile file) {
        file.putUserData(ScratchMarker.PENDING_CLOSE_HANDLING_KEY, null);
        file.putUserData(ScratchMarker.PENDING_CLOSE_WAS_MODIFIED_KEY, null);
        file.putUserData(ScratchMarker.PRE_CLOSE_HANDLED_KEY, null);
    }

    private static void deleteScratchAfterClose(@NotNull VirtualFile file) {
        ApplicationManager.getApplication().invokeLater(() ->
            ScratchMarker.deleteMarkedScratchFile(file, EditorCloseConfirmationListener.class));
    }
}
