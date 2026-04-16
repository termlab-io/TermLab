package com.termlab.sftp.client;

import com.termlab.ssh.client.TermLabServerKeyVerifier;
import com.termlab.ssh.client.TermLabSshClient;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.client.SshResolvedCredential;
import com.termlab.ssh.model.SshHost;
import com.intellij.openapi.application.ApplicationManager;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Bridges the SFTP plugin to the TermLab SSH plugin's
 * {@link TermLabSshClient#connectSession} pipeline. Returns a
 * {@link SshSftpSession} that owns both the underlying
 * {@link ClientSession} and its {@link SftpClient} subsystem.
 *
 * <p>Kept inside the SFTP plugin so the SSH plugin has no
 * compile-time dependency on the vendored {@code sshd-sftp} jar.
 */
public final class TermLabSftpConnector {

    private TermLabSftpConnector() {
    }

    public static @NotNull SshSftpSession open(
        @NotNull SshHost host,
        @NotNull SshResolvedCredential credential,
        @Nullable TermLabSshClient.BastionAuth bastionAuth
    ) throws SshConnectException {
        TermLabSshClient client = ApplicationManager.getApplication().getService(TermLabSshClient.class);
        if (client == null) {
            // Dev fallback: outside a real IDE boot the service registry
            // may not have the ssh plugin wired in yet.
            client = new TermLabSshClient();
        }
        ClientSession session = client.connectSession(
            host, credential, bastionAuth, new TermLabServerKeyVerifier());
        try {
            SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session);
            return new SshSftpSession(session, sftpClient);
        } catch (IOException e) {
            session.close(true);
            throw new SshConnectException(
                SshConnectException.Kind.CHANNEL_OPEN_FAILED,
                "Could not open SFTP subsystem on " + host.host() + ":" + host.port()
                    + " — " + e.getMessage(),
                e);
        }
    }
}
