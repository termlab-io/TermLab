package com.termlab.runner.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.termlab.runner.config.FileConfigBinding;
import com.termlab.runner.config.InterpreterRegistry;
import com.termlab.runner.config.RunConfig;
import com.termlab.runner.config.RunConfigDialog;
import com.termlab.runner.config.RunConfigStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.UUID;

/**
 * Create or edit the run configuration associated with the active file.
 */
public final class EditConfigAction extends AnAction {

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(activeFile(e) != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile file = activeFile(e);
        if (project == null || file == null) {
            return;
        }

        String defaultInterpreter = InterpreterRegistry.interpreterForFile(file.getName());
        if (defaultInterpreter == null) {
            defaultInterpreter = "bash";
        }

        RunConfigStore configStore = ApplicationManager.getApplication().getService(RunConfigStore.class);
        FileConfigBinding binding = ApplicationManager.getApplication().getService(FileConfigBinding.class);

        UUID existingId = binding.getConfigId(file.getPath());
        RunConfig existing = existingId != null ? configStore.getById(existingId) : null;

        RunConfig edited = RunConfigDialog.show(
            project,
            existing,
            existing != null ? existing.name() : file.getName(),
            existing != null ? existing.hostId() : null,
            existing != null ? existing.interpreter() : defaultInterpreter
        );
        if (edited == null) {
            return;
        }

        if (existing == null) {
            configStore.add(edited);
        } else {
            configStore.update(edited);
        }
        binding.bind(file.getPath(), edited.id());
        saveSilently(configStore);
        saveSilently(binding);
    }

    private static void saveSilently(@NotNull RunConfigStore store) {
        try {
            store.save();
        } catch (IOException ignored) {
        }
    }

    private static void saveSilently(@NotNull FileConfigBinding binding) {
        try {
            binding.save();
        } catch (IOException ignored) {
        }
    }

    private static @Nullable VirtualFile activeFile(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return null;
        }
        FileEditor editor = FileEditorManager.getInstance(project).getSelectedEditor();
        return editor != null ? editor.getFile() : null;
    }
}
