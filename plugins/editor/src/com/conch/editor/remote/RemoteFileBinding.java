package com.conch.editor.remote;

import com.conch.sftp.client.SshSftpSession;
import com.conch.ssh.model.SshHost;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Association between a local temp file (opened in an editor tab)
 * and the remote file it mirrors. Holds the live SFTP session so
 * save can upload without reopening a connection.
 *
 * <p>The session reference is weak from the caller's perspective:
 * if the user disconnects in the SFTP pane, the session becomes
 * unusable and upload will fail with an IO error. That's the
 * expected behavior — the spec has no reconnection logic in MVP.
 */
public record RemoteFileBinding(
    @NotNull Path tempPath,
    @NotNull SshHost host,
    @NotNull String absoluteRemotePath,
    @NotNull SshSftpSession session
) {}
