package com.conch.core;

import com.conch.core.workspace.WorkspaceManager;
import com.intellij.ide.GeneralSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.UnindexedFilesScannerExecutor;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginAdvertiserServiceKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ConchStartupActivity implements ProjectActivity {

    @Override
    public @Nullable Object execute(@NotNull Project project,
                                     @NotNull Continuation<? super Unit> continuation) {
        // Strip IDE-oriented project-level Editor settings pages (Code Style,
        // Inspections, File Templates, Inlay Hints, Reader Mode, File
        // Encodings, Auto Import). Project configurables live in the
        // project's extension area, so the app-level stripper in
        // ConchEditorSettingsStripper can't reach them — do it here.
        ConchEditorSettingsStripper.stripProjectConfigurables(project);

        WorkspaceManager.getInstance(project).restore();

        // Conch suppresses plugin/IDE suggestion notifications to keep the
        // terminal-first workflow focused and avoid advertiser background churn.
        PluginAdvertiserServiceKt.disableTryUltimate(project);

        // Disable indexing/scanning in Conch regardless of workspace root to
        // prevent background IDE analysis from consuming memory over time.
        UnindexedFilesScannerExecutor scannerExecutor = UnindexedFilesScannerExecutor.getInstance(project);
        scannerExecutor.cancelAllTasksAndWait();
        scannerExecutor.suspendQueue();

        // Quit the app when the last project window is closed (don't show welcome screen)
        GeneralSettings.getInstance().setShowWelcomeScreen(false);

        return Unit.INSTANCE;
    }
}
