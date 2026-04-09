package com.conch.core;

import com.conch.core.workspace.WorkspaceManager;
import com.intellij.ide.GeneralSettings;
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
    };

    @Override
    public @Nullable Object execute(@NotNull Project project,
                                     @NotNull Continuation<? super Unit> continuation) {
        WorkspaceManager.getInstance(project).restore();

        // Bug 6: Do not show the welcome screen when the last project window is closed
        GeneralSettings.getInstance().setShowWelcomeScreen(false);

        // Bug 4: Remove unwanted tool windows (e.g. Problems, Structure)
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
