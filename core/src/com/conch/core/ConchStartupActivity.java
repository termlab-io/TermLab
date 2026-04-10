package com.conch.core;

import com.conch.core.settings.ConchTerminalConfig;
import com.conch.core.workspace.WorkspaceManager;
import com.intellij.ide.GeneralSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ConchStartupActivity implements ProjectActivity {

    private static final String[] UNWANTED_TOOL_WINDOWS = {
        "Problems View",
        "Structure",
        "Bookmarks",
    };

    @Override
    public @Nullable Object execute(@NotNull Project project,
                                     @NotNull Continuation<? super Unit> continuation) {
        WorkspaceManager.getInstance(project).restore();

        // Quit the app when the last project window is closed (don't show welcome screen)
        GeneralSettings.getInstance().setShowWelcomeScreen(false);

        ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindowManager twm = ToolWindowManager.getInstance(project);

            // Remove unwanted tool windows
            for (String id : UNWANTED_TOOL_WINDOWS) {
                if (twm.getToolWindow(id) != null) {
                    twm.unregisterToolWindow(id);
                }
            }

            // Restore Project View visibility from our own config
            ConchTerminalConfig config = ConchTerminalConfig.getInstance();
            ConchTerminalConfig.State state = config != null ? config.getState() : null;
            boolean shouldShow = state != null && state.projectViewVisible;

            ToolWindow projectView = twm.getToolWindow("Project");
            if (projectView != null) {
                if (shouldShow && !projectView.isVisible()) {
                    projectView.show();
                } else if (!shouldShow && projectView.isVisible()) {
                    projectView.hide();
                }
            }
        });

        return Unit.INSTANCE;
    }
}
