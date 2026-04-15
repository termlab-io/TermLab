package com.conch.sftp.client;

import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

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

    @Override
    public void close() {
        try {
            sftpClient.close();
        } catch (IOException ignored) {
        }
        session.close(true);
    }
}
