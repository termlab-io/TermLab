package com.termlab.sftp.model;

import com.termlab.core.filepicker.FileEntry;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

public record RemoteFileEntry(
    @NotNull String name,
    long size,
    @Nullable Instant modified,
    boolean isDirectory,
    boolean isSymlink,
    @NotNull String permissions
) implements FileEntry {

    public static @NotNull RemoteFileEntry of(@NotNull SftpClient.DirEntry entry) {
        SftpClient.Attributes attrs = entry.getAttributes();
        boolean isDir = attrs.isDirectory();
        int mode = attrs.getPermissions();
        boolean isLink = (mode & SftpConstants.S_IFMT) == SftpConstants.S_IFLNK;
        return new RemoteFileEntry(
            entry.getFilename(),
            attrs.getSize(),
            attrs.getModifyTime() == null
                ? null
                : Instant.ofEpochMilli(attrs.getModifyTime().toMillis()),
            isDir,
            isLink,
            formatPosixMode(mode)
        );
    }

    private static @NotNull String formatPosixMode(int mode) {
        StringBuilder sb = new StringBuilder(9);
        sb.append((mode & 0400) != 0 ? 'r' : '-');
        sb.append((mode & 0200) != 0 ? 'w' : '-');
        sb.append((mode & 0100) != 0 ? 'x' : '-');
        sb.append((mode & 0040) != 0 ? 'r' : '-');
        sb.append((mode & 0020) != 0 ? 'w' : '-');
        sb.append((mode & 0010) != 0 ? 'x' : '-');
        sb.append((mode & 0004) != 0 ? 'r' : '-');
        sb.append((mode & 0002) != 0 ? 'w' : '-');
        sb.append((mode & 0001) != 0 ? 'x' : '-');
        return sb.toString();
    }
}
