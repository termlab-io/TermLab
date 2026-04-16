package com.termlab.core;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.registry.RegistryValue;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Suppresses the Windows Defender exclusion-check notification on
 * Windows.
 *
 * <p>The platform ships a {@code backgroundPostStartupActivity}
 * ({@code WindowsDefenderCheckerActivity}) that pops a notification
 * recommending the user add their project directory to Defender's
 * scan exclusions so indexing runs faster. That check is gated on
 * the {@code ide.check.windows.defender.rules} registry key, which
 * defaults to {@code true} in {@code PlatformExtensions.xml}. The
 * key is read on the background activity's {@code init} block via
 * {@code Registry.is(...)}; if it evaluates to {@code false}, the
 * extension throws {@link com.intellij.openapi.extensions.ExtensionNotApplicableException}
 * and never runs.
 *
 * <p>TermLab does no project indexing — there is nothing to accelerate
 * by tweaking Defender rules — so the notification is pure noise.
 * We flip the registry value to {@code false} in
 * {@link #appFrameCreated}, which fires before the background
 * startup activity pool drains.
 *
 * <p>The listener is only meaningful on Windows. On other OSes the
 * underlying extension is declared {@code os="windows"} so it
 * isn't registered at all, and flipping the registry key is a
 * harmless no-op. Kept unconditional to keep the behavior
 * symmetric across the platforms users might develop on.
 */
public final class TermLabDefenderNotifierSuppressor implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(TermLabDefenderNotifierSuppressor.class);

    private static final String REGISTRY_KEY = "ide.check.windows.defender.rules";

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        try {
            RegistryValue value = Registry.get(REGISTRY_KEY);
            if (value.asBoolean()) {
                value.setValue(false);
                if (SystemInfo.isWindows) {
                    LOG.info("TermLab: disabled Windows Defender rules check ("
                        + REGISTRY_KEY + "=false)");
                }
            }
        } catch (Throwable t) {
            // Registry key may not exist in a stripped platform layout
            // or on non-Windows hosts. We don't care — the goal is
            // simply "make sure this notification never fires."
            LOG.info("TermLab: could not toggle " + REGISTRY_KEY
                + " (likely not registered): " + t.getMessage());
        }
    }
}
