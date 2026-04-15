package com.conch.core;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.ExtensionsArea;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Removes non-Conch theme providers so Conch exposes only its own TermLab
 * themes in the theme picker.
 */
public final class ConchBuiltInThemeStripper implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(ConchBuiltInThemeStripper.class);

    private static final String THEME_PROVIDER_EP = "com.intellij.themeProvider";
    private static final Set<String> KEEP_THEME_IDS = Set.of("termlab.dark", "termlab.light");

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        ExtensionsArea area = ApplicationManager.getApplication().getExtensionArea();
        @SuppressWarnings("rawtypes")
        ExtensionPoint ep = area.getExtensionPointIfRegistered(THEME_PROVIDER_EP);
        if (ep == null) {
            LOG.warn("Conch: theme provider EP not found: " + THEME_PROVIDER_EP);
            return;
        }

        @SuppressWarnings("unchecked")
        List<Object> snapshot = new ArrayList<>(ep.getExtensionList());
        int removed = 0;
        for (Object extension : snapshot) {
            String id = readString(extension, "id", "getId");
            String path = readString(extension, "path", "getPath");

            if (KEEP_THEME_IDS.contains(id)) {
                continue;
            }
            try {
                ep.unregisterExtension(extension);
                removed++;
                LOG.info("Conch: unregistered non-TermLab theme provider id=" + id + " path=" + path);
            } catch (Throwable t) {
                LOG.warn("Conch: failed to unregister non-TermLab theme provider id=" + id + " path=" + path, t);
            }
        }

        LOG.info("Conch: stripped " + removed + " non-TermLab theme provider(s)");
    }

    private static String readString(Object target, String fieldName, String getterName) {
        if (target == null) return null;

        try {
            Field field = target.getClass().getField(fieldName);
            Object value = field.get(target);
            if (value instanceof String s) return s;
        } catch (Throwable ignored) {
            // Fall through to getter.
        }

        try {
            Method getter = target.getClass().getMethod(getterName);
            Object value = getter.invoke(target);
            if (value instanceof String s) return s;
        } catch (Throwable ignored) {
            // Ignore; caller handles null.
        }
        return null;
    }
}
