package com.termlab.core;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The platform About dialog always reads {@code license/third-party-libraries.html}
 * from {@code idea.home.path}. Packaged builds get that file from the installer
 * build, but local {@code make termlab} runs point {@code idea.home.path} at the
 * intellij-community checkout, so we seed the generated fallback from the TermLab
 * repo into the expected home location on startup.
 */
public final class TermLabThirdPartyLicenseFiles {
    private static final Logger LOG = Logger.getInstance(TermLabThirdPartyLicenseFiles.class);
    private static final String[] FILENAMES = {
        "third-party-libraries.html",
        "third-party-libraries.json",
    };

    private TermLabThirdPartyLicenseFiles() {
    }

    public static void ensurePresent() {
        Path home = Path.of(PathManager.getHomePath());
        Path sourceDir = home.resolve("termlab").resolve("customization").resolve("third-party-libraries");
        Path targetDir = home.resolve("license");
        if (!Files.isDirectory(sourceDir)) {
            return;
        }

        for (String filename : FILENAMES) {
            copyIfNeeded(sourceDir.resolve(filename), targetDir.resolve(filename));
        }
    }

    private static void copyIfNeeded(@NotNull Path source, @NotNull Path target) {
        if (!Files.isRegularFile(source)) {
            return;
        }

        try {
            if (Files.isRegularFile(target) && Files.mismatch(source, target) == -1L) {
                return;
            }

            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
        catch (IOException e) {
            LOG.warn("Failed to sync third-party license file " + source + " -> " + target, e);
        }
    }
}
