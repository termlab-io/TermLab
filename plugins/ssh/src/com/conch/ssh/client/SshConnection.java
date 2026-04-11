package com.conch.ssh.client;

import com.jediterm.terminal.TtyConnector;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;

/**
 * An open SSH connection — one {@link ClientSession} plus its shell
 * {@link ChannelShell} — with a lazy {@link TtyConnector} view for
 * JediTerm to render.
 *
 * <p>Ownership: {@code ConchSshClient.connect()} returns an instance of
 * this, and the caller (usually the session provider) is responsible for
 * {@link #close()}-ing it when the terminal tab goes away. Calling
 * {@code close()} tears down both the channel and the session.
 *
 * <p>Not reusable — after {@code close()}, the connector will report
 * {@code isConnected() = false} and I/O attempts will error.
 */
public final class SshConnection implements AutoCloseable {

    private final ClientSession session;
    private final ChannelShell channel;
    private volatile SshTtyConnector connector;

    public SshConnection(@NotNull ClientSession session, @NotNull ChannelShell channel) {
        this.session = session;
        this.channel = channel;
    }

    /**
     * @return the {@link TtyConnector} view of this connection. Lazily
     *         created on first access and cached; callers can safely
     *         invoke this multiple times.
     */
    public @NotNull TtyConnector getTtyConnector() {
        SshTtyConnector local = connector;
        if (local == null) {
            synchronized (this) {
                local = connector;
                if (local == null) {
                    connector = local = new SshTtyConnector(new MinaShellChannelIo(channel));
                }
            }
        }
        return local;
    }

    public @NotNull ClientSession session() {
        return session;
    }

    public @NotNull ChannelShell channel() {
        return channel;
    }

    @Override
    public void close() {
        try {
            channel.close(true);
        } catch (Exception ignored) {
        }
        try {
            session.close(true);
        } catch (Exception ignored) {
        }
    }

    /**
     * {@link ShellChannelIo} adapter that delegates to a real MINA
     * {@link ChannelShell}. Kept nested inside {@link SshConnection} so
     * the adapter and the channel-owning type live together and the rest
     * of the plugin can depend on the {@code ShellChannelIo} interface
     * without touching MINA directly.
     */
    private static final class MinaShellChannelIo implements ShellChannelIo {

        private final ChannelShell channel;

        MinaShellChannelIo(@NotNull ChannelShell channel) {
            this.channel = channel;
        }

        @Override
        public @NotNull InputStream remoteOutput() {
            return channel.getInvertedOut();
        }

        @Override
        public @NotNull OutputStream remoteInput() {
            return channel.getInvertedIn();
        }

        @Override
        public boolean isConnected() {
            return channel.isOpen() && !channel.isClosed();
        }

        @Override
        public void sendWindowChange(int columns, int rows) throws IOException {
            channel.sendWindowChange(columns, rows);
        }

        @Override
        public int waitForClose() throws InterruptedException {
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L);
            Integer status = channel.getExitStatus();
            return status == null ? 0 : status;
        }

        @Override
        public void close() {
            try {
                channel.close(true);
            } catch (Exception ignored) {
            }
        }
    }
}
