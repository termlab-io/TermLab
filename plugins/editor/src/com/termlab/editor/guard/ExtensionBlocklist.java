package com.termlab.editor.guard;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Hard-coded extension blocklist for the opt-in editor plugin.
 * Files with any of these extensions are refused before download
 * or local open, without touching bytes on disk or the wire.
 */
public final class ExtensionBlocklist {

    private static final Set<String> BLOCKED = Set.of(
        // images
        "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "svg",
        // archives
        "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar",
        // JVM + native bundles
        "jar", "war", "ear", "class",
        "exe", "dll", "so", "dylib",
        // documents
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        // media
        "mp3", "mp4", "mov", "avi", "mkv", "wav", "flac",
        // compiled python
        "pyc", "pyo"
    );

    private ExtensionBlocklist() {}

    /**
     * True if the filename's trailing extension is on the blocklist.
     * The extension is taken as everything after the final {@code '.'}
     * in the filename. Case-insensitive. Files with no dot (or a
     * leading dot only, like {@code .bashrc}) are never blocked.
     *
     * <p>For multi-dot archive extensions like {@code backup.tar.gz},
     * only the final segment ({@code gz}) is checked — which is
     * enough because the compressed outer extension is itself on
     * the blocklist.
     */
    public static boolean isBlocked(@NotNull String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == filename.length() - 1) return false;
        String ext = filename.substring(lastDot + 1).toLowerCase();
        return BLOCKED.contains(ext);
    }
}
