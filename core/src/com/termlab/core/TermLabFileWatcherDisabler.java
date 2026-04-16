package com.termlab.core;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.registry.RegistryValue;
import com.intellij.openapi.vfs.LocalFileSystem;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Disables IntelliJ's external file-watcher integration for TermLab.
 *
 * <p>The platform's native watcher path (fsnotifier on macOS/Linux,
 * a matching native watcher on Windows) is what triggers the
 * "External file changes sync might be slow" notification whenever
 * that watcher is missing or fails to start. TermLab does not rely on
 * external file synchronization or project indexing, so the watcher is
 * just noise and background work for this product.
 *
 * <p>TermLab already bakes {@code -Didea.filewatcher.disabled=true}
 * into its build-time VM options. This listener mirrors that at
 * runtime for launch paths that may bypass the generated vmoptions,
 * then eagerly initializes {@link LocalFileSystem} so the watcher, if
 * it gets created lazily later, sees the disabled property from the
 * start.
 */
public final class TermLabFileWatcherDisabler implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(TermLabFileWatcherDisabler.class);

    private static final String FILE_WATCHER_DISABLED_PROPERTY = "idea.filewatcher.disabled";
    private static final String EEL_FILE_WATCHER_REGISTRY_KEY = "use.eel.file.watcher";

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        boolean touched = false;

        if (!Boolean.getBoolean(FILE_WATCHER_DISABLED_PROPERTY)) {
            System.setProperty(FILE_WATCHER_DISABLED_PROPERTY, "true");
            touched = true;
        }

        try {
            RegistryValue value = Registry.get(EEL_FILE_WATCHER_REGISTRY_KEY);
            if (value.asBoolean()) {
                value.setValue(false);
                touched = true;
            }
        } catch (Throwable t) {
            LOG.info("TermLab: could not toggle " + EEL_FILE_WATCHER_REGISTRY_KEY
                + " (likely not registered): " + t.getMessage());
        }

        try {
            LocalFileSystem.getInstance();
        } catch (Throwable t) {
            LOG.warn("TermLab: failed to initialize LocalFileSystem after disabling file watcher", t);
            return;
        }

        if (touched) {
            LOG.info("TermLab: disabled external file watcher integration");
        }
    }
}
