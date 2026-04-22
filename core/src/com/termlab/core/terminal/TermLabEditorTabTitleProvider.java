package com.termlab.core.terminal;

import com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil;
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class TermLabEditorTabTitleProvider implements EditorTabTitleProvider {
    private static final String HINT_SEPARATOR = "  ";
    private static final ThreadLocal<Boolean> COMPUTING_BASE = new ThreadLocal<>();

    @Override
    public @Nullable String getEditorTabTitle(@NotNull Project project, @NotNull VirtualFile file) {
        if (Boolean.TRUE.equals(COMPUTING_BASE.get())) {
            return terminalTitleOrNull(file);
        }

        String hint = TermLabTabNumberSupport.getShortcutHint(project, null, file);
        if (hint == null) {
            return terminalTitleOrNull(file);
        }

        String baseTitle;
        COMPUTING_BASE.set(Boolean.TRUE);
        try {
            baseTitle = EditorTabPresentationUtil.getEditorTabTitle(project, file);
        } finally {
            COMPUTING_BASE.remove();
        }

        if (baseTitle == null || baseTitle.isBlank()) {
            return hint;
        }
        return baseTitle + HINT_SEPARATOR + hint;
    }

    private static @Nullable String terminalTitleOrNull(@NotNull VirtualFile file) {
        if (file instanceof TermLabTerminalVirtualFile termFile) {
            String title = termFile.getTerminalTitle();
            if (title != null && !title.isBlank()) {
                return title;
            }
        }
        return null;
    }
}
