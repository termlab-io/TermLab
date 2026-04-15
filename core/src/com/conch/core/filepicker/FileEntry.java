package com.conch.core.filepicker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Platform-agnostic view of a single directory entry. Used by the
 * unified file picker and by the SFTP tool window panes. Implementations
 * include {@code com.conch.sftp.model.LocalFileEntry} (backed by
 * {@code java.nio.file.Path}) and {@code com.conch.sftp.model.RemoteFileEntry}
 * (backed by Apache SSHD SFTP).
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
