package com.conch.editor.scratch;

import com.conch.core.filepicker.FilePickerResult;
import com.conch.core.filepicker.ui.UnifiedFilePickerDialog;
import com.conch.sftp.vfs.SftpUrl;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Save the active scratch file to a local directory or a remote SFTP
 * host via the unified file picker. Bound to Cmd+Alt+S / Ctrl+Alt+S.
 */
public final class SaveScratchToRemoteAction extends AnAction {

    private static final String NOTIFICATION_GROUP = "Conch SFTP";
    private static final String LAST_SOURCE_KEY = "conch.editor.lastRemoteSourceId";

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(canRun(e));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private static boolean canRun(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return false;
        return activeScratchFile(project) != null;
    }

    private static @Nullable VirtualFile activeScratchFile(@NotNull Project project) {
        FileEditor editor = FileEditorManager.getInstance(project).getSelectedEditor();
        if (editor == null) return null;
        VirtualFile file = editor.getFile();
        if (!(file instanceof LightVirtualFile lvf)) return null;
        if (lvf.getUserData(ScratchMarker.KEY) != Boolean.TRUE) return null;
        return file;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        VirtualFile scratch = activeScratchFile(project);
        if (scratch == null) return;

        FilePickerResult result = UnifiedFilePickerDialog.showSaveDialog(
            project,
            "Save Scratch",
            scratch.getName(),
            lastUsedSourceId());
        if (result == null) return;

        Document doc = FileDocumentManager.getInstance().getDocument(scratch);
        if (doc == null) return;
        byte[] bytes = doc.getText().getBytes(StandardCharsets.UTF_8);

        try {
            result.source().writeFile(result.absolutePath(), bytes);
        } catch (IOException ioe) {
            notifyError(project, "Save failed: " + ioe.getMessage());
            return;
        }

        rememberLastUsedSource(result.source().id());

        VirtualFile saved = resolveSavedVirtualFile(result);
        if (saved != null) {
            FileEditorManager mgr = FileEditorManager.getInstance(project);
            mgr.closeFile(scratch);
            mgr.openFile(saved, true);
        }

        notify(project,
            "Saved to " + result.source().label() + ":" + result.absolutePath(),
            NotificationType.INFORMATION);
    }

    private static @Nullable String lastUsedSourceId() {
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
            new Notification(NOTIFICATION_GROUP, "Save Scratch", message, type), project);
    }

    private static void notifyError(@NotNull Project project, @NotNull String message) {
        notify(project, message, NotificationType.ERROR);
    }
}
