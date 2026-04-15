package com.conch.sftp.session;

import com.conch.sftp.client.SshSftpSession;
import com.conch.ssh.client.SshConnectException;
import com.conch.ssh.credentials.HostCredentialBundle;
import com.conch.ssh.model.SshHost;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Indirection over {@link com.conch.sftp.client.ConchSftpConnector#open}
 * so that {@link SftpSessionManager} can be unit-tested with a fake.
 */
public interface SftpConnector {

    /**
     * Opens a new SFTP session to the given host. The caller is
     * responsible for resolving credentials (on the EDT) and passing
     * the resulting bundle. This method is safe to call from any
     * thread — it does not show any UI.
     *
     * @return a freshly-opened session.
     * @throws SshConnectException if the connection fails for any reason.
     */
    @NotNull SshSftpSession open(@NotNull SshHost host, @NotNull HostCredentialBundle bundle) throws SshConnectException;

    /**
     * Resolves credentials for the given host. Must be called on the
     * Event Dispatch Thread because vault credential providers may
     * show a {@link com.intellij.openapi.ui.DialogWrapper} to prompt
     * for a vault password.
     *
     * @return the resolved bundle, or null if credentials could not
     *         be resolved (user cancelled, no provider available).
     */
    @Nullable HostCredentialBundle resolveCredentials(@NotNull SshHost host);
}
