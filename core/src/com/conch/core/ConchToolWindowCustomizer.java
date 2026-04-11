package com.conch.core;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.wm.ToolWindowEP;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Strips IDE-oriented tool windows that Conch's bundled modules still
 * register via {@code <toolWindow>} in their plugin XMLs. Fires on
 * {@link AppLifecycleListener#appFrameCreated(List)}, before any project
 * is opened and before the stripe buttons can be painted.
 *
 * <p>Conch deliberately follows the Android-Studio-style "vendored platform"
 * model where we bundle whole modules rather than reaching in to delete
 * individual XML entries. That gives us broad stability across upstream
 * changes, but it also means features like "Problems View" tag along. This
 * listener is where we surgically remove those at startup.
 */
public final class ConchToolWindowCustomizer implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(ConchToolWindowCustomizer.class);

    /**
     * Tool window ids to unregister. These come from the {@code id} attribute
     * of {@code <toolWindow>} extensions in the bundled modules' plugin XMLs.
     * Add new entries here as we discover more IDE-only tool windows that
     * shouldn't exist in Conch.
     */
    private static final Set<String> UNWANTED_TOOL_WINDOW_IDS = Set.of(
        // From platform-resources/LangExtensions.xml — IDE analysis-results
        // panel. Conch has no analysis, so this window is always empty.
        "Problems View"
    );

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        ExtensionPoint<ToolWindowEP> ep = ToolWindowEP.EP_NAME.getPoint();

        List<ToolWindowEP> toRemove = new ArrayList<>();
        for (ToolWindowEP extension : ep.getExtensionList()) {
            if (UNWANTED_TOOL_WINDOW_IDS.contains(extension.id)) {
                toRemove.add(extension);
            }
        }

        for (ToolWindowEP extension : toRemove) {
            ep.unregisterExtension(extension);
            LOG.info("Conch: unregistered tool window '" + extension.id + "'");
        }
    }
}
