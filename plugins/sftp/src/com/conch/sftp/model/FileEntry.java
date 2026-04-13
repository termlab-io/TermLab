package com.conch.sftp.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Platform-agnostic view of a single directory entry, used by both
 * the local pane (java.nio.file.Path-backed) and the remote pane
 * (MINA SFTP-backed). {@link com.conch.sftp.toolwindow.FileTableModel}
 * renders either kind via this interface so there's only one code
 * path for column layout, sort, icon, and row selection.
 */
public interface FileEntry {
    @NotNull String name();

    long size();

    @Nullable Instant modified();

    boolean isDirectory();

    boolean isSymlink();

    /** POSIX permissions string, e.g. "rwxr-xr--", or empty if unknown. */
    @NotNull String permissions();
}
