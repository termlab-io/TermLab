package com.termlab.core;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.List;

/**
 * Overrides the macOS Dock icon with a high-resolution (1024x1024) PNG
 * on app startup.
 *
 * <p>Why this is needed: IntelliJ's {@code AppUIUtil.updateAppWindowIcon}
 * loads the application SVG at a hard-coded {@code size = 32} and calls
 * {@code Taskbar.setIconImage} with that tiny bitmap. macOS then scales
 * it up to the dock size (typically 128-256 pt), producing a visibly
 * blurry icon. The {@code -Dapple.awt.application.icon=...termlab.icns}
 * JVM arg in the run configuration is overridden by this runtime call,
 * so the .icns approach that works for packaged .app bundles has no
 * effect in dev mode.
 *
 * <p>This listener re-calls {@code Taskbar.setIconImage} with the full
 * 1024x1024 source image <em>after</em> IntelliJ's startup code runs,
 * letting macOS do its own (high-quality) downscaling. Using
 * {@code invokeLater} with {@link ModalityState#any()} ensures we run
 * after any pending EDT tasks IntelliJ may have queued for its own
 * icon update.
 */
public final class TermLabMacDockIconCustomizer implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(TermLabMacDockIconCustomizer.class);

    /**
     * Classpath path of the source dock icon. Lives under
     * {@code customization/resources/} which is stripped to the classpath
     * root by the {@code customization_resources} resourcegroup.
     */
    private static final String ICON_RESOURCE = "/termlab_dock_icon.png";

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        if (!SystemInfo.isMac) return;
        if (!Taskbar.isTaskbarSupported()) return;

        BufferedImage image = loadIcon();
        if (image == null) return;

        // Set it now in case we happen to run after IntelliJ's update.
        applyDockIcon(image);

        // Also queue it via invokeLater — IntelliJ's updateAppWindowIcon
        // runs asynchronously during bootstrap and may set a 32px bitmap
        // after appFrameCreated fires. The queued task runs after any
        // pending EDT work, so it wins.
        ApplicationManager.getApplication().invokeLater(
            () -> applyDockIcon(image),
            ModalityState.any());
    }

    private static @org.jetbrains.annotations.Nullable BufferedImage loadIcon() {
        try (InputStream in = TermLabMacDockIconCustomizer.class.getResourceAsStream(ICON_RESOURCE)) {
            if (in == null) {
                LOG.warn("TermLab: dock icon resource not found at " + ICON_RESOURCE);
                return null;
            }
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                LOG.warn("TermLab: could not decode dock icon at " + ICON_RESOURCE);
                return null;
            }
            LOG.info("TermLab: loaded dock icon " + image.getWidth() + "x" + image.getHeight());
            return image;
        } catch (Exception e) {
            LOG.warn("TermLab: failed to load dock icon", e);
            return null;
        }
    }

    private static void applyDockIcon(@NotNull Image image) {
        try {
            Taskbar.getTaskbar().setIconImage(image);
        } catch (UnsupportedOperationException | SecurityException e) {
            LOG.warn("TermLab: could not set dock icon: " + e.getMessage());
        }
    }
}
