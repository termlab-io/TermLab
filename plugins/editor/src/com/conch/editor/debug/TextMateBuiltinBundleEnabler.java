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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Startup activity that ensures TextMate's built-in bundles are registered so
 * that TextMateService can resolve file-name matchers (e.g. .java, .py, .go).
 *
 * Root cause: on a non-sources run PluginPathManager.getPluginHome("textmate-plugin")
 * resolves to a path that does not exist in Conch's layout
 * ({home}/plugins/textmate-plugin/lib/bundles), so discoverBuiltinBundles() finds
 * nothing and TextMateServiceImpl ends up with an empty extensionMapping.
 *
 * Architecture note: TextMate does NOT register file-name matchers with
 * FileTypeManager.addExtension(). Instead, TextMateFileType implements
 * FileTypeIdentifiableByVirtualFile.isMyFileType(), which at file-open time
 * calls TextMateService.getLanguageDescriptorByFileName(). This means:
 *   - FileTypeManager.getFileTypeByFileName("x.py") will ALWAYS return UnknownFileType
 *     for TextMate-managed extensions — that is correct and expected.
 *   - The correct probe is TextMateService.getLanguageDescriptorByFileName("x.py").
 *
 * Fix: Before or around the time reloadEnabledBundles() fires, scan the actual
 * bundles directory ({home}/plugins/textmate/lib/bundles) and populate
 * TextMateBuiltinBundlesSettings.builtinBundles so that discoverBuiltinBundles()
 * short-circuits to the pre-populated list instead of trying the wrong plugin-home
 * path.  Then call reloadEnabledBundles() and flush the EDT so that the async
 * extensionMapping update (posted via invokeLater inside registerBundles) is applied
 * before we probe.
 *
 * Timeline of reloadEnabledBundles() internals:
 *   1. registerBundles(fireEvents=true) acquires lock.
 *   2. discoverBuiltinBundles() → returns the pre-populated list (62 bundles).
 *   3. registerBundlesInParallel() loads all bundles — synchronous (awaits IO).
 *   4. fireFileTypesChangedEvent() posts an invokeLater that sets extensionMapping
 *      on the EDT — ASYNC return from reloadEnabledBundles().
 *   5. invokeAndWait() here flushes that EDT task before we probe.
 */
public final class TextMateBuiltinBundleEnabler implements ProjectActivity {

    private static volatile boolean fired = false;

    private static final Logger LOG = Logger.getInstance(TextMateBuiltinBundleEnabler.class);

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (fired) return Unit.INSTANCE;
        fired = true;

        // ----------------------------------------------------------------
        // Phase 1: environment diagnostics
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

        // Count all file types BEFORE fix (purely for context; TextMate always
        // registers exactly 1 shared TextMateFileType singleton in FileTypeManager)
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
        //          After reloadEnabledBundles() returns, flush the EDT to
        //          ensure the async extensionMapping update has been applied.
        // ----------------------------------------------------------------
        enableBuiltinBundlesIfNeeded(bundlesPath);

        // ----------------------------------------------------------------
        // Phase 3: post-fix diagnostic probes.
        //
        // FileTypeManager count is expected to remain 1 (the single shared
        // TextMateFileType) — TextMate never adds per-extension entries to
        // FileTypeManager.  The meaningful probes use TextMateService directly.
        // ----------------------------------------------------------------
        FileType[] typesAfter = ftm.getRegisteredFileTypes();
        LOG.warn("CONCH_EDITOR_DEBUG: registered file type count AFTER fix=" + typesAfter.length
                + " (expect unchanged; TextMate uses FileTypeIdentifiableByVirtualFile, not per-extension registration)");
        int tmCountAfter = 0;
        for (FileType ft : typesAfter) {
            if (ft.getClass().getName().toLowerCase().contains("textmate")) {
                LOG.warn("CONCH_EDITOR_DEBUG: registered TextMate type (after): name=" + ft.getName()
                        + " class=" + ft.getClass().getName());
                tmCountAfter++;
            }
        }
        LOG.warn("CONCH_EDITOR_DEBUG: total TextMate-typed file types registered AFTER fix=" + tmCountAfter
                + " (expect 1 — the shared TextMateFileType singleton)");

