package com.termlab.sftp.search;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Pane-owned in-memory cache for filename listings.
 *
 * <p>The cache only tracks state and atomic transitions. The search
 * contributor owns build orchestration and feeds results back through
 * {@link #complete(long, String, List)} or
 * {@link #fail(long, String)}.
 */
public final class FileListCache {

    public static final int MAX_PATHS = 200_000;

    public enum State {
        EMPTY,
        BUILDING,
        READY,
        FAILED
    }

    public record Snapshot(
        @NotNull State state,
        @Nullable String root,
        @Nullable String toolId,
        @NotNull List<String> paths,
        boolean truncated,
        @Nullable String failureMessage,
        long token
    ) {
    }

    private final Object lock = new Object();

    private State state = State.EMPTY;
    private @Nullable String root;
    private @Nullable String toolId;
    private @NotNull List<String> paths = List.of();
    private boolean truncated;
    private @Nullable String failureMessage;
    private long token;

    public @NotNull Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(state, root, toolId, paths, truncated, failureMessage, token);
        }
    }

    /**
     * Transition to {@link State#BUILDING} for {@code newRoot} when a
     * build is required. Returns a build token, or {@code -1} when the
     * current cache contents already cover the requested root.
     */
    public long beginBuild(@NotNull String newRoot) {
        synchronized (lock) {
            boolean alreadyReady = state == State.READY && newRoot.equals(root);
            boolean alreadyBuilding = state == State.BUILDING && newRoot.equals(root);
            if (alreadyReady || alreadyBuilding) {
                return -1L;
            }

            token += 1L;
            state = State.BUILDING;
            root = newRoot;
            toolId = null;
            paths = List.of();
            truncated = false;
            failureMessage = null;
            return token;
        }
    }

    public void complete(long buildToken, @NotNull String buildToolId, @NotNull List<String> newPaths) {
        synchronized (lock) {
            if (state != State.BUILDING || token != buildToken) {
                return;
            }
            boolean overflow = newPaths.size() > MAX_PATHS;
            List<String> stored = overflow
                ? List.copyOf(new ArrayList<>(newPaths.subList(0, MAX_PATHS)))
                : List.copyOf(newPaths);
            state = State.READY;
            toolId = buildToolId;
            paths = stored;
            truncated = overflow;
            failureMessage = null;
        }
    }

    public void fail(long buildToken, @NotNull String message) {
        synchronized (lock) {
            if (state != State.BUILDING || token != buildToken) {
                return;
            }
            state = State.FAILED;
            toolId = null;
            paths = List.of();
            truncated = false;
            failureMessage = message;
        }
    }

    public void cancel(long buildToken) {
        synchronized (lock) {
            if (state != State.BUILDING || token != buildToken) {
                return;
            }
            state = State.EMPTY;
            toolId = null;
            paths = List.of();
            truncated = false;
            failureMessage = null;
        }
    }

    public void invalidate() {
        synchronized (lock) {
            token += 1L;
            state = State.EMPTY;
            root = null;
            toolId = null;
            paths = List.of();
            truncated = false;
            failureMessage = null;
        }
    }
}
