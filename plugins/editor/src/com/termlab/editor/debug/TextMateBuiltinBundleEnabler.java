package com.termlab.editor.debug;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Ensures TextMate's built-in bundles are available before files are opened in
 * either regular project windows or Light Edit windows.
 *
 * <p>TermLab's layout places the bundled TextMate grammars at
 * {@code <home>/plugins/textmate/lib/bundles}, but upstream discovery can look
 * for a plugin-home path that does not exist in our packaged layout. We seed the
 * builtin bundle list explicitly and trigger a reload once the app frame exists.
 */
public final class TextMateBuiltinBundleEnabler implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(TextMateBuiltinBundleEnabler.class);
    private static volatile boolean fired = false;

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        if (fired) return;
        fired = true;

        Path bundlesPath = Paths.get(PathManager.getHomePath(), "plugins", "textmate", "lib", "bundles");
        enableBuiltinBundlesIfNeeded(bundlesPath);
    }

    private static void enableBuiltinBundlesIfNeeded(Path bundlesPath) {
        if (!Files.isDirectory(bundlesPath)) {
            LOG.warn("TermLab editor: TextMate bundles directory not found at " + bundlesPath);
            return;
        }

        Object builtinSettings;
        Class<?> builtinSettingsClass;
        try {
            builtinSettingsClass = Class.forName("org.jetbrains.plugins.textmate.configuration.TextMateBuiltinBundlesSettings");
            builtinSettings = ApplicationManager.getApplication().getService(builtinSettingsClass);
        } catch (ClassNotFoundException e) {
            LOG.warn("TermLab editor: TextMate builtin bundle settings class is unavailable", e);
            return;
        }

        if (builtinSettings == null) {
            LOG.warn("TermLab editor: TextMate builtin bundle settings service is unavailable");
            return;
        }

        List<?> existingBundles;
        try {
            Method getter = builtinSettingsClass.getMethod("getBuiltinBundles");
            existingBundles = (List<?>) getter.invoke(builtinSettings);
        } catch (Throwable t) {
            LOG.warn("TermLab editor: failed to read TextMate builtin bundles", t);
            return;
        }

        if (existingBundles.isEmpty()) {
            List<Object> bundlesToLoad = loadBundles(bundlesPath);
            if (bundlesToLoad.isEmpty()) {
                LOG.warn("TermLab editor: no TextMate bundles found at " + bundlesPath);
                return;
            }

            try {
                Method setter = builtinSettingsClass.getMethod("setBuiltinBundles", List.class);
                setter.invoke(builtinSettings, bundlesToLoad);
            } catch (Throwable t) {
                LOG.warn("TermLab editor: failed to seed TextMate builtin bundles", t);
                return;
            }
        }

        reloadEnabledBundles();
        flushEdt();
    }

    private static List<Object> loadBundles(Path bundlesPath) {
        Class<?> bundleToLoadClass;
        java.lang.reflect.Constructor<?> bundleToLoadCtor;
        try {
            bundleToLoadClass = Class.forName("org.jetbrains.plugins.textmate.TextMateBundleToLoad");
            bundleToLoadCtor = bundleToLoadClass.getConstructor(String.class, String.class);
        } catch (Throwable t) {
            LOG.warn("TermLab editor: TextMate bundle descriptor class is unavailable", t);
            return List.of();
        }

        List<Object> bundlesToLoad = new ArrayList<>();
        try (Stream<Path> entries = Files.list(bundlesPath)) {
            entries.filter(path -> Files.isDirectory(path) && !path.getFileName().toString().startsWith("."))
                .forEach(bundleDir -> {
                    try {
                        bundlesToLoad.add(bundleToLoadCtor.newInstance(
                            bundleDir.getFileName().toString(),
                            bundleDir.toString()
                        ));
                    } catch (Throwable t) {
                        LOG.warn("TermLab editor: failed to register TextMate bundle at " + bundleDir, t);
                    }
                });
        } catch (IOException e) {
            LOG.warn("TermLab editor: failed to scan TextMate bundles at " + bundlesPath, e);
            return List.of();
        }

        return bundlesToLoad;
    }

    private static void reloadEnabledBundles() {
        try {
            Class<?> svcClass = Class.forName("org.jetbrains.plugins.textmate.TextMateService");
            Object svc = ApplicationManager.getApplication().getService(svcClass);
            if (svc == null) {
                LOG.warn("TermLab editor: TextMate service is unavailable");
                return;
            }
            Method reload = svcClass.getMethod("reloadEnabledBundles");
            reload.invoke(svc);
        } catch (Throwable t) {
            LOG.warn("TermLab editor: failed to reload TextMate bundles", t);
        }
    }

    private static void flushEdt() {
        try {
            ApplicationManager.getApplication().invokeAndWait(() -> { });
        } catch (Throwable t) {
            LOG.warn("TermLab editor: failed to flush TextMate reload on EDT", t);
        }
    }
}
