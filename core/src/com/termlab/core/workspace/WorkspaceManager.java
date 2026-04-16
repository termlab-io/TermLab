package com.termlab.core.workspace;

import com.termlab.core.terminal.TermLabTerminalVirtualFile;
import com.termlab.core.terminal.LocalPtySessionProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.PROJECT)
public final class WorkspaceManager {
    private static final Logger LOG = Logger.getInstance(WorkspaceManager.class);
    private static final Path WORKSPACES_DIR = Path.of(
        System.getProperty("user.home"), ".config", "termlab", "workspaces"
    );
    private static final String DEFAULT_WORKSPACE = "default.json";

    private final Project project;
    private String activeWorkspaceName = "default";

    public WorkspaceManager(@NotNull Project project) {
        this.project = project;
    }

    public static WorkspaceManager getInstance(@NotNull Project project) {
        return project.getService(WorkspaceManager.class);
    }

    public @NotNull WorkspaceState captureState() {
        FileEditorManager editorManager = FileEditorManager.getInstance(project);
        List<WorkspaceState.TabState> tabs = new ArrayList<>();

        for (VirtualFile file : editorManager.getOpenFiles()) {
            if (file instanceof TermLabTerminalVirtualFile termFile) {
                tabs.add(new WorkspaceState.TabState(
                    termFile.getSessionId(),
                    termFile.getName(),
                    termFile.getProvider().getId(),
                    termFile.getCurrentWorkingDirectory(),
                    null
                ));
            }
        }
        return new WorkspaceState(activeWorkspaceName, tabs);
    }

    public void save() {
        try {
            Files.createDirectories(WORKSPACES_DIR);
            WorkspaceState state = captureState();
            String json = WorkspaceSerializer.toJson(state);
            Path file = WORKSPACES_DIR.resolve(activeWorkspaceName + ".json");

            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            LOG.info("Workspace saved: " + file);
        } catch (IOException e) {
            LOG.error("Failed to save workspace", e);
        }
    }

    public void restore() {
        restore(DEFAULT_WORKSPACE);
    }

    public void restore(@NotNull String fileName) {
        Path file = WORKSPACES_DIR.resolve(fileName);
        if (!Files.exists(file)) {
            openDefaultTerminal();
            return;
        }

        try {
            String json = Files.readString(file);
            WorkspaceState state = WorkspaceSerializer.fromJson(json);
            activeWorkspaceName = state.name();

            if (state.tabs().isEmpty()) {
                openDefaultTerminal();
                return;
            }

            // FileEditorManager.openFile must be called on EDT
            ApplicationManager.getApplication().invokeLater(() -> {
                LocalPtySessionProvider ptyProvider = new LocalPtySessionProvider();
                FileEditorManager editorManager = FileEditorManager.getInstance(project);

                for (WorkspaceState.TabState tab : state.tabs()) {
                    if ("com.termlab.local-pty".equals(tab.providerId())) {
                        TermLabTerminalVirtualFile termFile =
                            new TermLabTerminalVirtualFile(tab.title(), ptyProvider);
                        termFile.setCurrentWorkingDirectory(tab.workingDirectory());
                        editorManager.openFile(termFile, false);
                    }
                }
            });
        } catch (IOException e) {
            LOG.error("Failed to restore workspace", e);
            openDefaultTerminal();
        }
    }

    public void saveAs(@NotNull String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9_\\-. ]", "");
        if (sanitized.isBlank()) return;
        activeWorkspaceName = sanitized;
        save();
    }

    public @NotNull List<String> listWorkspaces() {
        List<String> names = new ArrayList<>();
        try {
            if (Files.exists(WORKSPACES_DIR)) {
                try (var stream = Files.list(WORKSPACES_DIR)) {
                    stream.filter(p -> p.toString().endsWith(".json"))
                        .forEach(p -> {
                            String fn = p.getFileName().toString();
                            names.add(fn.substring(0, fn.length() - 5));
                        });
                }
            }
        } catch (IOException e) {
            LOG.error("Failed to list workspaces", e);
        }
        return names;
    }

    private void openDefaultTerminal() {
        ApplicationManager.getApplication().invokeLater(() -> {
            LocalPtySessionProvider ptyProvider = new LocalPtySessionProvider();
            TermLabTerminalVirtualFile termFile =
                new TermLabTerminalVirtualFile("Terminal", ptyProvider);
            FileEditorManager.getInstance(project).openFile(termFile, true);
        });
    }
}
