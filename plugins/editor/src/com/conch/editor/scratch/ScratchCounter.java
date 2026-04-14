package com.conch.editor.scratch;

import java.util.concurrent.atomic.AtomicInteger;

/** Session-scoped scratch file counter. Reset every launch. */
public final class ScratchCounter {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    private ScratchCounter() {}

    public static int next() {
        return COUNTER.incrementAndGet();
    }
}
