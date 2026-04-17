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
import com.intellij.openapi.util.DimensionService;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Dimension;
import java.lang.ref.WeakReference;

/**
 * Pops the current terminal tab out into its own editor window.
 *
 * <p>This mirrors the existing drag-to-detach workflow, but gives
 * TermLab a keyboard-first path for the same action.
 */
public final class PopOutTerminalTabAction extends AnAction {
    private static final String TERMINAL_POPOUT_DIMENSION_KEY = "termlab.terminal.popout";
    private static final Dimension DEFAULT_TERMINAL_POPOUT_SIZE = new Dimension(1100, 760);
    private static final Key<WeakReference<EditorWindow>> POP_OUT_SOURCE_WINDOW_KEY =
        Key.create("termlab.pop.out.source.window");

    @Override
    public void update(@NotNull AnActionEvent e) {
        TermLabTerminalVirtualFile termFile = resolveTerminalFile(e);
        EditorWindow currentWindow = resolveSourceWindow(e);
        EditorWindow sourceWindow = resolveStoredSourceWindow(termFile);
        boolean restoreAvailable = termFile != null
            && currentWindow != null
            && sourceWindow != null
            && sourceWindow != currentWindow;
        boolean enabled = termFile != null && currentWindow != null;
        e.getPresentation().setVisible(enabled);
        e.getPresentation().setEnabled(enabled);
        if (enabled) {
            if (restoreAvailable) {
                e.getPresentation().setText("Return Tab");
                e.getPresentation().setDescription("Move this terminal tab back to its previous split");
            } else {
                e.getPresentation().setText("Pop Out Tab");
                e.getPresentation().setDescription("Move this terminal tab into its own window");
            }
        }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        TermLabTerminalVirtualFile termFile = resolveTerminalFile(e);
        EditorWindow currentWindow = resolveSourceWindow(e);
        if (termFile == null || currentWindow == null) return;

        FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(project);
        EditorWindow previousWindow = resolveStoredSourceWindow(termFile);
        if (previousWindow != null && previousWindow != currentWindow) {
            restoreToPreviousWindow(termFile, currentWindow, previousWindow, manager);
            return;
        }

        FileEditorOpenRequest request = new FileEditorOpenRequest()
            .withOpenMode(FileEditorOpenMode.NEW_WINDOW)
            .withRequestFocus(true)
            .withSelectAsCurrent(true);

        configurePopOutWindowSize(project, termFile);
        storeSourceWindow(termFile, currentWindow);
        termFile.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, true);
        try {
            manager.openFile(termFile, request);
            if (!currentWindow.isDisposed()) {
                manager.closeFile(termFile, currentWindow);
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

    private static void restoreToPreviousWindow(@NotNull TermLabTerminalVirtualFile termFile,
                                                @NotNull EditorWindow currentWindow,
                                                @NotNull EditorWindow previousWindow,
                                                @NotNull FileEditorManagerEx manager) {
        FileEditorOpenRequest request = new FileEditorOpenRequest()
            .withTargetWindow(previousWindow)
            .withRequestFocus(true)
            .withSelectAsCurrent(true);

        termFile.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, true);
        try {
            manager.openFile(termFile, request);
            if (!currentWindow.isDisposed()) {
                manager.closeFile(termFile, currentWindow);
            }
            clearStoredSourceWindow(termFile);
        } finally {
            termFile.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, null);
        }
    }

    private static void storeSourceWindow(@NotNull TermLabTerminalVirtualFile termFile,
                                          @NotNull EditorWindow sourceWindow) {
        termFile.putUserData(POP_OUT_SOURCE_WINDOW_KEY, new WeakReference<>(sourceWindow));
    }

    static void configurePopOutWindowSize(@NotNull Project project,
                                          @NotNull TermLabTerminalVirtualFile termFile) {
        termFile.putUserData(FileEditorManagerKeys.WINDOW_DIMENSION_KEY, TERMINAL_POPOUT_DIMENSION_KEY);
        DimensionService.getInstance().setSize(
            TERMINAL_POPOUT_DIMENSION_KEY,
            DEFAULT_TERMINAL_POPOUT_SIZE,
            project
        );
    }

    private static void clearStoredSourceWindow(@NotNull TermLabTerminalVirtualFile termFile) {
        termFile.putUserData(POP_OUT_SOURCE_WINDOW_KEY, null);
    }

    private static @Nullable EditorWindow resolveStoredSourceWindow(@Nullable TermLabTerminalVirtualFile termFile) {
        if (termFile == null) return null;
        WeakReference<EditorWindow> ref = termFile.getUserData(POP_OUT_SOURCE_WINDOW_KEY);
        EditorWindow sourceWindow = ref == null ? null : ref.get();
        if (sourceWindow == null || sourceWindow.isDisposed()) {
            clearStoredSourceWindow(termFile);
            return null;
        }
        return sourceWindow;
    }
}
