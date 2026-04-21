package com.termlab.core.terminal;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * TermLab is terminal-only for now, so any non-terminal editor tabs are closed immediately.
 */
public final class TermLabTerminalOnlyFileEditorListener implements FileEditorManagerListener {
    private static final Dimension DEFAULT_TERMINAL_POPOUT_SIZE = new Dimension(1100, 760);

    @Override
    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        TermLabTabBarManager.applyPreferredTabSettings(source.getProject());

        if (file instanceof TermLabTerminalVirtualFile) {
            scheduleConsistentFloatingWindowSize(source, file);
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

    @Override
    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        TermLabTabBarManager.schedulePreferredTabSettings(event.getManager().getProject());
    }

    private static void scheduleConsistentFloatingWindowSize(@NotNull FileEditorManager source,
                                                             @NotNull VirtualFile file) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (source.getProject().isDisposed()) {
                return;
            }

            FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(source.getProject());
            FileEditor editor = source.getSelectedEditor(file);
            if (editor == null) {
                return;
            }

            JComponent component = editor.getComponent();
            var splitters = manager.getSplittersFor(component);
            if (splitters == null || !splitters.isFloating()) {
                return;
            }
            if (manager.getSiblings(file).size() != 1) {
                return;
            }

            Window window = SwingUtilities.getWindowAncestor(component);
            if (window == null) {
                return;
            }
            window.setSize(DEFAULT_TERMINAL_POPOUT_SIZE);
        });
    }
}
