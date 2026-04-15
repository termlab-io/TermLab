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
     * Opens a new SFTP session to the given host. Resolves credentials via
     * {@link HostCredentialBundle#resolveForHost} unless an injected
     * implementation supplies them differently.
     *
     * @return a freshly-opened session.
     * @throws SshConnectException if the connection fails for any reason.
     */
    @NotNull SshSftpSession open(@NotNull SshHost host) throws SshConnectException;

    /**
     * Resolves credentials for the given host. Production code goes
     * through the vault plugin; tests can return a stub.
     */
    @Nullable HostCredentialBundle resolveCredentials(@NotNull SshHost host);
}
