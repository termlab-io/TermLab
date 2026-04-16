package com.termlab.sftp.model;

import com.termlab.core.filepicker.FileEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

public record LocalFileEntry(
    @NotNull Path path,
    @NotNull String name,
    long size,
    @Nullable Instant modified,
    boolean isDirectory,
    boolean isSymlink,
    @NotNull String permissions
) implements FileEntry {

    public static @NotNull LocalFileEntry of(@NotNull Path path) throws IOException {
        BasicFileAttributes basic = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String perms = "";
        try {
            PosixFileAttributes posix = Files.readAttributes(
                path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            perms = PosixFilePermissions.toString(posix.permissions());
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (Windows). Leave permissions empty.
        }
        String fileName = path.getFileName() == null
            ? path.toString()
            : path.getFileName().toString();
        return new LocalFileEntry(
            path,
            fileName,
            basic.size(),
            Instant.ofEpochMilli(basic.lastModifiedTime().toMillis()),
            basic.isDirectory(),
            basic.isSymbolicLink(),
            perms
        );
    }
}
