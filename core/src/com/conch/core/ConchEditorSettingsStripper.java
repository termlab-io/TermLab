package com.conch.core;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.ExtensionsArea;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurableEP;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Strips the IDE's "Editor" settings subtree down to the pages that actually
 * affect Conch's terminal view.
 *
 * <p>Conch is a terminal workstation, not an IDE — so the bulk of the
 * platform's Editor preferences (Code Editing, Smart Keys, Tabs, Inspections,
 * Code Style, File Templates, File Types, Minimap, Breadcrumbs, Sticky Lines,
 * Intentions, Live Templates, Inlay Hints, Reader Mode, Auto Import, etc.)
 * expose options that have no meaning here.  The ones we keep are the pages
 * that feed into the terminal rendering:
 *
 * <ul>
 *   <li>Editor &gt; Font ({@code editor.preferences.fonts.default}) — the
 *       terminal uses the platform editor font settings.</li>
 *   <li>Editor &gt; Color Scheme
 *       ({@code reference.settingsdialog.IDE.editor.colors}) — terminal
 *       colors/fg/bg come from here.</li>
 *   <li>Editor &gt; General &gt; Appearance
 *       ({@code editor.preferences.appearance}) — caret style, selection
 *       colors, etc., which the terminal editor inherits.</li>
 *   <li>Editor &gt; General ({@code preferences.editor}) — kept so that the
 *       Appearance sub-page still has a parent; General itself has no
 *       directly-visible options in Conch.</li>
 * </ul>
 *
 * <p>Pages are registered via three different extension points:
 * <ol>
 *   <li>{@code com.intellij.applicationConfigurable} — application-level,
 *       stripped in {@link #appFrameCreated}.</li>
 *   <li>{@code com.intellij.editorOptionsProvider} — the sub-pages under
 *       Editor &gt; General, also stripped in {@link #appFrameCreated}.</li>
 *   <li>{@code com.intellij.projectConfigurable} — per-project, stripped in
 *       {@link #stripProjectConfigurables} which {@code ConchStartupActivity}
 *       invokes on project open.</li>
 * </ol>
 *
 * <p>Same pattern as {@link ConchRefactoringStripper}: an explicit list of
 * IDs so new upstream additions are obvious from the logs rather than
 * silently pass through a filter.
 */
public final class ConchEditorSettingsStripper implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(ConchEditorSettingsStripper.class);

    /**
     * Top-level or nested application-scoped pages under the Editor group we
     * remove. IDs sourced from
     * {@code platform/platform-resources/src/META-INF/LangExtensions.xml}
     * and {@code PlatformExtensions.xml}.
     */
    private static final Set<String> APP_CONFIGURABLE_IDS = Set.of(
        "preferences.editor.code.editing",   // Code Editing
        "editing.templates",                 // Live Templates
        "preferences.fileTypes",             // File Types
        "preferences.intentionPowerPack",    // Intentions
        "com.intellij.minimap",              // Minimap
        "Console",                           // Console (parentId=preferences.editor)
        "editor.breadcrumbs",                // Breadcrumbs
        "editor.stickyLines"                 // Sticky Lines
    );

    /**
     * Sub-pages under Editor &gt; General that get loaded through
     * {@code childrenEPName="com.intellij.editorOptionsProvider"}.
     * {@code editor.preferences.appearance} is deliberately absent — it's a
     * keeper.
     */
    private static final Set<String> EDITOR_OPTIONS_PROVIDER_IDS = Set.of(
        "editor.preferences.smartKeys",
        "editor.preferences.gutterIcons",
        "editor.preferences.tabs",
        "editor.preferences.folding",
        "editor.preferences.completion",
        "editor.preferences.completion.popup",
        "editor.preferences.completion.inline"
    );

    /**
     * Project-scoped pages under the Editor group removed per-project open.
     * Registered via {@code <projectConfigurable>} in the same XMLs.
     */
    private static final Set<String> PROJECT_CONFIGURABLE_IDS = Set.of(
        "editor.reader.mode",                // Reader Mode
        "inlay.hints",                       // Inlay Hints
        "preferences.sourceCode",            // Code Style
        "fileTemplates",                     // File Templates
        "Errors",                            // Inspections
        "File.Encoding",                     // File Encodings
        "editor.preferences.import"          // Auto Import (parentId=preferences.editor)
    );

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        stripFromExtensionPoint(
            Configurable.APPLICATION_CONFIGURABLE.getPoint(),
            APP_CONFIGURABLE_IDS,
            "app configurable");

        ExtensionsArea appArea = ApplicationManager.getApplication().getExtensionArea();
        ExtensionPoint<ConfigurableEP<Configurable>> editorOptionsEp =
            appArea.getExtensionPointIfRegistered("com.intellij.editorOptionsProvider");
        if (editorOptionsEp != null) {
            stripFromExtensionPoint(editorOptionsEp, EDITOR_OPTIONS_PROVIDER_IDS, "editor options provider");
        }
    }

    /**
     * Called from {@link ConchStartupActivity} on project open. Project-level
     * configurables live in each project's extension area, so they have to be
     * stripped per-project — the {@code AppLifecycleListener} can't reach them.
     */
    public static void stripProjectConfigurables(@NotNull Project project) {
        ExtensionPoint<ConfigurableEP<Configurable>> ep =
            project.getExtensionArea().getExtensionPointIfRegistered("com.intellij.projectConfigurable");
        if (ep == null) return;
        stripFromExtensionPoint(ep, PROJECT_CONFIGURABLE_IDS, "project configurable (" + project.getName() + ")");
    }

    private static void stripFromExtensionPoint(
        @NotNull ExtensionPoint<ConfigurableEP<Configurable>> ep,
        @NotNull Set<String> unwantedIds,
        @NotNull String label
    ) {
        // Snapshot first — unregisterExtension mutates the underlying list.
        List<ConfigurableEP<Configurable>> snapshot = new ArrayList<>(ep.getExtensionList());
        int removed = 0;
        for (ConfigurableEP<Configurable> extension : snapshot) {
            if (extension.id == null) continue;
            if (!unwantedIds.contains(extension.id)) continue;
            try {
                ep.unregisterExtension(extension);
                removed++;
                LOG.info("Conch: stripped " + label + " '" + extension.id + "'");
            } catch (Exception e) {
                LOG.warn("Conch: failed to strip " + label + " '" + extension.id + "'", e);
            }
        }
        LOG.info("Conch: stripped " + removed + " " + label + "(s)");
    }
}
