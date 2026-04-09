package com.conch.core.terminal;

import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OscTrackingTtyConnector implements TtyConnector {
    // OSC 7 format: \033]7;file://hostname/path\033\\ or \033]7;file://hostname/path\007
    private static final Pattern OSC7_PATTERN = Pattern.compile(
        "\\033\\]7;file://[^/]*(/.+?)(?:\\033\\\\|\\007)"
    );

    private final TtyConnector delegate;
    private final Consumer<String> cwdListener;
    private final StringBuilder buffer = new StringBuilder();

    public OscTrackingTtyConnector(@NotNull TtyConnector delegate,
                                    @NotNull Consumer<String> cwdListener) {
        this.delegate = delegate;
        this.cwdListener = cwdListener;
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        int count = delegate.read(buf, offset, length);
        if (count > 0) {
            buffer.append(buf, offset, count);
            extractOsc7();
            if (buffer.length() > 4096) {
                buffer.delete(0, buffer.length() - 512);
            }
        }
        return count;
    }

    private void extractOsc7() {
        Matcher matcher = OSC7_PATTERN.matcher(buffer);
        String lastCwd = null;
        int lastEnd = 0;
        while (matcher.find()) {
            lastCwd = matcher.group(1);
            lastEnd = matcher.end();
        }
        if (lastCwd != null) {
            cwdListener.accept(lastCwd);
            buffer.delete(0, lastEnd);
        }
    }

    @Override public boolean isConnected() { return delegate.isConnected(); }
    @Override public void write(byte[] bytes) throws IOException { delegate.write(bytes); }
    @Override public void write(String string) throws IOException { delegate.write(string); }
    @Override public int waitFor() throws InterruptedException { return delegate.waitFor(); }
    @Override public boolean ready() throws IOException { return delegate.ready(); }
    @Override public String getName() { return delegate.getName(); }
    @Override public void close() { delegate.close(); }
    @Override public void resize(Dimension termWinSize) { delegate.resize(termWinSize); }
    @Override public void resize(Dimension termWinSize, Dimension pixelSize) { delegate.resize(termWinSize, pixelSize); }
}
