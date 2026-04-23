package com.termlab.sysinfo.collect;

import com.intellij.openapi.application.ApplicationManager;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.client.TermLabServerKeyVerifier;
import com.termlab.ssh.client.TermLabSshClient;
import com.termlab.ssh.credentials.HostCredentialBundle;
import com.termlab.ssh.model.SshHost;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class RemoteCommandRunner implements CommandRunner {

    private final SshHost host;

    public RemoteCommandRunner(@NotNull SshHost host) {
        this.host = host;
    }

    @Override
    public @NotNull CommandResult run(@NotNull String command, @NotNull Duration timeout)
        throws IOException, InterruptedException {
        HostCredentialBundle bundle = resolveCredentials();
        if (bundle == null) {
            throw new IOException("Could not resolve credentials for " + host.label());
        }

        try (bundle) {
            TermLabSshClient client = ApplicationManager.getApplication().getService(TermLabSshClient.class);
            try (ClientSession session = client.connectSession(
                host,
                bundle.target(),
                bundle.bastion(),
                new TermLabServerKeyVerifier()
            )) {
                return runExec(session, command, timeout);
            } catch (SshConnectException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
    }

    private @NotNull CommandResult runExec(
        @NotNull ClientSession session,
        @NotNull String command,
        @NotNull Duration timeout
    ) throws IOException, InterruptedException {
        ChannelExec channel = session.createExecChannel(command);
        channel.open().verify(timeout);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread outThread = copyThread(channel.getInvertedOut(), stdout, "stdout");
        Thread errThread = copyThread(channel.getInvertedErr(), stderr, "stderr");
        outThread.start();
        errThread.start();

        long deadlineNs = System.nanoTime() + timeout.toNanos();
        while (channel.isOpen() && !channel.isClosed()) {
            if (System.nanoTime() >= deadlineNs) {
                channel.close(true);
                throw new IOException("Command timed out after " + timeout.toSeconds() + "s");
            }
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.MILLISECONDS.toMillis(100));
        }
        outThread.join(TimeUnit.SECONDS.toMillis(1));
        errThread.join(TimeUnit.SECONDS.toMillis(1));

        Integer exit = channel.getExitStatus();
        return new CommandResult(
            exit == null ? 0 : exit,
            stdout.toString(StandardCharsets.UTF_8),
            stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private static @NotNull Thread copyThread(
        @NotNull java.io.InputStream input,
        @NotNull ByteArrayOutputStream output,
        @NotNull String streamName
    ) {
        Thread thread = new Thread(() -> {
            try (input) {
                input.transferTo(output);
            } catch (IOException ignored) {
            }
        }, "TermLabSysInfo-remote-" + streamName);
        thread.setDaemon(true);
        return thread;
    }

    private HostCredentialBundle resolveCredentials() {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            return HostCredentialBundle.resolveForHost(host);
        }
        AtomicReference<HostCredentialBundle> ref = new AtomicReference<>();
        ApplicationManager.getApplication().invokeAndWait(() -> ref.set(HostCredentialBundle.resolveForHost(host)));
        return ref.get();
    }
}
