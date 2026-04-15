package com.conch.sftp.session;

import com.conch.sftp.client.ConchSftpConnector;
import com.conch.sftp.client.SshSftpSession;
import com.conch.ssh.client.SshConnectException;
import com.conch.ssh.credentials.HostCredentialBundle;
import com.conch.ssh.model.SshHost;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class DefaultSftpConnector implements SftpConnector {

    @Override
    public @NotNull SshSftpSession open(@NotNull SshHost host, @NotNull HostCredentialBundle bundle)
        throws SshConnectException
    {
        return ConchSftpConnector.open(host, bundle.target(), bundle.bastion());
    }

    @Override
    public @Nullable HostCredentialBundle resolveCredentials(@NotNull SshHost host) {
        return HostCredentialBundle.resolveForHost(host);
    }
}
