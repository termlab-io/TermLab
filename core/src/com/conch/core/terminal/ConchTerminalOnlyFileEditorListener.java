package com.conch.core.terminal;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Conch is terminal-only for now, so any non-terminal editor tabs are closed immediately.
 */
public final class ConchTerminalOnlyFileEditorListener implements FileEditorManagerListener {
    @Override
    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        if (file instanceof ConchTerminalVirtualFile) {
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            source.closeFile(file);
        });
    }
}
