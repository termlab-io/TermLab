package com.termlab.core.terminal;

import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class TermLabTabNumberSupport {
    public static final int MAX_SHORTCUT_TABS = 9;

    private TermLabTabNumberSupport() {}

    public static @Nullable EditorWindow resolveWindow(@NotNull Project project,
                                                       @Nullable EditorWindow preferredWindow,
                                                       @Nullable VirtualFile file) {
        if (preferredWindow != null && !preferredWindow.isDisposed() && containsFile(preferredWindow, file)) {
            return preferredWindow;
        }

        FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(project);
        EditorWindow currentWindow = manager.getCurrentWindow();
        if (currentWindow != null && !currentWindow.isDisposed() && containsFile(currentWindow, file)) {
            return currentWindow;
        }

        EditorWindow fallback = null;
        for (EditorWindow window : manager.getWindows()) {
            if (window == null || window.isDisposed()) {
                continue;
            }
            if (fallback == null) {
                fallback = window;
            }
            if (containsFile(window, file)) {
                return window;
            }
        }
        return fallback;
    }

    public static int findTabIndex(@NotNull EditorWindow window, @NotNull VirtualFile file) {
        VirtualFile[] files = window.getFiles();
        for (int i = 0; i < files.length; i++) {
            if (file.equals(files[i])) {
                return i;
            }
        }
        return -1;
    }

    public static @Nullable String getShortcutHint(@NotNull Project project,
                                                   @Nullable EditorWindow preferredWindow,
                                                   @NotNull VirtualFile file) {
        EditorWindow window = resolveWindow(project, preferredWindow, file);
        if (window == null) {
            return null;
        }

        int tabIndex = findTabIndex(window, file);
        if (tabIndex < 0 || tabIndex >= MAX_SHORTCUT_TABS) {
            return null;
        }

        return formatShortcutHint(tabIndex + 1);
    }

    public static @NotNull String formatShortcutHint(int tabNumber) {
        return SystemInfo.isMac ? "\u2318" + tabNumber : "Ctrl+" + tabNumber;
    }

    private static boolean containsFile(@NotNull EditorWindow window, @Nullable VirtualFile file) {
        return file == null || window.isFileOpen(file);
    }
}
