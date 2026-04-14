package com.conch.sftp.spi;

import com.conch.sftp.client.SshSftpSession;
import com.conch.sftp.model.RemoteFileEntry;
import com.conch.ssh.model.SshHost;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Extension point consumed by the SFTP remote pane to delegate
 * double-click-to-open behavior for files (directories still
 * navigate). Zero extensions registered means "do nothing," which
 * preserves existing pane behavior when the opt-in editor plugin
 * is disabled.
 *
 * <p>The SFTP session is passed through directly rather than
 * reopened — the opener is expected to use
 * {@link SshSftpSession#client()} for any SFTP IO. The opener must
 * not close the session.
 */
public interface RemoteFileOpener {

    ExtensionPointName<RemoteFileOpener> EP_NAME =
        ExtensionPointName.create("com.conch.sftp.remoteFileOpener");

    /**
     * Open the given remote file for editing.
     *
     * @param project        current project
     * @param host           host descriptor (for display + caching)
     * @param session        live SFTP session; caller retains ownership
     * @param absoluteRemotePath absolute path to the file on the remote
     * @param entry          directory-listing entry for the file (used
     *                       for size + name + attribute info without a
     *                       second stat call)
     */
    void open(@NotNull Project project,
              @NotNull SshHost host,
              @NotNull SshSftpSession session,
              @NotNull String absoluteRemotePath,
              @NotNull RemoteFileEntry entry);
}
