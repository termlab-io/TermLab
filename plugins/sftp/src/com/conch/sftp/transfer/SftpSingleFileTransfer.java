package com.conch.sftp.transfer;

import org.apache.sshd.sftp.client.SftpClient;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stateless helpers for single-file SFTP transfers used by the
 * opt-in editor plugin. Intentionally lighter than
 * {@link TransferCoordinator}, which owns UI state and collision
 * handling for pane-to-pane transfers.
 */
public final class SftpSingleFileTransfer {

    private SftpSingleFileTransfer() {}

    /**
     * Download {@code remotePath} to {@code localDest}, replacing
     * anything at the destination. Creates parent directories.
     * Streams bytes; does not load the whole file into memory.
     */
    public static void download(
        @NotNull SftpClient client,
        @NotNull String remotePath,
        @NotNull Path localDest
    ) throws IOException {
        Path parent = localDest.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = localDest.resolveSibling(localDest.getFileName().toString() + ".part");
        try (InputStream in = client.read(remotePath);
             OutputStream out = Files.newOutputStream(tmp)) {
            in.transferTo(out);
        }
        Files.move(tmp, localDest,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Upload {@code localSource} to {@code remotePath}, replacing
     * anything at the destination. Does not manage permissions or
     * ownership.
     */
    public static void upload(
        @NotNull SftpClient client,
        @NotNull Path localSource,
        @NotNull String remotePath
    ) throws IOException {
        try (InputStream in = Files.newInputStream(localSource);
             OutputStream out = client.write(remotePath)) {
            in.transferTo(out);
        }
    }
}
