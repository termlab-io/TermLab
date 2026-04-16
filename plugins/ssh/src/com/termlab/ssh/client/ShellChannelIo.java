package com.termlab.ssh.client;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Minimal interface the {@link SshTtyConnector} needs from a MINA
 * {@code ChannelShell}. Extracted so the connector can be unit-tested
 * with an in-memory pipe pair instead of a real SSH channel — MINA's
 * {@code ChannelShell} is awkward to subclass because its constructor
 * wants MINA-internal config types that aren't reachable from a test
 * harness.
 *
 * <p>Real production implementation lives in {@code MinaShellChannelIo}
 * (inside {@link SshConnection}); tests use a lightweight in-memory fake.
 */
public interface ShellChannelIo {

    /** @return the stream the remote writes into (our stdin-from-remote). */
    @NotNull InputStream remoteOutput();

    /** @return the stream we write into to send to the remote (our stdout-to-remote). */
    @NotNull OutputStream remoteInput();

    /** @return whether the channel is still healthy and connected. */
    boolean isConnected();

    /** Send an SSH {@code window-change} packet with new dimensions. */
    void sendWindowChange(int columns, int rows) throws IOException;

    /**
     * Block until the channel is closed by either side.
     *
     * @return the remote's exit status, or 0 if none was reported
     */
    int waitForClose() throws InterruptedException;

    /** Close the channel. Idempotent. */
    void close();
}
