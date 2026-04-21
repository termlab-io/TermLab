package com.termlab.editor.scratch;

import com.termlab.core.filepicker.FilePickerResult;
import com.termlab.core.filepicker.FileSource;
import com.termlab.core.filepicker.ui.UnifiedFilePickerDialog;
import com.termlab.sftp.vfs.SftpUrl;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Shared Save As flow used by both the Save action (for unnamed
 * scratches) and the Save As action (for any file with a Document).
 * Opens the unified file picker, writes the document contents, closes
 * the scratch tab if appropriate, and opens the saved file.
 */
final class SaveAsHelper {

    private static final String NOTIFICATION_GROUP = "SFTP";
    private static final String LAST_SOURCE_KEY = "termlab.editor.lastRemoteSourceId";

    private SaveAsHelper() {}

    /**
     * Run the unified Save As flow for the given file. The file must
     * have a backing {@link Document}; if it is a marked TermLab scratch file,
     * its tab will be closed after a successful save and the new file opened in
     * its place.
     */
    static boolean saveAs(@NotNull Project project, @NotNull VirtualFile file) {
        Document doc = FileDocumentManager.getInstance().getDocument(file);
        if (doc == null) return false;

        FilePickerResult result = UnifiedFilePickerDialog.showSaveDialog(
            project,
            "Save As",
            file.getName(),
            lastUsedSourceId());
        if (result == null) return false;

        byte[] bytes = doc.getText().getBytes(StandardCharsets.UTF_8);
        // Dialog released its source reference on dispose; re-acquire for the write.
        FileSource source = result.source();
        Object writeToken = new Object();
        try {
            source.open(project, writeToken);
        } catch (IOException ioe) {
            notifyError(project, "Save failed: " + ioe.getMessage());
            return false;
        }
        try {
            source.writeFile(result.absolutePath(), bytes);
        } catch (IOException ioe) {
            notifyError(project, "Save failed: " + ioe.getMessage());
            return false;
        } finally {
            source.close(writeToken);
        }

        rememberLastUsedSource(result.source().id());

        VirtualFile saved = resolveSavedVirtualFile(result);
        FileEditorManager mgr = FileEditorManager.getInstance(project);
        boolean isScratch = ScratchMarker.isMarkedScratch(file);
        if (saved != null) {
            if (isScratch) {
                file.putUserData(ScratchMarker.SKIP_CLOSE_CONFIRMATION_KEY, Boolean.TRUE);
                mgr.closeFile(file);
                ScratchMarker.deleteMarkedScratchFile(file, SaveAsHelper.class);
                file.putUserData(ScratchMarker.SKIP_CLOSE_CONFIRMATION_KEY, null);
            }
            mgr.openFile(saved, true);
        }

        notify(project,
            "Saved to " + result.source().label() + ":" + result.absolutePath(),
            NotificationType.INFORMATION);
        return true;
    }

    /** Last-used source id, used by callers that preload the picker. */
    public static @Nullable String lastUsedSourceId() {
        return PropertiesComponent.getInstance().getValue(LAST_SOURCE_KEY);
    }

    private static void rememberLastUsedSource(@NotNull String id) {
        PropertiesComponent.getInstance().setValue(LAST_SOURCE_KEY, id);
    }

    /**
     * Convert a {@link FilePickerResult} into an IntelliJ
     * {@link VirtualFile} so the saved file opens as a proper editor
     * tab. Local files go through {@link LocalFileSystem}; SFTP files
     * go through the SFTP virtual filesystem.
     */
    private static @Nullable VirtualFile resolveSavedVirtualFile(@NotNull FilePickerResult result) {
        String id = result.source().id();
        if ("local".equals(id)) {
            return LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(Paths.get(result.absolutePath()));
        } else if (id.startsWith("sftp:")) {
            try {
                UUID hostId = UUID.fromString(id.substring("sftp:".length()));
                String url = SftpUrl.compose(hostId, result.absolutePath());
                return VirtualFileManager.getInstance().findFileByUrl(url);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private static void notify(@NotNull Project project, @NotNull String message, @NotNull NotificationType type) {
        Notifications.Bus.notify(
            new Notification(NOTIFICATION_GROUP, "Save As", message, type), project);
    }

    private static void notifyError(@NotNull Project project, @NotNull String message) {
        notify(project, message, NotificationType.ERROR);
    }
}
