package com.termlab.core;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.ExtensionsArea;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurableEP;
import com.intellij.openapi.options.colors.ColorAndFontDescriptorsProvider;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import com.intellij.openapi.project.Project;
import com.intellij.application.options.colors.ColorAndFontPanelFactory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Strips the IDE's "Editor" settings subtree down to the pages that actually
 * affect TermLab's terminal view.
 *
 * <p>TermLab is a terminal workstation, not an IDE — so the bulk of the
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
 *       colors/fg/bg come from here, minus Language Defaults, Diff &amp;
 *       Merge, and By Scope.</li>
 *   <li>Editor &gt; General &gt; Appearance
 *       ({@code editor.preferences.appearance}) — caret style, selection
 *       colors, etc., which the terminal editor inherits.</li>
 *   <li>Editor &gt; General ({@code preferences.editor}) — kept so that the
 *       Appearance sub-page still has a parent; General itself has no
 *       directly-visible options in TermLab.</li>
 * </ul>
 *
 * <p>Pages are registered via three different extension points:
 * <ol>
 *   <li>{@code com.intellij.applicationConfigurable} — application-level,
 *       stripped in {@link #appFrameCreated}.</li>
 *   <li>{@code com.intellij.editorOptionsProvider} — the sub-pages under
 *       Editor &gt; General, also stripped in {@link #appFrameCreated}.</li>
 *   <li>{@code com.intellij.projectConfigurable} — per-project, stripped in
 *       {@link #stripProjectConfigurables} which {@code TermLabStartupActivity}
 *       invokes on project open.</li>
 * </ol>
 *
 * <p>Same pattern as {@link TermLabRefactoringStripper}: an explicit list of
 * IDs so new upstream additions are obvious from the logs rather than
 * silently pass through a filter.
 */
public final class TermLabEditorSettingsStripper implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(TermLabEditorSettingsStripper.class);
    private static final String DEFAULT_LANGUAGE_COLORS_PAGE_CLASS =
        "com.intellij.openapi.options.colors.pages.DefaultLanguageColorsPage";
    private static final String DIFF_COLORS_FACTORY_CLASS =
        "com.intellij.openapi.diff.impl.settings.DiffColorsPageFactory";
    private static final String SCOPE_COLORS_FACTORY_CLASS =
        "com.intellij.application.options.colors.ScopeColorsPageFactory";
    private static final PluginId TERMLAB_CORE_PLUGIN_ID = PluginId.getId("com.termlab.core");

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
        ExtensionPoint<ConfigurableEP<Configurable>> appConfigurables = Configurable.APPLICATION_CONFIGURABLE.getPoint();
        stripFromExtensionPoint(
            appConfigurables,
            APP_CONFIGURABLE_IDS,
            "app configurable");
        replaceColorSchemeConfigurable(appConfigurables);

        ExtensionsArea appArea = ApplicationManager.getApplication().getExtensionArea();
        ExtensionPoint<ConfigurableEP<Configurable>> editorOptionsEp =
            appArea.getExtensionPointIfRegistered("com.intellij.editorOptionsProvider");
        if (editorOptionsEp != null) {
            stripFromExtensionPoint(editorOptionsEp, EDITOR_OPTIONS_PROVIDER_IDS, "editor options provider");
        }

        stripColorSchemeChildren(appArea);
    }

    /**
     * Called from {@link TermLabStartupActivity} on project open. Project-level
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
                LOG.info("TermLab: stripped " + label + " '" + extension.id + "'");
            } catch (Exception e) {
                LOG.warn("TermLab: failed to strip " + label + " '" + extension.id + "'", e);
            }
        }
        LOG.info("TermLab: stripped " + removed + " " + label + "(s)");
    }

    private static void stripColorSchemeChildren(@NotNull ExtensionsArea appArea) {
        ExtensionPoint<ColorSettingsPage> colorSettingsEp =
            appArea.getExtensionPointIfRegistered("com.intellij.colorSettingsPage");
        if (colorSettingsEp != null) {
            stripExtensionsByClassName(
                colorSettingsEp,
                Set.of(DEFAULT_LANGUAGE_COLORS_PAGE_CLASS),
                "color settings page");
        }

        ExtensionPoint<ColorAndFontPanelFactory> panelFactoryEp =
            appArea.getExtensionPointIfRegistered("com.intellij.colorAndFontPanelFactory");
        if (panelFactoryEp != null) {
            stripExtensionsByClassName(
                panelFactoryEp,
                Set.of(DIFF_COLORS_FACTORY_CLASS, SCOPE_COLORS_FACTORY_CLASS),
                "color and font panel factory");
        }

        ExtensionPoint<ColorAndFontDescriptorsProvider> descriptorsEp =
            appArea.getExtensionPointIfRegistered("com.intellij.colorAndFontDescriptorProvider");
        if (descriptorsEp != null) {
            stripExtensionsByClassName(
                descriptorsEp,
                Set.of(DIFF_COLORS_FACTORY_CLASS),
                "color and font descriptor provider");
        }
    }

    private static void replaceColorSchemeConfigurable(@NotNull ExtensionPoint<ConfigurableEP<Configurable>> ep) {
        List<ConfigurableEP<Configurable>> snapshot = new ArrayList<>(ep.getExtensionList());
        for (ConfigurableEP<Configurable> extension : snapshot) {
            if (!"reference.settingsdialog.IDE.editor.colors".equals(extension.id)) {
                continue;
            }
            if (TermLabColorAndFontOptions.class.getName().equals(extension.instanceClass)) {
                return;
            }
            try {
                IdeaPluginDescriptor termLabDescriptor = PluginManagerCore.getPlugin(TERMLAB_CORE_PLUGIN_ID);
                if (termLabDescriptor == null) {
                    LOG.warn("TermLab: failed to replace Color Scheme configurable, core plugin descriptor is missing");
                    return;
                }
                ep.unregisterExtension(extension);

                ConfigurableEP<Configurable> replacement = new ConfigurableEP<>(termLabDescriptor);
                replacement.displayName = extension.displayName;
                replacement.key = extension.key;
                replacement.bundle = extension.bundle;
                replacement.id = extension.id;
                replacement.parentId = extension.parentId;
                replacement.groupId = extension.groupId;
                replacement.groupWeight = extension.groupWeight;
                replacement.nonDefaultProject = extension.nonDefaultProject;
                replacement.dynamic = extension.dynamic;
                replacement.children = extension.children;
                replacement.childrenEPName = extension.childrenEPName;
                replacement.treeRendererClass = extension.treeRendererClass;
                replacement.instanceClass = TermLabColorAndFontOptions.class.getName();
                ep.registerExtension(replacement, termLabDescriptor, ApplicationManager.getApplication());
                LOG.info("TermLab: replaced Color Scheme configurable to strip Console Font");
            } catch (Exception e) {
                LOG.warn("TermLab: failed to replace Color Scheme configurable", e);
            }
            return;
        }
    }

    private static <T> void stripExtensionsByClassName(
        @NotNull ExtensionPoint<T> ep,
        @NotNull Set<String> unwantedClassNames,
        @NotNull String label
    ) {
        List<T> snapshot = new ArrayList<>(ep.getExtensionList());
        int removed = 0;
        for (T extension : snapshot) {
            if (!unwantedClassNames.contains(extension.getClass().getName())) continue;
            try {
                ep.unregisterExtension(extension);
                removed++;
                LOG.info("TermLab: stripped " + label + " '" + extension.getClass().getName() + "'");
            } catch (Exception e) {
                LOG.warn("TermLab: failed to strip " + label + " '" + extension.getClass().getName() + "'", e);
            }
        }
        LOG.info("TermLab: stripped " + removed + " " + label + "(s)");
    }
}
