package com.conch.minecraftadmin.client;

import org.jetbrains.annotations.NotNull;

/** Live session handle for one AMP panel. Immutable except for rotate() on 401. */
public final class AmpSession {
    private final String baseUrl;
    private volatile String sessionId;

    public AmpSession(@NotNull String baseUrl, @NotNull String sessionId) {
        this.baseUrl = baseUrl;
        this.sessionId = sessionId;
    }

    public @NotNull String baseUrl() { return baseUrl; }
    public @NotNull String sessionId() { return sessionId; }
    void rotate(@NotNull String newSessionId) { this.sessionId = newSessionId; }
}
