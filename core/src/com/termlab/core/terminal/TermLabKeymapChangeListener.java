package com.termlab.core.terminal;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Refreshes editor tab presentations when the keymap changes so that
 * shortcut hints appended by {@link TermLabEditorTabTitleProvider}
 * reflect the user's currently bound keys.
 */
public final class TermLabKeymapChangeListener implements KeymapManagerListener {

    @Override
    public void activeKeymapChanged(@Nullable Keymap keymap) {
        refreshAllTabTitles();
    }

    @Override
    public void shortcutsChanged(@NotNull Keymap keymap,
                                 @NotNull Collection<String> actionIds,
                                 boolean fromSettings) {
        for (String id : actionIds) {
            if (id != null && id.startsWith(TermLabTabNumberSupport.SELECT_TAB_ACTION_ID_PREFIX)) {
                refreshAllTabTitles();
                return;
            }
        }
    }

    private static void refreshAllTabTitles() {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (project.isDisposed()) continue;
                FileEditorManager manager = FileEditorManager.getInstance(project);
                for (VirtualFile file : manager.getOpenFiles()) {
                    manager.updateFilePresentation(file);
                }
            }
        });
    }
}
