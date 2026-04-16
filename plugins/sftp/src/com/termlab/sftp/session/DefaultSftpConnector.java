package com.termlab.sftp.session;

import com.termlab.sftp.client.TermLabSftpConnector;
import com.termlab.sftp.client.SshSftpSession;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.credentials.HostCredentialBundle;
import com.termlab.ssh.model.SshHost;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class DefaultSftpConnector implements SftpConnector {

    @Override
    public @NotNull SshSftpSession open(@NotNull SshHost host, @NotNull HostCredentialBundle bundle)
        throws SshConnectException
    {
        return TermLabSftpConnector.open(host, bundle.target(), bundle.bastion());
    }

    @Override
    public @Nullable HostCredentialBundle resolveCredentials(@NotNull SshHost host) {
        return HostCredentialBundle.resolveForHost(host);
    }
}
