package com.termlab.core.filepicker;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * A backing source for the unified file picker. Each source represents
 * one navigable root (a local filesystem, one SFTP host, a cloud bucket,
 * etc.). Sources are contributed via the
 * {@code com.termlab.core.fileSourceProvider} extension point.
 */
public interface FileSource {

    /** Human-readable label shown in the picker's source dropdown. */
    @NotNull String label();

    /** Icon shown next to the label in the source dropdown. */
    @NotNull Icon icon();

    /**
     * Stable identifier for this source. Used to persist the
     * "last-used source" preference and deduplicate sources with
     * the same label. For SFTP, this is {@code "sftp:" + host.id()}.
     * For the built-in local source, this is the literal {@code "local"}.
     */
    @NotNull String id();

    /**
     * The path the picker should open at when this source is first
     * selected. Typically the user's home directory for local, or
     * the remote home for SFTP.
     *
     * <p>Must only be called AFTER {@link #open} has completed
     * successfully. Implementations that need a live session (SFTP)
     * rely on this ordering.
     */
    @NotNull String initialPath();

    /**
     * Ensure the source is ready for listing operations. For local
     * this is a no-op; for SFTP this acquires the session via
     * {@code SftpSessionManager}. Called on a background thread by
     * the dialog under modal progress. Throws if the source cannot
     * be brought online.
     *
     * @param owner reference-count owner for any underlying resources
     *              (sessions). The dialog passes its own identity;
     *              the source releases at dialog close.
     */
    void open(@NotNull Project project, @NotNull Object owner) throws IOException;

    /** Release any resources acquired by {@link #open}. */
    void close(@NotNull Object owner);

    /**
     * List the directory at {@code absolutePath}. Returns entries in
     * no particular order; the dialog sorts for display. Must NOT
     * include "." or "..".
     */
    @NotNull List<FileEntry> list(@NotNull String absolutePath) throws IOException;

    /** True if the path exists and is a directory. */
    boolean isDirectory(@NotNull String absolutePath) throws IOException;

    /** True if the path exists (file OR directory). */
    boolean exists(@NotNull String absolutePath) throws IOException;

    /**
     * The parent path of {@code absolutePath}, or null if it's
     * already at the source's top-level.
     */
    @Nullable String parentOf(@NotNull String absolutePath);

    /**
     * Join a directory path and a child name into a new absolute path.
     */
    @NotNull String resolve(@NotNull String directoryPath, @NotNull String childName);

    /**
     * Write bytes to {@code absolutePath}, creating or overwriting.
     * Implementations handle atomic writes internally: SFTP uses
     * .tmp+rename, local uses Files.write with CREATE+TRUNCATE.
     */
    void writeFile(@NotNull String absolutePath, byte @NotNull [] content) throws IOException;

    /** Read bytes at {@code absolutePath}. Used by the Open flow. */
    @NotNull InputStream readFile(@NotNull String absolutePath) throws IOException;
}
