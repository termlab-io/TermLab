package com.termlab.core.terminal;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * TermLab is terminal-only for now, so any non-terminal editor tabs are closed immediately.
 */
public final class TermLabTerminalOnlyFileEditorListener implements FileEditorManagerListener {
    @Override
    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        TermLabTabBarManager.applyPreferredTabSettings(source.getProject());

        if (file instanceof TermLabTerminalVirtualFile) {
            return;
        }

        if (!com.intellij.ide.plugins.PluginManagerCore.isDisabled(
                com.intellij.openapi.extensions.PluginId.getId("com.termlab.editor"))) {
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            source.closeFile(file);
        });
    }

    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        TermLabTabBarManager.schedulePreferredTabSettings(source.getProject());
    }
}
