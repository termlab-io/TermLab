package com.termlab.core.actions;

import com.termlab.core.terminal.TermLabTerminalVirtualFile;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class MultiExecActionSupport {
    private static final String SSH_PROVIDER_ID = "com.termlab.ssh";

    private MultiExecActionSupport() {
    }

    static @Nullable TermLabTerminalVirtualFile resolveTerminalFile(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file instanceof TermLabTerminalVirtualFile term) {
            return term;
        }

        Project project = e.getProject();
        if (project == null) {
            return null;
        }

        VirtualFile[] selected = FileEditorManager.getInstance(project).getSelectedFiles();
        if (selected.length > 0 && selected[0] instanceof TermLabTerminalVirtualFile term) {
            return term;
        }

        VirtualFile alt = e.getData(PlatformDataKeys.LAST_ACTIVE_FILE_EDITOR) != null
            ? e.getData(PlatformDataKeys.LAST_ACTIVE_FILE_EDITOR).getFile()
            : null;
        if (alt instanceof TermLabTerminalVirtualFile term) {
            return term;
        }
        return null;
    }

    static boolean isSshTerminal(@Nullable TermLabTerminalVirtualFile file) {
        return file != null && SSH_PROVIDER_ID.equals(file.getProvider().getId());
    }
}
