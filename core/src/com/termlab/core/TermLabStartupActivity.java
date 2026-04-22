package com.termlab.core;
import com.intellij.ide.ProjectWindowCustomizerService;
import com.termlab.core.terminal.TermLabTabBarManager;
import com.termlab.core.workspace.WorkspaceManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ide.GeneralSettings;
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.UnindexedFilesScannerExecutor;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginAdvertiserServiceKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;

public final class TermLabStartupActivity implements ProjectActivity {
    private static final Color TERMLAB_PROJECT_COLOR = new Color(0x51, 0x64, 0x82);

    @Override
    public @Nullable Object execute(@NotNull Project project,
                                     @NotNull Continuation<? super Unit> continuation) {
        // Strip IDE-oriented project-level Editor settings pages (Code Style,
        // Inspections, File Templates, Inlay Hints, Reader Mode, File
        // Encodings, Auto Import). Project configurables live in the
        // project's extension area, so the app-level stripper in
        // TermLabEditorSettingsStripper can't reach them — do it here.
        TermLabThirdPartyLicenseFiles.ensurePresent();
        TermLabEditorSettingsStripper.stripProjectConfigurables(project);

        WorkspaceManager.getInstance(project).restore();

        // TermLab suppresses plugin/IDE suggestion notifications to keep the
        // terminal-first workflow focused and avoid advertiser background churn.
        PluginAdvertiserServiceKt.disableTryUltimate(project);

        // Disable indexing/scanning in TermLab regardless of workspace root to
        // prevent background IDE analysis from consuming memory over time.
        UnindexedFilesScannerExecutor scannerExecutor = UnindexedFilesScannerExecutor.getInstance(project);
        scannerExecutor.cancelAllTasksAndWait();
        scannerExecutor.suspendQueue();

        // TermLab has no code analysis workflow, so hide the editor's
        // inspection widget/traffic-light status chip globally.
        EditorSettingsExternalizable.getInstance().setShowInspectionWidget(false);

        // Quit the app when the last project window is closed (don't show welcome screen)
        GeneralSettings.getInstance().setShowWelcomeScreen(false);

        // If TermLab launches already in Distraction Free Mode while a single
        // tab is open, normalize the platform's saved "before" tab placement
        // so new terminal tabs don't get forced through a single hidden slot.
        TermLabTabBarManager.normalizeDistractionFreeModeTabPlacement();

        // Hide the tab bar when only one tab is open, but still allow tabs
        // to appear in Distraction Free Mode once multiple tabs exist.
        TermLabTabBarManager.schedulePreferredTabSettings(project);

        // Earlier TermLab builds saved console font settings into the
        // standalone app-level service, which makes Color Scheme -> Console
        // Font read-only. Re-apply the migration after project startup so it
        // wins over persisted option loading order.
        ApplicationManager.getApplication().invokeLater(TermLabConsoleFontCustomizer::applyConsoleFontSettings);

        // Keep the project/window accent stable across TermLab workspaces.
        ProjectWindowCustomizerService customizer =
            ApplicationManager.getApplication().getService(ProjectWindowCustomizerService.class);
        if (customizer != null) {
            ApplicationManager.getApplication().invokeLater(() ->
                customizer.setCustomProjectColor(project, TERMLAB_PROJECT_COLOR)
            );
        }

        return Unit.INSTANCE;
    }
}
