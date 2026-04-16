package com.termlab.core.actions;

import com.termlab.core.terminal.TermLabTerminalVirtualFile;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerKeys;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.ex.FileEditorOpenMode;
import com.intellij.openapi.fileEditor.ex.FileEditorOpenRequest;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Pops the current terminal tab out into its own editor window.
 *
 * <p>This mirrors the existing drag-to-detach workflow, but gives
 * TermLab a keyboard-first path for the same action.
 */
public final class PopOutTerminalTabAction extends AnAction {

    @Override
    public void update(@NotNull AnActionEvent e) {
        TermLabTerminalVirtualFile termFile = resolveTerminalFile(e);
        boolean enabled = termFile != null && resolveSourceWindow(e) != null;
        e.getPresentation().setVisible(enabled);
        e.getPresentation().setEnabled(enabled);
        if (enabled) {
            e.getPresentation().setText("Pop Out Tab");
            e.getPresentation().setDescription("Move this terminal tab into its own window");
        }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        TermLabTerminalVirtualFile termFile = resolveTerminalFile(e);
        EditorWindow sourceWindow = resolveSourceWindow(e);
        if (termFile == null || sourceWindow == null) return;

        FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(project);
        FileEditorOpenRequest request = new FileEditorOpenRequest()
            .withOpenMode(FileEditorOpenMode.NEW_WINDOW)
            .withRequestFocus(true)
            .withSelectAsCurrent(true);

        termFile.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, true);
        try {
            manager.openFile(termFile, request);
            if (!sourceWindow.isDisposed()) {
                manager.closeFile(termFile, sourceWindow);
            }
        } finally {
            termFile.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, null);
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private static @Nullable TermLabTerminalVirtualFile resolveTerminalFile(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file instanceof TermLabTerminalVirtualFile term) {
            return term;
        }
        Project project = e.getProject();
        if (project == null) return null;
        VirtualFile[] selected = FileEditorManager.getInstance(project).getSelectedFiles();
        return selected.length > 0 && selected[0] instanceof TermLabTerminalVirtualFile term ? term : null;
    }

    private static @Nullable EditorWindow resolveSourceWindow(@NotNull AnActionEvent e) {
        EditorWindow window = e.getData(EditorWindow.DATA_KEY);
        if (window != null) {
            return window;
        }
        Project project = e.getProject();
        return project == null ? null : FileEditorManagerEx.getInstanceEx(project).getCurrentWindow();
    }
}