        // Correct probes: ask TextMateService for language descriptors.
        // Non-null means the bundle was loaded and the extension is recognised.
        probeTextMateDescriptors();

        return Unit.INSTANCE;
    }

    /**
     * Core fix.
     *
     * Steps:
     *   A – obtain TextMateBuiltinBundlesSettings service instance
     *   B – check whether builtinBundles is already populated
     *   C – scan the on-disk bundles directory to build the list
     *   D – write the list into builtinBundlesSettings.builtinBundles
     *   E – call reloadEnabledBundles() on TextMateService
     *          (iterates builtinBundles via discoverBuiltinBundles short-circuit)
     *   F – flush the EDT via invokeAndWait so the async extensionMapping
     *          update posted by fireFileTypesChangedEvent is applied before
     *          we probe
     */
    private static void enableBuiltinBundlesIfNeeded(Path bundlesPath) {
        if (!Files.isDirectory(bundlesPath)) {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] bundles dir not found at " + bundlesPath + " — skipping");
            return;
        }

        // --- Step A: obtain TextMateBuiltinBundlesSettings instance ---
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
            Method getter = builtinSettingsClass.getMethod("getBuiltinBundles");
            existingBundles = (List<?>) getter.invoke(builtinSettings);
        } catch (Throwable t) {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] getBuiltinBundles() failed: " + t.getMessage(), t);
            return;
        }

        LOG.warn("CONCH_EDITOR_DEBUG: [enabler] existing builtinBundles.size=" + existingBundles.size());

        if (!existingBundles.isEmpty()) {
            // Already populated (e.g. pre-set by another init path) — just reload.
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] builtinBundles already populated; triggering reload");
            reloadEnabledBundles();
            return;
        }

        // --- Step C: scan the on-disk bundles directory ---
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

        LOG.warn("CONCH_EDITOR_DEBUG: [enabler] found " + bundlesToLoad.size() + " bundles at " + bundlesPath);
        if (bundlesToLoad.isEmpty()) {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] no bundles found — skipping");
            return;
        }

        // --- Step D: write the list into builtinBundlesSettings.builtinBundles ---
        // TextMateBuiltinBundlesSettings.builtinBundles is a Kotlin var (mutable
        // property backed by a generated setBuiltinBundles(List) setter).
        // discoverBuiltinBundles() short-circuits on isNotEmpty(), so after this
        // call registerBundles() will use our list instead of trying to stat the
        // wrong textmate-plugin path.
        try {
            Method setter = builtinSettingsClass.getMethod("setBuiltinBundles", List.class);
            setter.invoke(builtinSettings, bundlesToLoad);
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] setBuiltinBundles() called with "
                    + bundlesToLoad.size() + " entries"
                    + " via " + setter.getDeclaringClass().getName() + ".setBuiltinBundles(List)");
        } catch (Throwable t) {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] setBuiltinBundles() failed: " + t.getMessage(), t);
            return;
        }

        // --- Step E: trigger bundle registration ---
        // reloadEnabledBundles() → registerBundles(fireEvents=true):
        //   - calls discoverBuiltinBundles() → returns our 62-entry list (short-circuit)
        //   - calls registerBundlesInParallel() — synchronous (awaits IO completion)
        //   - posts fireFileTypesChangedEvent via invokeLater (async EDT) to set
        //     extensionMapping on TextMateServiceImpl
        //
        // IMPORTANT: reloadEnabledBundles() returns before the invokeLater fires,
        // so extensionMapping is still the cleared/old value at this point.
        // Step F flushes that EDT task.
        reloadEnabledBundles();

        // --- Step F: flush the EDT so the async extensionMapping update is applied ---
        // fireFileTypesChangedEvent() posted an invokeLater that sets
        // extensionMapping and notifies FileTypeManager listeners. We call
        // invokeAndWait (a no-op on the EDT itself) to drain that task before
        // our probes run.
        try {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] flushing EDT to apply async extensionMapping update...");
            ApplicationManager.getApplication().invokeAndWait(() ->
                LOG.warn("CONCH_EDITOR_DEBUG: [enabler] EDT flush complete — extensionMapping should now be populated")
            );
        } catch (Throwable t) {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] invokeAndWait flush threw: " + t.getMessage(), t);
        }
    }

    /**
     * Calls reloadEnabledBundles() on TextMateService via reflection.
     *
     * TextMateService.reloadEnabledBundles() (public abstract, declared in
     * TextMateService.java line 43) calls registerBundles(fireEvents=true)
     * in TextMateServiceImpl.  It reads BOTH:
     *   - TextMateBuiltinBundlesSettings.builtinBundles (via discoverBuiltinBundles)
     *   - TextMateUserBundlesSettings.bundles (user-added bundles)
     */
    private static void reloadEnabledBundles() {
        try {
            Class<?> svcClass = Class.forName("org.jetbrains.plugins.textmate.TextMateService");
            Object svc = ApplicationManager.getApplication().getService(svcClass);
            if (svc == null) {
                LOG.warn("CONCH_EDITOR_DEBUG: [enabler] TextMateService instance is null — cannot reload");
                return;
            }
            Method reload = svcClass.getMethod("reloadEnabledBundles");
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] calling "
                    + reload.getDeclaringClass().getName() + ".reloadEnabledBundles()");
            reload.invoke(svc);
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] reloadEnabledBundles() returned"
                    + " (extensionMapping update is async — see EDT flush below)");
        } catch (Throwable t) {
            LOG.warn("CONCH_EDITOR_DEBUG: [enabler] reloadEnabledBundles() threw: "
                    + t.getClass().getName() + ": " + t.getMessage(), t);
        }
    }

    /**
     * Probes TextMateService.getLanguageDescriptorByFileName() for several
     * well-known extensions.
     *
     * NOTE: FileTypeManager.getFileTypeByFileName("x.py") is NOT the right probe
     * for TextMate — it will always return UnknownFileType for TextMate-managed
     * extensions because TextMate uses FileTypeIdentifiableByVirtualFile rather
     * than FileTypeManager extension registration.  A non-null language descriptor
     * from TextMateService means the bundle is loaded and the extension is handled.
     */
    private static void probeTextMateDescriptors() {
        Class<?> svcClass;
        Object svc;
        Method getDescriptor;
        try {
            svcClass = Class.forName("org.jetbrains.plugins.textmate.TextMateService");
            svc = ApplicationManager.getApplication().getService(svcClass);
            if (svc == null) {
                LOG.warn("CONCH_EDITOR_DEBUG: [probe] TextMateService is null — cannot probe");
                return;
            }
            getDescriptor = svcClass.getMethod("getLanguageDescriptorByFileName", CharSequence.class);
        } catch (Throwable t) {
            LOG.warn("CONCH_EDITOR_DEBUG: [probe] reflection setup failed: " + t.getMessage(), t);
            return;
        }

        // Also report extensionMapping size for a quick sanity check
        try {
            Method getMapping = svcClass.getMethod("getFileNameMatcherToScopeNameMapping");
            Object mapping = getMapping.invoke(svc);
            if (mapping instanceof java.util.Map) {
                LOG.warn("CONCH_EDITOR_DEBUG: [probe] extensionMapping.size=" + ((java.util.Map<?,?>) mapping).size()
                        + " (expect >0 if bundles loaded; 0 = reload not yet applied)");
            }
        } catch (Throwable ignored) {}

        String[] probes = {"x.java", "x.py", "x.go", "x.rs", "x.md", "x.json", "x.yaml"};
        for (String p : probes) {
            try {
                Object descriptor = getDescriptor.invoke(svc, p);
                LOG.warn("CONCH_EDITOR_DEBUG: [probe] getLanguageDescriptorByFileName('" + p + "') -> "
                        + (descriptor == null ? "NULL (not recognised)" : descriptor.getClass().getSimpleName() + " (OK)"));
            } catch (Throwable t) {
                LOG.warn("CONCH_EDITOR_DEBUG: [probe] '" + p + "' threw: " + t.getMessage());
            }
        }
    }
}
