package com.termlab.core;

import com.intellij.ide.AppLifecycleListener;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Seeds the About dialog's third-party license files before the first frame is
 * shown, so the built-in "Powered by open source software" link works in local
 * {@code make termlab} runs as well as packaged builds.
 */
public final class TermLabThirdPartyLicenseFilesSync implements AppLifecycleListener {
    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        TermLabThirdPartyLicenseFiles.ensurePresent();
    }
}
