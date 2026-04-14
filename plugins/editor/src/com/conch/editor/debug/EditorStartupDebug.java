package com.conch.editor.debug;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One-shot debug activity that logs TextMate loading state at Conch
 * startup. Runs once per session. Remove after diagnosis.
 */
public final class EditorStartupDebug implements ProjectActivity {

    private static volatile boolean fired = false;

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (fired) return Unit.INSTANCE;
        fired = true;

        Logger log = Logger.getInstance(EditorStartupDebug.class);
        log.warn("CONCH_EDITOR_DEBUG: startup — Conch editor plugin loaded");

        String home = PathManager.getHomePath();
        log.warn("CONCH_EDITOR_DEBUG: PathManager.getHomePath=" + home);
        log.warn("CONCH_EDITOR_DEBUG: PathManager.getPluginsPath=" + PathManager.getPluginsPath());

        Path bundlesPath = Paths.get(home, "plugins", "textmate", "lib", "bundles");
        log.warn("CONCH_EDITOR_DEBUG: expected bundles path=" + bundlesPath + " exists=" + Files.exists(bundlesPath));

        // Check TextMateService classpath presence + service instance
        try {
            Class<?> svcClass = Class.forName("org.jetbrains.plugins.textmate.TextMateService");
            log.warn("CONCH_EDITOR_DEBUG: TextMateService.class loaded from: " + svcClass.getProtectionDomain().getCodeSource());
            Object svc = com.intellij.openapi.application.ApplicationManager.getApplication().getService(svcClass);
            log.warn("CONCH_EDITOR_DEBUG: TextMateService instance=" + (svc == null ? "NULL" : svc.getClass().getName()));
            if (svc != null) {
                // Try to invoke initServiceAndLoadBundles / reloadEnabledBundles if available
                try {
                    java.lang.reflect.Method m = svcClass.getMethod("reloadEnabledBundles");
                    log.warn("CONCH_EDITOR_DEBUG: calling TextMateService.reloadEnabledBundles()");
                    m.invoke(svc);
                    log.warn("CONCH_EDITOR_DEBUG: reloadEnabledBundles returned OK");
                } catch (NoSuchMethodException nsme) {
                    log.warn("CONCH_EDITOR_DEBUG: no reloadEnabledBundles method");
                } catch (Throwable t) {
                    log.warn("CONCH_EDITOR_DEBUG: reloadEnabledBundles threw: " + t.getClass().getName() + ": " + t.getMessage());
                }
            }
        } catch (ClassNotFoundException e) {
            log.warn("CONCH_EDITOR_DEBUG: TextMateService class NOT on classpath");
        } catch (Throwable t) {
            log.warn("CONCH_EDITOR_DEBUG: TextMateService lookup threw: " + t.getMessage());
        }

        // Count all file types
        FileTypeManager ftm = FileTypeManager.getInstance();
        FileType[] types = ftm.getRegisteredFileTypes();
        log.warn("CONCH_EDITOR_DEBUG: registered file type count=" + types.length);

        int tmCount = 0;
        for (FileType ft : types) {
            if (ft.getClass().getName().toLowerCase().contains("textmate")) {
                log.warn("CONCH_EDITOR_DEBUG: registered TextMate type: name=" + ft.getName()
                    + " class=" + ft.getClass().getName());
                tmCount++;
            }
        }
        log.warn("CONCH_EDITOR_DEBUG: total TextMate-typed file types registered=" + tmCount);

        // Probe a few common filenames to see what FileTypeManager thinks they are
        String[] probes = {"x.java", "x.py", "x.go", "x.rs", "x.md", "x.json", "x.yaml"};
        for (String p : probes) {
            FileType ft = ftm.getFileTypeByFileName(p);
            log.warn("CONCH_EDITOR_DEBUG: probe '" + p + "' -> " + ft.getClass().getName() + " (name=" + ft.getName() + ")");
        }

        return Unit.INSTANCE;
    }
}
