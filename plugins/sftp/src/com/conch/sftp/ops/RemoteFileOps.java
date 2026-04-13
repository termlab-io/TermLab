package com.conch.sftp.ops;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Pure-function helpers for SFTP filesystem mutations invoked by
 * {@link com.conch.sftp.toolwindow.RemoteFilePane}'s context menu
 * and drag-and-drop handlers. All methods run against an already
 * authenticated MINA {@link SftpClient}; they must be called off
 * the EDT so the network round-trips don't freeze the UI.
 */
public final class RemoteFileOps {

    private RemoteFileOps() {
    }

    /** Rename {@code oldPath}'s leaf to {@code newName}, keeping its parent directory. */
    public static @NotNull String rename(
        @NotNull SftpClient sftp,
        @NotNull String oldPath,
        @NotNull String newName
    ) throws IOException {
        String newPath = joinPath(parentOf(oldPath), newName);
        sftp.rename(oldPath, newPath);
        return newPath;
    }

    /**
     * Recursively delete {@code path}. Directories are walked via
     * {@link SftpClient#readDir}; files, symlinks, and empty
     * directories are removed with {@code remove} / {@code rmdir}.
     */
    public static void delete(@NotNull SftpClient sftp, @NotNull String path) throws IOException {
        SftpClient.Attributes attrs = sftp.stat(path);
        boolean isSymlink = (attrs.getPermissions() & SftpConstants.S_IFMT) == SftpConstants.S_IFLNK;
        if (!attrs.isDirectory() || isSymlink) {
            sftp.remove(path);
            return;
        }
        for (SftpClient.DirEntry entry : sftp.readDir(path)) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            delete(sftp, joinPath(path, name));
        }
        sftp.rmdir(path);
    }

    /** Create a new subdirectory named {@code name} inside {@code parentPath}. */
    public static @NotNull String mkdir(
        @NotNull SftpClient sftp,
        @NotNull String parentPath,
        @NotNull String name
    ) throws IOException {
        String path = joinPath(parentPath, name);
        sftp.mkdir(path);
        return path;
    }

    /** Move {@code source} into {@code destDir}, preserving its file name. */
    public static @NotNull String move(
        @NotNull SftpClient sftp,
        @NotNull String source,
        @NotNull String destDir
    ) throws IOException {
        String destPath = joinPath(destDir, baseName(source));
        if (destPath.equals(source)) {
            throw new IOException("Source and destination are the same: " + source);
        }
        sftp.rename(source, destPath);
        return destPath;
    }

    private static @NotNull String parentOf(@NotNull String path) {
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return "/";
        return path.substring(0, slash);
    }

    private static @NotNull String baseName(@NotNull String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1) return path;
        return path.substring(slash + 1);
    }

    private static @NotNull String joinPath(@NotNull String base, @NotNull String child) {
        if (base.endsWith("/")) return base + child;
        return base + "/" + child;
    }
}
