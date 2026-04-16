package com.termlab.core.terminal;

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Provides dynamic tab titles for TermLab terminal tabs.
 * Returns the current title stored on the TermLabTerminalVirtualFile,
 * which is updated by OSC 0/2 escape sequences from the terminal.
 */
public final class TermLabEditorTabTitleProvider implements EditorTabTitleProvider {

    @Override
    public @Nullable String getEditorTabTitle(@NotNull Project project, @NotNull VirtualFile file) {
        if (file instanceof TermLabTerminalVirtualFile termFile) {
            String title = termFile.getTerminalTitle();
            if (title != null && !title.isBlank()) {
                return title;
            }
        }
        return null;
    }
}
