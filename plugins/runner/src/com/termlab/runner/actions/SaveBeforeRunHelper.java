package com.termlab.runner.actions;

import com.termlab.core.filepicker.FilePickerResult;
import com.termlab.core.filepicker.FileSource;
import com.termlab.core.filepicker.ui.UnifiedFilePickerDialog;
import com.termlab.editor.scratch.ScratchMarker;
import com.termlab.sftp.vfs.SftpUrl;
import com.termlab.sftp.vfs.SftpVirtualFile;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
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
 * Ensures a file has a stable local or remote path before execution.
 */
final class SaveBeforeRunHelper {

    private SaveBeforeRunHelper() {}

    record RunTarget(@NotNull String scriptPath, @Nullable UUID sftpHostId) {
        boolean isLocal() {
            return sftpHostId == null;
        }
    }

    static @Nullable RunTarget resolve(@NotNull Project project, @NotNull VirtualFile file) {
        if (file instanceof LightVirtualFile lightVirtualFile
            && lightVirtualFile.getUserData(ScratchMarker.KEY) == Boolean.TRUE) {
            VirtualFile saved = saveScratch(project, lightVirtualFile);
            if (saved == null) {
                return null;
            }
            file = saved;
        }

        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
            ApplicationManager.getApplication().runWriteAction(() ->
                FileDocumentManager.getInstance().saveDocument(document));
        }

        if (file instanceof SftpVirtualFile sftpFile) {
            SftpUrl url = SftpUrl.parse(sftpFile.getUrl());
            if (url == null) {
                return null;
            }
            return new RunTarget(url.remotePath(), url.hostId());
        }

        return new RunTarget(file.getPath(), null);
    }

    private static @Nullable VirtualFile saveScratch(@NotNull Project project, @NotNull LightVirtualFile file) {
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            return null;
        }

        FilePickerResult result = UnifiedFilePickerDialog.showSaveDialog(project, "Save As", file.getName(), null);
        if (result == null) {
            return null;
        }

        byte[] bytes = document.getText().getBytes(StandardCharsets.UTF_8);
        FileSource source = result.source();
        Object writeToken = new Object();
        try {
            source.open(project, writeToken);
            source.writeFile(result.absolutePath(), bytes);
        } catch (IOException e) {
            return null;
        } finally {
            source.close(writeToken);
        }

        VirtualFile saved = resolveSavedVirtualFile(result);
        if (saved != null) {
            FileEditorManager manager = FileEditorManager.getInstance(project);
            manager.closeFile(file);
            manager.openFile(saved, true);
        }
        return saved;
    }

    private static @Nullable VirtualFile resolveSavedVirtualFile(@NotNull FilePickerResult result) {
        String id = result.source().id();
        if ("local".equals(id)) {
            return LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(Paths.get(result.absolutePath()));
        }
        if (id.startsWith("sftp:")) {
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
}
