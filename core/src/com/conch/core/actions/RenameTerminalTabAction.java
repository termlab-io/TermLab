package com.conch.core.actions;

import com.conch.core.terminal.ConchTerminalVirtualFile;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidatorEx;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Renames a Conch terminal tab. Shown in the editor tab context menu
 * (replacing all the default file-oriented entries in
 * {@code EditorTabPopupMenu}) and bound to {@code F2} as a keyboard
 * shortcut while a terminal tab has focus.
 *
 * <p>Renaming sets {@link ConchTerminalVirtualFile#setManualTitleOverride(boolean)}
 * so subsequent OSC 0/2 title updates from the shell don't clobber the
 * user's choice. Renaming to an empty string clears the override and
 * restores shell-driven titles.
 */
public final class RenameTerminalTabAction extends AnAction {

    @Override
    public void update(@NotNull AnActionEvent e) {
        ConchTerminalVirtualFile termFile = resolveTerminalFile(e);
        e.getPresentation().setVisible(termFile != null);
        e.getPresentation().setEnabled(termFile != null);
        if (termFile != null) {
            e.getPresentation().setText("Rename Tab…");
            e.getPresentation().setDescription("Rename this terminal tab");
        }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        ConchTerminalVirtualFile termFile = resolveTerminalFile(e);
        if (termFile == null) return;

        String current = termFile.getTerminalTitle();
        String initial = current == null ? "" : current;

        String newTitle = Messages.showInputDialog(
            project,
            "New tab name (leave blank to restore the automatic title from the shell):",
            "Rename Terminal Tab",
            null,
            initial,
            new RenameInputValidator()
        );

        if (newTitle == null) return;  // cancelled

        String trimmed = newTitle.trim();
        if (trimmed.isEmpty()) {
            termFile.setTerminalTitle(null);
            termFile.setManualTitleOverride(false);
        } else {
            termFile.setTerminalTitle(trimmed);
            termFile.setManualTitleOverride(true);
        }

        FileEditorManager.getInstance(project).updateFilePresentation(termFile);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /**
     * Find the terminal file that "this action applies to":
     * <ul>
     *   <li>first try {@link CommonDataKeys#VIRTUAL_FILE} — populated when
     *       the user right-clicks a specific tab</li>
     *   <li>fall back to the currently-selected file editor — populated
     *       for the keyboard shortcut path when a tab has focus</li>
     * </ul>
     */
    private static @Nullable ConchTerminalVirtualFile resolveTerminalFile(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file instanceof ConchTerminalVirtualFile term) {
            return term;
        }
        Project project = e.getProject();
        if (project == null) return null;
        VirtualFile[] files = FileEditorManager.getInstance(project).getSelectedFiles();
        if (files.length > 0 && files[0] instanceof ConchTerminalVirtualFile term) {
            return term;
        }
        // Defensive: use PlatformDataKeys in case VIRTUAL_FILE is missing
        // but the editor context still exposes a file some other way.
        VirtualFile alt = e.getData(PlatformDataKeys.LAST_ACTIVE_FILE_EDITOR) != null
            ? e.getData(PlatformDataKeys.LAST_ACTIVE_FILE_EDITOR).getFile()
            : null;
        if (alt instanceof ConchTerminalVirtualFile term) {
            return term;
        }
        return null;
    }

    private static final class RenameInputValidator implements InputValidatorEx {
        @Override
        public boolean checkInput(@Nullable String inputString) {
            // Allow any input including empty (which clears the override).
            return inputString != null;
        }

        @Override
        public boolean canClose(@Nullable String inputString) {
            return checkInput(inputString);
        }

        @Override
        public @Nullable String getErrorText(@Nullable String inputString) {
            return null;
        }
    }
}
