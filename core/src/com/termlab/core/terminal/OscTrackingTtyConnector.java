package com.termlab.core.terminal;

import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;

import com.jediterm.core.util.TermSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps a TtyConnector and:
 * <ul>
 *   <li>Intercepts OSC escape sequences from terminal output:
 *     <ul>
 *       <li>OSC 7 (working directory): {@code file://hostname/path}</li>
 *       <li>OSC 0/2 (window title): text set by shell/programs (e.g. vim, codex)</li>
 *     </ul>
 *   </li>
 *   <li>Fixes JediTerm's broken Device Attributes responses. JediTerm replies to
 *       BOTH primary DA ({@code ESC[c}) and secondary DA ({@code ESC[>c}) queries
 *       with {@code ESC[?6c}. The primary form is mostly fine but undersized; the
 *       secondary form is wholly invalid (DA2 must use {@code ESC[>...c}). tmux
 *       can't parse either response cleanly and leaks the bytes through to its
 *       active pane as visible garbage. We track DA queries from the program
 *       output, then on the matching JediTerm write, substitute the correct
 *       xterm-style response: {@code ESC[?1;2c} for primary, {@code ESC[>0;0;0c}
 *       for secondary.</li>
 * </ul>
 *
 * <p>The DA queue is touched only by the JediTerm emulator thread: reads happen
 * via {@link #read} from the emulator's main loop, and writes for non-user-input
 * traffic (i.e. terminal-generated responses) are dispatched synchronously by
 * {@link TermLabTerminalStarter} on that same thread. No synchronization required.
 */
public final class OscTrackingTtyConnector implements TtyConnector {
    // ESC = \x1b, BEL = \x07, ST = ESC + backslash
    // OSC 7: ESC]7;file://hostname/path followed by BEL or ST
    private static final Pattern OSC7_PATTERN = Pattern.compile(
        "\\x1b\\]7;file://[^/]*(/.+?)(?:\\x1b\\\\|\\x07)"
    );

    // OSC 0 or OSC 2: ESC]0;title or ESC]2;title followed by BEL or ST
    private static final Pattern OSC_TITLE_PATTERN = Pattern.compile(
        "\\x1b\\][02];(.+?)(?:\\x1b\\\\|\\x07)"
    );

    // Device Attributes queries from the running program:
    //   ESC[c   — Primary DA   (group 1 empty)
    //   ESC[>c  — Secondary DA (group 1 = ">")
    private static final Pattern DA_QUERY_PATTERN = Pattern.compile("\\x1b\\[(>?)c");

    // JediTerm's broken DA response — same bytes for both primary and secondary.
    private static final byte[] JEDITERM_DA_BYTES = {0x1B, '[', '?', '6', 'c'};
    private static final String JEDITERM_DA_STRING = new String(JEDITERM_DA_BYTES, StandardCharsets.US_ASCII);

    // Proper xterm-style replies:
    //   Primary DA   → ESC[?1;2c   (VT100 + Advanced Video Option)
    //   Secondary DA → ESC[>0;0;0c (VT100, firmware 0, ROM 0)
    private static final byte[] PROPER_DA1 = {0x1B, '[', '?', '1', ';', '2', 'c'};
    private static final byte[] PROPER_DA2 = {0x1B, '[', '>', '0', ';', '0', ';', '0', 'c'};
    private static final int MAX_BUFFER_CHARS = 4096;
    private static final int KEEP_BUFFER_CHARS = 512;
    private static final int MAX_PENDING_DA_QUERIES = 1024;

    private final TtyConnector delegate;
    private final Consumer<String> cwdListener;
    private final Consumer<String> titleListener;
    private final StringBuilder buffer = new StringBuilder();
    /** Buffer index from which DA query scanning should continue. */
    private int daScanOffset = 0;
    /** Pending DA queries in order of arrival; {@code true} = secondary DA, {@code false} = primary DA. */
    private final Deque<Boolean> pendingDaQueries = new ArrayDeque<>();

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
            trackDaQueries();
            trimBufferIfNeeded();
        }
        return count;
    }

    private void extractOsc() {
        int lastEnd = 0;

        // Extract OSC 7 (CWD)
        Matcher cwdMatcher = OSC7_PATTERN.matcher(buffer);
        while (cwdMatcher.find()) {
            cwdListener.accept(cwdMatcher.group(1));
            lastEnd = Math.max(lastEnd, cwdMatcher.end());
        }

        // Extract OSC 0/2 (title)
        Matcher titleMatcher = OSC_TITLE_PATTERN.matcher(buffer);
        while (titleMatcher.find()) {
            titleListener.accept(titleMatcher.group(1));
            lastEnd = Math.max(lastEnd, titleMatcher.end());
        }

        if (lastEnd > 0) {
            buffer.delete(0, lastEnd);
            daScanOffset = Math.max(0, daScanOffset - lastEnd);
        }
    }

    private void trackDaQueries() {
        int start = Math.max(0, Math.min(daScanOffset, buffer.length()));
        Matcher m = DA_QUERY_PATTERN.matcher(buffer.subSequence(start, buffer.length()));
        while (m.find()) {
            if (pendingDaQueries.size() >= MAX_PENDING_DA_QUERIES) {
                pendingDaQueries.pollFirst();
            }
            pendingDaQueries.addLast(">".equals(m.group(1)));
        }
        daScanOffset = buffer.length();
    }

    private void trimBufferIfNeeded() {
        if (buffer.length() <= MAX_BUFFER_CHARS) {
            return;
        }
        int remove = buffer.length() - KEEP_BUFFER_CHARS;
        buffer.delete(0, remove);
        daScanOffset = Math.max(0, daScanOffset - remove);
    }

    @Override public boolean isConnected() { return delegate.isConnected(); }

    @Override
    public void write(byte[] bytes) throws IOException {
        delegate.write(fixDaResponse(bytes));
    }

    @Override
    public void write(String string) throws IOException {
        if (JEDITERM_DA_STRING.equals(string)) {
            delegate.write(fixDaResponse(JEDITERM_DA_BYTES));
        } else {
            delegate.write(string);
        }
    }

    private byte[] fixDaResponse(byte[] bytes) {
        if (!Arrays.equals(bytes, JEDITERM_DA_BYTES)) {
            return bytes;
        }
        Boolean isSecondary = pendingDaQueries.pollFirst();
        // If we don't have a tracked query (e.g. response races ahead of our scan),
        // default to the primary form — that's what tmux is most likely waiting for.
        return Boolean.TRUE.equals(isSecondary) ? PROPER_DA2 : PROPER_DA1;
    }

    @Override public int waitFor() throws InterruptedException { return delegate.waitFor(); }
    @Override public boolean ready() throws IOException { return delegate.ready(); }
    @Override public String getName() { return delegate.getName(); }
    @Override public void close() { delegate.close(); }
    @Override public void resize(TermSize termSize) { delegate.resize(termSize); }
}
