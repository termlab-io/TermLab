package com.termlab.ssh.client;

import com.jediterm.core.util.TermSize;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class SshTtyConnectorTest {

    @Test
    void read_passesThroughFromRemoteOutput() throws Exception {
        FakeShellChannelIo io = new FakeShellChannelIo("hello from remote");
        SshTtyConnector connector = new SshTtyConnector(io);

        char[] buf = new char[64];
        int count = connector.read(buf, 0, buf.length);
        assertEquals(17, count);
        assertEquals("hello from remote", new String(buf, 0, count));
    }

    @Test
    void write_bytes_passesThroughToRemoteInput() throws Exception {
        FakeShellChannelIo io = new FakeShellChannelIo("");
        SshTtyConnector connector = new SshTtyConnector(io);

        connector.write("ls -la\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("ls -la\n", io.writtenAsString());
    }

    @Test
    void write_string_passesThroughToRemoteInput() throws Exception {
        FakeShellChannelIo io = new FakeShellChannelIo("");
        SshTtyConnector connector = new SshTtyConnector(io);

        connector.write("pwd\n");
        assertEquals("pwd\n", io.writtenAsString());
    }

    @Test
    void resize_forwardsColumnsAndRowsToChannel() throws Exception {
        FakeShellChannelIo io = new FakeShellChannelIo("");
        SshTtyConnector connector = new SshTtyConnector(io);

        connector.resize(new TermSize(132, 50));
        assertEquals(1, io.windowChangeCount);
        assertEquals(132, io.lastWindowChangeCols);
        assertEquals(50, io.lastWindowChangeRows);
    }

    @Test
    void resize_swallowsIoFailure() {
        FakeShellChannelIo io = new FakeShellChannelIo("");
        io.failOnWindowChange = true;
        SshTtyConnector connector = new SshTtyConnector(io);

        assertDoesNotThrow(() -> connector.resize(new TermSize(80, 24)));
    }

    @Test
    void isConnected_tracksChannelState() throws Exception {
        FakeShellChannelIo io = new FakeShellChannelIo("");
        SshTtyConnector connector = new SshTtyConnector(io);

        assertTrue(connector.isConnected());
        io.connected = false;
        assertFalse(connector.isConnected());
    }

    @Test
    void getName_isSsh() {
        FakeShellChannelIo io = new FakeShellChannelIo("");
        SshTtyConnector connector = new SshTtyConnector(io);
        assertEquals("SSH", connector.getName());
    }

    @Test
    void close_closesChannel() throws Exception {
        FakeShellChannelIo io = new FakeShellChannelIo("");
        SshTtyConnector connector = new SshTtyConnector(io);
        assertFalse(io.closed);
        connector.close();
        assertTrue(io.closed);
    }

    @Test
    void waitFor_returnsExitStatus() throws Exception {
        FakeShellChannelIo io = new FakeShellChannelIo("");
        io.exitStatus = 42;
        SshTtyConnector connector = new SshTtyConnector(io);
        assertEquals(42, connector.waitFor());
    }

    @Test
    void waitFor_defaultsToZero() throws Exception {
        FakeShellChannelIo io = new FakeShellChannelIo("");
        // exitStatus stays at 0 (default)
        SshTtyConnector connector = new SshTtyConnector(io);
        assertEquals(0, connector.waitFor());
    }

    @Test
    void ready_reflectsUnderlyingStream() throws Exception {
        FakeShellChannelIo io = new FakeShellChannelIo("some data");
        SshTtyConnector connector = new SshTtyConnector(io);
        assertTrue(connector.ready());

        FakeShellChannelIo emptyIo = new FakeShellChannelIo("");
        SshTtyConnector emptyConnector = new SshTtyConnector(emptyIo);
        assertFalse(emptyConnector.ready());
    }

    // -- fake channel ---------------------------------------------------------

    private static final class FakeShellChannelIo implements ShellChannelIo {
        private final InputStream remoteOutput;
        private final ByteArrayOutputStream remoteInput = new ByteArrayOutputStream();

        boolean connected = true;
        boolean closed = false;
        int exitStatus = 0;
        int windowChangeCount = 0;
        int lastWindowChangeCols = -1;
        int lastWindowChangeRows = -1;
        boolean failOnWindowChange = false;
        final CountDownLatch closedLatch = new CountDownLatch(1);

        FakeShellChannelIo(@NotNull String initialRemoteOutput) {
            this.remoteOutput = new ByteArrayInputStream(
                initialRemoteOutput.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public @NotNull InputStream remoteOutput() { return remoteOutput; }

        @Override
        public @NotNull OutputStream remoteInput() { return remoteInput; }

        @Override
        public boolean isConnected() { return connected; }

        @Override
        public void sendWindowChange(int columns, int rows) throws IOException {
            if (failOnWindowChange) {
                throw new IOException("simulated failure");
            }
            windowChangeCount++;
            lastWindowChangeCols = columns;
            lastWindowChangeRows = rows;
        }

        @Override
        public int waitForClose() {
            return exitStatus;
        }

        @Override
        public void close() {
            closed = true;
            closedLatch.countDown();
        }

        String writtenAsString() {
            return remoteInput.toString(StandardCharsets.UTF_8);
        }
    }
}
