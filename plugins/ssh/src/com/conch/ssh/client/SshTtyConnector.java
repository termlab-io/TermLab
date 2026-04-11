package com.conch.ssh.client;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * JediTerm {@link TtyConnector} over a {@link ShellChannelIo}.
 *
 * <p>Mirrors {@code LocalPtySessionProvider.LocalPtyTtyConnector} in
 * shape, with two key differences:
 * <ul>
 *   <li>Reads from {@code channelIo.remoteOutput()} and writes to
 *       {@code channelIo.remoteInput()} instead of a {@code PtyProcess}'s
 *       pipe pair.</li>
 *   <li>{@link #resize(TermSize)} calls
 *       {@link ShellChannelIo#sendWindowChange(int, int)} over the SSH
 *       protocol instead of {@code pty.setWinSize()}.</li>
 * </ul>
 *
 * <p>No OSC parsing here — the outer {@code OscTrackingTtyConnector}
 * (installed by {@code ConchTerminalEditor}) wraps whatever this
 * returns and handles title / CWD updates in one place.
 */
public final class SshTtyConnector implements TtyConnector {

    private final ShellChannelIo channel;
    private final InputStreamReader reader;
    private final OutputStream writer;

    public SshTtyConnector(@NotNull ShellChannelIo channel) {
        this.channel = channel;
        this.reader = new InputStreamReader(channel.remoteOutput(), StandardCharsets.UTF_8);
        this.writer = channel.remoteInput();
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        return reader.read(buf, offset, length);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        writer.write(bytes);
        writer.flush();
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    public void resize(@NotNull TermSize termSize) {
        try {
            channel.sendWindowChange(termSize.getColumns(), termSize.getRows());
        } catch (IOException ignored) {
            // A resize failure is non-fatal — the terminal keeps working
            // at the old size. Nothing useful to report back.
        }
    }

    @Override
    public int waitFor() throws InterruptedException {
        return channel.waitForClose();
    }

    @Override
    public boolean ready() throws IOException {
        return reader.ready();
    }

    @Override
    public String getName() {
        return "SSH";
    }

    @Override
    public void close() {
        channel.close();
    }
}
