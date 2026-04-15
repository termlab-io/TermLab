package com.conch.sftp.client;

import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns a MINA {@link SftpClient} and the underlying
 * {@link ClientSession}. Closing the wrapper closes both.
 *
 * <p>Conch's SFTP plugin holds one of these per active remote pane.
 * The pane keeps the reference for the lifetime of the user's
 * connection and calls {@link #close()} when the user disconnects
 * or the pane is disposed.
 */
public final class SshSftpSession implements AutoCloseable {

    private final ClientSession session;
    private final SftpClient sftpClient;

    public SshSftpSession(@NotNull ClientSession session, @NotNull SftpClient sftpClient) {
        this.session = session;
        this.sftpClient = sftpClient;
    }

    public @NotNull SftpClient client() {
        return sftpClient;
    }

    public @NotNull ClientSession session() {
        return session;
    }

    /**
     * Write {@code content} to {@code remotePath} using a temp-file-then-rename
     * strategy for atomicity. Safe to call on new or existing remote files.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Write content to {@code remotePath.<random>.tmp}.
     *   <li>Rename the tmp file onto {@code remotePath} (atomic on POSIX SFTP servers).
     *   <li>If rename fails, fall back to: remove existing target, then rename.
     * </ol>
     *
     * @throws IOException if the write or final rename fails
     */
    public void writeBytesAtomically(@NotNull String remotePath, byte @NotNull [] content)
        throws IOException
    {
        String randomSuffix = Long.toHexString(ThreadLocalRandom.current().nextLong());
        String writeTmp = remotePath + "." + randomSuffix + ".tmp";

        // Step 1: write to a sibling temp file.
        try {
            try (OutputStream out = sftpClient.write(writeTmp)) {
                out.write(content);
            }
        } catch (IOException e) {
            try { sftpClient.remove(writeTmp); } catch (IOException ignored) {}
            throw e;
        }

        // Step 2: atomic rename (POSIX servers succeed even when the target exists).
        try {
            sftpClient.rename(writeTmp, remotePath);
        } catch (IOException renameErr) {
            // Fallback for non-POSIX servers: remove the existing target, then rename.
            try { sftpClient.remove(remotePath); } catch (IOException ignored) {}
            try {
                sftpClient.rename(writeTmp, remotePath);
            } catch (IOException finalErr) {
                try { sftpClient.remove(writeTmp); } catch (IOException ignored) {}
                throw finalErr;
            }
        }
    }

    @Override
    public void close() {
        try {
            sftpClient.close();
        } catch (IOException ignored) {
        }
        session.close(true);
    }
}
