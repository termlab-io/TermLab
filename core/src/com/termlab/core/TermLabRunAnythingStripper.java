package com.termlab.core;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.ExtensionsArea;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Disables the platform Run Anything feature at startup so no providers remain
 * behind the action if a bundled module tries to surface it indirectly.
 */
public final class TermLabRunAnythingStripper implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(TermLabRunAnythingStripper.class);

    private static final String RUN_ANYTHING_ACTION_ID = "RunAnything";
    private static final String RUN_ANYTHING_PROVIDER_EP = "com.intellij.runAnything.executionProvider";
    private static final String MODIFIER_KEY_DOUBLE_CLICK_HANDLER =
        "com.intellij.openapi.keymap.impl.ModifierKeyDoubleClickHandler";

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        stripRunAnythingProviders();
        unregisterDoubleCtrlShortcut();
    }

    private static void stripRunAnythingProviders() {
        ExtensionsArea area = ApplicationManager.getApplication().getExtensionArea();
        @SuppressWarnings("rawtypes")
        ExtensionPoint ep = area.getExtensionPointIfRegistered(RUN_ANYTHING_PROVIDER_EP);
        if (ep == null) {
            LOG.warn("TermLab: Run Anything provider EP not found: " + RUN_ANYTHING_PROVIDER_EP);
            return;
        }

        @SuppressWarnings("unchecked")
        List<Object> snapshot = new ArrayList<>(ep.getExtensionList());
        int removed = 0;
        for (Object extension : snapshot) {
            try {
                ep.unregisterExtension(extension);
                removed++;
                LOG.info("TermLab: unregistered Run Anything provider " + extension.getClass().getName());
            } catch (Throwable t) {
                LOG.warn("TermLab: failed to unregister Run Anything provider "
                    + extension.getClass().getName(), t);
            }
        }

        LOG.info("TermLab: stripped " + removed + " Run Anything provider(s)");
    }

    private static void unregisterDoubleCtrlShortcut() {
        try {
            Class<?> handlerClass = Class.forName(MODIFIER_KEY_DOUBLE_CLICK_HANDLER);
            Method getInstance = handlerClass.getMethod("getInstance");
            Object handler = getInstance.invoke(null);
            Method unregisterAction = handlerClass.getMethod("unregisterAction", String.class);
            unregisterAction.invoke(handler, RUN_ANYTHING_ACTION_ID);
            LOG.info("TermLab: unregistered Run Anything double-ctrl shortcut");
        } catch (Throwable t) {
            LOG.warn("TermLab: failed to unregister Run Anything double-ctrl shortcut", t);
        }
    }
}
