package com.termlab.sftp.ops;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Pure-function helpers for local filesystem mutations invoked by
 * {@link com.termlab.sftp.toolwindow.LocalFilePane}'s context menu
 * and drag-and-drop handlers. All methods assume they run off the
 * EDT and may throw {@link IOException}.
 */
public final class LocalFileOps {

    private LocalFileOps() {
    }

    /** Rename {@code source} to {@code newName} in its current parent directory. */
    public static @NotNull Path rename(@NotNull Path source, @NotNull String newName) throws IOException {
        Path newPath = source.resolveSibling(newName);
        Files.move(source, newPath);
        return newPath;
    }

    /** Recursively delete {@code path}, whether file or directory. Symlinks are unlinked, not followed. */
    public static void delete(@NotNull Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Create a new subdirectory named {@code name} inside {@code parent}. */
    public static @NotNull Path mkdir(@NotNull Path parent, @NotNull String name) throws IOException {
        Path dir = parent.resolve(name);
        Files.createDirectory(dir);
        return dir;
    }

    /**
     * Move {@code source} into {@code destDir}, preserving its file
     * name. Fails loudly on same-path no-ops so the caller doesn't
     * silently drop a move that would have clobbered itself.
     */
    public static @NotNull Path move(@NotNull Path source, @NotNull Path destDir) throws IOException {
        Path dest = destDir.resolve(source.getFileName());
        if (dest.equals(source)) {
            throw new IOException("Source and destination are the same: " + source);
        }
        Files.move(source, dest);
        return dest;
    }
}
