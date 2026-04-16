package com.termlab.runner.actions;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.termlab.core.terminal.TermLabTerminalVirtualFile;
import com.termlab.runner.config.FileConfigBinding;
import com.termlab.runner.config.InterpreterRegistry;
import com.termlab.runner.config.RunConfig;
import com.termlab.runner.config.RunConfigDialog;
import com.termlab.runner.config.RunConfigStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import com.intellij.openapi.actionSystem.DataContext;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Toolbar combo-like entry point for the current file's run configuration.
 */
public final class ConfigDropdownAction extends ComboBoxAction {

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = activeFile(e);
        boolean enabled = file != null && isLightEditorFile(file);
        e.getPresentation().setVisible(true);
        e.getPresentation().setEnabled(enabled);
        if (!enabled) {
            e.getPresentation().setText("Quick Run");
            e.getPresentation().setDescription("Current run configuration");
            return;
        }

        RunConfig config = boundConfig(file.getPath());
        e.getPresentation().setText(config != null ? config.name() : "Quick Run");
        e.getPresentation().setDescription("Current run configuration");
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    protected @NotNull DefaultActionGroup createPopupActionGroup(
        @NotNull JComponent button,
        @NotNull DataContext context
    ) {
        DefaultActionGroup group = new DefaultActionGroup();
        Project project = CommonDataKeys.PROJECT.getData(context);
        VirtualFile file = CommonDataKeys.VIRTUAL_FILE.getData(context);
        if (project == null || file == null) {
            return group;
        }

        List<RunConfig> configs = configsForFile(file.getPath());
        for (RunConfig config : configs) {
            group.add(new SelectConfigAction(file.getPath(), config));
        }

        if (!configs.isEmpty()) {
            group.add(Separator.create());
        }
        group.add(new AddConfigurationAction(project, file));

        RunConfig current = boundConfig(file.getPath());
        if (current != null) {
            group.add(new EditCurrentConfigurationAction(project, file, current));
        }

        return group;
    }

    @Override
    protected boolean shouldShowDisabledActions() {
        return false;
    }

    private static @Nullable RunConfig boundConfig(@NotNull String path) {
        if (ApplicationManager.getApplication() == null) {
            return null;
        }
        FileConfigBinding binding = ApplicationManager.getApplication().getService(FileConfigBinding.class);
        RunConfigStore store = ApplicationManager.getApplication().getService(RunConfigStore.class);
        UUID id = binding.getConfigId(path);
        return id != null ? store.getById(id) : null;
    }

    private static @NotNull List<RunConfig> configsForFile(@NotNull String path) {
        if (ApplicationManager.getApplication() == null) {
            return List.of();
        }
        FileConfigBinding binding = ApplicationManager.getApplication().getService(FileConfigBinding.class);
        RunConfigStore store = ApplicationManager.getApplication().getService(RunConfigStore.class);
        return binding.getConfigIds(path).stream()
            .map(store::getById)
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    private static @Nullable VirtualFile activeFile(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return null;
        }
        FileEditor editor = FileEditorManager.getInstance(project).getSelectedEditor();
        return editor != null ? editor.getFile() : null;
    }

    private static boolean isLightEditorFile(@NotNull VirtualFile file) {
        return !(file instanceof TermLabTerminalVirtualFile);
    }

    private static final class SelectConfigAction extends DumbAwareAction {
        private final String filePath;
        private final RunConfig config;

        private SelectConfigAction(@NotNull String filePath, @NotNull RunConfig config) {
            super(config.name());
            this.filePath = filePath;
            this.config = config;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            FileConfigBinding binding = ApplicationManager.getApplication().getService(FileConfigBinding.class);
            binding.setActive(filePath, config.id());
            try {
                binding.save();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class AddConfigurationAction extends DumbAwareAction {
        private final Project project;
        private final VirtualFile file;

        private AddConfigurationAction(@NotNull Project project, @NotNull VirtualFile file) {
            super("Add Configuration...");
            this.project = project;
            this.file = file;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            String defaultInterpreter = InterpreterRegistry.interpreterForFile(file.getName());
            if (defaultInterpreter == null) {
                defaultInterpreter = "bash";
            }
            RunConfig created = RunConfigDialog.show(project, null, file.getName(), null, defaultInterpreter);
            if (created == null) {
                return;
            }

            RunConfigStore store = ApplicationManager.getApplication().getService(RunConfigStore.class);
            FileConfigBinding binding = ApplicationManager.getApplication().getService(FileConfigBinding.class);
            store.add(created);
            binding.bind(file.getPath(), created.id());
            try {
                store.save();
                binding.save();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class EditCurrentConfigurationAction extends DumbAwareAction {
        private final Project project;
        private final VirtualFile file;
        private final RunConfig current;

        private EditCurrentConfigurationAction(
            @NotNull Project project,
            @NotNull VirtualFile file,
            @NotNull RunConfig current
        ) {
            super("Edit Current Configuration...");
            this.project = project;
            this.file = file;
            this.current = current;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            String defaultInterpreter = InterpreterRegistry.interpreterForFile(file.getName());
            if (defaultInterpreter == null) {
                defaultInterpreter = current.interpreter();
            }
            RunConfig edited = RunConfigDialog.show(
                project,
                current,
                current.name(),
                current.hostId(),
                defaultInterpreter
            );
            if (edited == null) {
                return;
            }

            RunConfigStore store = ApplicationManager.getApplication().getService(RunConfigStore.class);
            FileConfigBinding binding = ApplicationManager.getApplication().getService(FileConfigBinding.class);
            store.update(edited);
            binding.setActive(file.getPath(), edited.id());
            try {
                store.save();
                binding.save();
            } catch (IOException ignored) {
            }
        }
    }
}
