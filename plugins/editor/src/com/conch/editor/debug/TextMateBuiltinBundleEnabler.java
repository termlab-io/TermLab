package com.conch.editor.debug;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Startup activity that ensures TextMate's built-in bundles are enabled so
 * that file-name matchers (e.g. .java, .py, .go) are registered with
 * FileTypeManager.
 *
 * Root cause: on a non-sources run PluginPathManager.getPluginHome("textmate-plugin")
 * resolves to a path that does not exist in Conch's layout
 * ({home}/plugins/textmate-plugin/lib/bundles), so discoverBuiltinBundles() finds
 * nothing and TextMateServiceImpl ends up with an empty extension mapping.
 *
 * Fix: Before reloadEnabledBundles() fires, scan the actual bundles directory
 * ({home}/plugins/textmate/lib/bundles) and populate
 * TextMateBuiltinBundlesSettings.instance.builtinBundles so that
 * discoverBuiltinBundles() short-circuits to the pre-populated list instead of
 * trying the wrong plugin-home path.
 */
public final class TextMateBuiltinBundleEnabler implements ProjectActivity {

    private static volatile boolean fired = false;

    private static final Logger LOG = Logger.getInstance(TextMateBuiltinBundleEnabler.class);

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (fired) return Unit.INSTANCE;
        fired = true;

        // ----------------------------------------------------------------
        // Phase 1: existing diagnostic logging (kept verbatim)
        // ----------------------------------------------------------------
        LOG.warn("CONCH_EDITOR_DEBUG: startup — Conch editor plugin loaded");

        String home = PathManager.getHomePath();
        LOG.warn("CONCH_EDITOR_DEBUG: PathManager.getHomePath=" + home);
        LOG.warn("CONCH_EDITOR_DEBUG: PathManager.getPluginsPath=" + PathManager.getPluginsPath());

        Path bundlesPath = Paths.get(home, "plugins", "textmate", "lib", "bundles");
        LOG.warn("CONCH_EDITOR_DEBUG: expected bundles path=" + bundlesPath + " exists=" + Files.exists(bundlesPath));

        // Check TextMateService classpath presence + service instance
        try {
            Class<?> svcClass = Class.forName("org.jetbrains.plugins.textmate.TextMateService");
            LOG.warn("CONCH_EDITOR_DEBUG: TextMateService.class loaded from: " + svcClass.getProtectionDomain().getCodeSource());
            Object svc = ApplicationManager.getApplication().getService(svcClass);
            LOG.warn("CONCH_EDITOR_DEBUG: TextMateService instance=" + (svc == null ? "NULL" : svc.getClass().getName()));
        } catch (ClassNotFoundException e) {
            LOG.warn("CONCH_EDITOR_DEBUG: TextMateService class NOT on classpath");
        } catch (Throwable t) {
            LOG.warn("CONCH_EDITOR_DEBUG: TextMateService lookup threw: " + t.getMessage());
        }

        // Count all file types (before fix)
        FileTypeManager ftm = FileTypeManager.getInstance();
        FileType[] typesBefore = ftm.getRegisteredFileTypes();
        LOG.warn("CONCH_EDITOR_DEBUG: registered file type count BEFORE fix=" + typesBefore.length);
        int tmCountBefore = 0;
        for (FileType ft : typesBefore) {
            if (ft.getClass().getName().toLowerCase().contains("textmate")) {
                LOG.warn("CONCH_EDITOR_DEBUG: registered TextMate type (before): name=" + ft.getName()
                        + " class=" + ft.getClass().getName());
                tmCountBefore++;
            }
        }
        LOG.warn("CONCH_EDITOR_DEBUG: total TextMate-typed file types registered BEFORE fix=" + tmCountBefore);

        // ----------------------------------------------------------------
        // Phase 2: populate TextMateBuiltinBundlesSettings if empty, then
        //          trigger a reload so all file-name matchers are registered.
        // ----------------------------------------------------------------
        enableBuiltinBundlesIfNeeded(bundlesPath);

        // ----------------------------------------------------------------
        // Phase 3: post-fix diagnostic probes (existing block)
        // ----------------------------------------------------------------
        FileType[] typesAfter = ftm.getRegisteredFileTypes();
        LOG.warn("CONCH_EDITOR_DEBUG: registered file type count AFTER fix=" + typesAfter.length);
        int tmCountAfter = 0;
        for (FileType ft : typesAfter) {
            if (ft.getClass().getName().toLowerCase().contains("textmate")) {
                LOG.warn("CONCH_EDITOR_DEBUG: registered TextMate type (after): name=" + ft.getName()
                        + " class=" + ft.getClass().getName());
                tmCountAfter++;
            }
        }
        LOG.warn("CONCH_EDITOR_DEBUG: total TextMate-typed file types registered AFTER fix=" + tmCountAfter);

        String[] probes = {"x.java", "x.py", "x.go", "x.rs", "x.md", "x.json", "x.yaml"};
        for (String p : probes) {
            FileType ft = ftm.getFileTypeByFileName(p);
            LOG.warn("CONCH_EDITOR_DEBUG: probe '" + p + "' -> " + ft.getClass().getName() + " (name=" + ft.getName() + ")");
        }

