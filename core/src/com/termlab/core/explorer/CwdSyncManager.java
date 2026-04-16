package com.termlab.core.explorer;

import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.PROJECT)
public final class CwdSyncManager {
    private static final Logger LOG = Logger.getInstance(CwdSyncManager.class);
    private final Project project;
    private String lastSyncedPath;

    public CwdSyncManager(@NotNull Project project) {
        this.project = project;
    }

    public static CwdSyncManager getInstance(@NotNull Project project) {
        return project.getService(CwdSyncManager.class);
    }

    public void onWorkingDirectoryChanged(@Nullable String path) {
        if (path == null || path.equals(lastSyncedPath)) return;
        lastSyncedPath = path;

        ApplicationManager.getApplication().invokeLater(() -> {
            VirtualFile dir = LocalFileSystem.getInstance().findFileByPath(path);
            if (dir != null && dir.isDirectory()) {
                ProjectView projectView = ProjectView.getInstance(project);
                projectView.select(null, dir, true);
                LOG.info("CWD synced to: " + path);
            }
        });
    }
}
