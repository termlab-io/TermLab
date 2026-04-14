package com.conch.editor.remote;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Project-scoped listener that cleans up temp files when their
 * editor tabs close. Registered via {@code <projectListeners>}
 * so it's instantiated per project.
 */
public final class RemoteEditorProjectListener implements FileEditorManagerListener {

    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        Path closedPath = Paths.get(file.getPath()).toAbsolutePath();
        RemoteFileBindingRegistry registry = ApplicationManager.getApplication()
            .getService(RemoteFileBindingRegistry.class);
        if (registry == null) return;
        RemoteFileBinding binding = registry.remove(closedPath.toString());
        if (binding == null) return;
        RemoteEditService service = ApplicationManager.getApplication()
            .getService(RemoteEditService.class);
        if (service == null) return;
        RemoteEditorCleanup.deleteTempFileAndEmptyParents(
            binding.tempPath(), service.tempRoot());
    }
}
