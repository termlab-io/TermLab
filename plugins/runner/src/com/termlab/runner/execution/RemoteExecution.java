package com.termlab.runner.execution;

import com.intellij.openapi.diagnostic.Logger;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSH exec-channel-backed execution.
 */
public final class RemoteExecution implements ScriptExecution {

    private static final Logger LOG = Logger.getInstance(RemoteExecution.class);

    private final ClientSession session;
    private final ChannelExec channel;
    private final InputStream mergedOutput;
    private final CopyOnWriteArrayList<Runnable> terminationListeners = new CopyOnWriteArrayList<>();

    private RemoteExecution(
        @NotNull ClientSession session,
        @NotNull ChannelExec channel,
        @NotNull InputStream mergedOutput
    ) {
        this.session = session;
        this.channel = channel;
        this.mergedOutput = mergedOutput;

        Thread waiter = new Thread(() -> {
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L);
            for (Runnable listener : terminationListeners) {
                listener.run();
            }
        }, "TermLabRunner-remote-waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    public static @NotNull RemoteExecution start(
        @NotNull ClientSession session,
        @NotNull String command
    ) throws IOException {
        LOG.info("TermLab Runner: starting remote execution: " + command);
        ChannelExec channel = session.createExecChannel(command);
        channel.open().verify(Duration.ofSeconds(10));

        InputStream stdout = channel.getInvertedOut();
        InputStream stderr = channel.getInvertedErr();
        InputStream merged = new SequenceInputStream(stdout, stderr);

        return new RemoteExecution(session, channel, merged);
    }

    @Override
    public @NotNull InputStream getOutputStream() {
        return mergedOutput;
    }

    @Override
    public void sendInterrupt() {
        try {
            LOG.info("TermLab Runner: closing remote exec channel as interrupt fallback");
            channel.close(false);
        } catch (Exception e) {
            LOG.warn("TermLab Runner: failed to close remote channel: " + e.getMessage());
        }
    }

    @Override
    public void kill() {
        LOG.info("TermLab Runner: closing remote exec channel");
        try {
            channel.close(true);
        } catch (Exception ignored) {
        }
        try {
            session.close(true);
        } catch (Exception ignored) {
        }
    }

    @Override
    public @Nullable Integer getExitCode() {
        if (channel.isOpen() && !channel.isClosed()) {
            return null;
        }
        return channel.getExitStatus();
    }

    @Override
    public void addTerminationListener(@NotNull Runnable listener) {
        terminationListeners.add(listener);
        if (!channel.isOpen() || channel.isClosed()) {
            listener.run();
        }
    }

    @Override
    public boolean isRunning() {
        return channel.isOpen() && !channel.isClosed();
    }
}
