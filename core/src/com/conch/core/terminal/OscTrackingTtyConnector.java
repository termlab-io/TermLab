package com.conch.core.terminal;

import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;

import com.jediterm.core.util.TermSize;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps a TtyConnector and intercepts OSC escape sequences from terminal output:
 * - OSC 7 (working directory): file://hostname/path
 * - OSC 0/2 (window title): text set by shell/programs
 */
public final class OscTrackingTtyConnector implements TtyConnector {
    // OSC 7: \033]7;file://hostname/path followed by BEL or ST
    private static final Pattern OSC7_PATTERN = Pattern.compile(
        "\\033\\]7;file://[^/]*(/.+?)(?:\\033\\\\|\\007)"
    );

    // OSC 0 or OSC 2: \033]0;title or \033]2;title followed by BEL or ST
    private static final Pattern OSC_TITLE_PATTERN = Pattern.compile(
        "\\033\\][02];(.+?)(?:\\033\\\\|\\007)"
    );

    private final TtyConnector delegate;
    private final Consumer<String> cwdListener;
    private final Consumer<String> titleListener;
    private final StringBuilder buffer = new StringBuilder();

    public OscTrackingTtyConnector(@NotNull TtyConnector delegate,
                                    @NotNull Consumer<String> cwdListener,
                                    @NotNull Consumer<String> titleListener) {
        this.delegate = delegate;
        this.cwdListener = cwdListener;
        this.titleListener = titleListener;
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        int count = delegate.read(buf, offset, length);
        if (count > 0) {
            buffer.append(buf, offset, count);
            extractOsc();
            if (buffer.length() > 4096) {
                buffer.delete(0, buffer.length() - 512);
            }
        }
        return count;
    }

    private void extractOsc() {
        // Extract OSC 7 (CWD)
        Matcher cwdMatcher = OSC7_PATTERN.matcher(buffer);
        String lastCwd = null;
        while (cwdMatcher.find()) {
            lastCwd = cwdMatcher.group(1);
        }
        if (lastCwd != null) {
            cwdListener.accept(lastCwd);
        }

        // Extract OSC 0/2 (title)
        Matcher titleMatcher = OSC_TITLE_PATTERN.matcher(buffer);
        String lastTitle = null;
        int lastEnd = 0;
        while (titleMatcher.find()) {
            lastTitle = titleMatcher.group(1);
            lastEnd = titleMatcher.end();
        }
        if (lastTitle != null) {
            titleListener.accept(lastTitle);
        }

        // Trim buffer up to the last matched sequence
        if (lastEnd > 0) {
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
    @Override public void resize(TermSize termSize) { delegate.resize(termSize); }
}
