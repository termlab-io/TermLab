package com.conch.tunnels.client;

import com.conch.tunnels.model.TunnelState;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Handle for one active tunnel. Owns a {@link ClientSession} and the
 * bound forwarding address. Lifecycle: CONNECTING → ACTIVE → DISCONNECTED
 * (or ERROR at any point).
 */
public final class TunnelConnection implements AutoCloseable {

    private volatile ClientSession session;
    private volatile SshdSocketAddress boundAddress;
    private volatile TunnelState state;
    private volatile String errorMessage;

    public TunnelConnection(@NotNull ClientSession session) {
        this.session = session;
        this.state = TunnelState.CONNECTING;
    }

    public @Nullable ClientSession session() { return session; }
    public @Nullable SshdSocketAddress boundAddress() { return boundAddress; }
    public @NotNull TunnelState state() { return state; }
    public @Nullable String errorMessage() { return errorMessage; }

    public void markActive(@NotNull SshdSocketAddress bound) {
        this.boundAddress = bound;
        this.state = TunnelState.ACTIVE;
        this.errorMessage = null;
    }

    public void markError(@NotNull String message) {
        this.state = TunnelState.ERROR;
        this.errorMessage = message;
    }

    public void markDisconnected() {
        this.state = TunnelState.DISCONNECTED;
        this.errorMessage = null;
    }

    @Override
    public void close() {
        if (session != null) {
            session.close(true);
            session = null;
        }
        if (state == TunnelState.ACTIVE || state == TunnelState.CONNECTING) {
            state = TunnelState.DISCONNECTED;
        }
    }
}
