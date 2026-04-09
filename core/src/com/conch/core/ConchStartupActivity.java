package com.conch.core;

import com.conch.core.workspace.WorkspaceManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.wm.ToolWindowManager;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ConchStartupActivity implements ProjectActivity {

    /**
     * Tool window IDs that should not appear in the Conch workstation.
     */
    private static final String[] UNWANTED_TOOL_WINDOWS = {
        "Problems View",
        "Structure",
        "Bookmarks",
    };

    @Override
    public @Nullable Object execute(@NotNull Project project,
                                     @NotNull Continuation<? super Unit> continuation) {
        WorkspaceManager.getInstance(project).restore();

        // Remove unwanted tool windows
        ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindowManager twm = ToolWindowManager.getInstance(project);
            for (String id : UNWANTED_TOOL_WINDOWS) {
                if (twm.getToolWindow(id) != null) {
                    twm.unregisterToolWindow(id);
                }
            }
        });

        return Unit.INSTANCE;
    }
}
