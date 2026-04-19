package com.termlab.sftp.toolwindow;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SftpVisibilityFilters {

    private SftpVisibilityFilters() {
    }

    public static boolean shouldShowLocal(@NotNull Path path, boolean showHiddenFiles) {
        if (showHiddenFiles) return true;

        Path fileName = path.getFileName();
        if (fileName != null && fileName.toString().startsWith(".")) {
            return false;
        }

        try {
            return !Files.isHidden(path);
        } catch (IOException ignored) {
            return true;
        }
    }

    public static boolean shouldShowRemote(@NotNull String fileName, boolean showHiddenFiles) {
        return showHiddenFiles || !fileName.startsWith(".");
    }
}