        return Unit.INSTANCE;
    }

    /**
     * Core fix. Tries direct API first; falls back to reflection only when
     * module/visibility boundaries block direct access.
     */
    private static void enableBuiltinBundlesIfNeeded(Path bundlesPath) {
        if (!Files.isDirectory(bundlesPath)) {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] bundles dir not found at " + bundlesPath + " — skipping");
            return;
        }

        // --- Step A: obtain TextMateBuiltinBundlesSettings instance ---
        // TextMateBuiltinBundlesSettings is a public Kotlin class registered as
        // an applicationService; we use ApplicationManager rather than its
        // companion @JvmStatic accessor to avoid a hard compile-time dep that
        // would require adding //plugins/textmate to the editor BUILD target.
        Object builtinSettings;
        Class<?> builtinSettingsClass;
        try {
            builtinSettingsClass = Class.forName(
                    "org.jetbrains.plugins.textmate.configuration.TextMateBuiltinBundlesSettings");
            builtinSettings = ApplicationManager.getApplication().getService(builtinSettingsClass);
        } catch (ClassNotFoundException e) {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] TextMateBuiltinBundlesSettings not on classpath — skipping", e);
            return;
        }

        if (builtinSettings == null) {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] TextMateBuiltinBundlesSettings service is null (not registered?) — skipping");
            return;
        }

        // --- Step B: check existing builtinBundles list ---
        List<?> existingBundles;
        try {
            java.lang.reflect.Method getter = builtinSettingsClass.getMethod("getBuiltinBundles");
            existingBundles = (List<?>) getter.invoke(builtinSettings);
        } catch (Throwable t) {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] getBuiltinBundles() failed: " + t.getMessage(), t);
            return;
        }

        LOG.warn("CONCH_EDITOR_DEBUG: [enabler] existing builtinBundles.size=" + existingBundles.size());

        if (!existingBundles.isEmpty()) {
            // Already populated (e.g. persisted from a previous session) —
            // just make sure reloadEnabledBundles() has been called.
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] builtinBundles already populated; triggering reload");
            reloadEnabledBundles();
            return;
        }

        // --- Step C: scan the on-disk bundles directory ---
        // TextMateBundleToLoad(name: String, path: String) is a public data class.
        Class<?> bundleToLoadClass;
        java.lang.reflect.Constructor<?> bundleToLoadCtor;
        try {
            bundleToLoadClass = Class.forName("org.jetbrains.plugins.textmate.TextMateBundleToLoad");
            bundleToLoadCtor = bundleToLoadClass.getConstructor(String.class, String.class);
        } catch (Throwable t) {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] TextMateBundleToLoad not accessible: " + t.getMessage(), t);
            return;
        }

        List<Object> bundlesToLoad = new ArrayList<>();
        try (Stream<Path> entries = Files.list(bundlesPath)) {
            entries.filter(p -> Files.isDirectory(p) && !p.getFileName().toString().startsWith("."))
                   .forEach(bundleDir -> {
                       String name = bundleDir.getFileName().toString();
                       String path = bundleDir.toString();
                       try {
                           bundlesToLoad.add(bundleToLoadCtor.newInstance(name, path));
                       } catch (Throwable t) {
                           LOG.warn("CONCH_EDITOR_DEBUG: [enabler] failed to construct TextMateBundleToLoad for "
                                   + path + ": " + t.getMessage());
                       }
                   });
        } catch (IOException e) {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] failed to list bundles dir: " + e.getMessage(), e);
            return;
        }

        LOG.warn("CONCH_EDITOR_DEBUG: [enabler] found " + bundlesToLoad.size() + " bundles to enable at " + bundlesPath);
        if (bundlesToLoad.isEmpty()) {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] no bundles found — skipping");
            return;
        }

        // --- Step D: write the list back into builtinBundlesSettings ---
        try {
            java.lang.reflect.Method setter = builtinSettingsClass.getMethod("setBuiltinBundles", List.class);
            setter.invoke(builtinSettings, bundlesToLoad);
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] setBuiltinBundles() called with " + bundlesToLoad.size() + " entries");
        } catch (Throwable t) {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] setBuiltinBundles() failed: " + t.getMessage(), t);
            return;
        }

        // --- Step E: trigger bundle registration ---
        reloadEnabledBundles();
    }

    private static void reloadEnabledBundles() {
        try {
            Class<?> svcClass = Class.forName("org.jetbrains.plugins.textmate.TextMateService");
            Object svc = ApplicationManager.getApplication().getService(svcClass);
            if (svc == null) {
                LOG.warn("CONCH_EDITOR_DEBUG: [enabler] TextMateService instance is null — cannot reload");
                return;
            }
            java.lang.reflect.Method reload = svcClass.getMethod("reloadEnabledBundles");
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] calling reloadEnabledBundles()");
            reload.invoke(svc);
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] reloadEnabledBundles() returned OK");
        } catch (Throwable t) {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] reloadEnabledBundles() threw: " + t.getClass().getName() + ": " + t.getMessage(), t);
        }
    }
}
