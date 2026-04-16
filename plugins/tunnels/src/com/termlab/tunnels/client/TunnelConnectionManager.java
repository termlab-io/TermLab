package com.termlab.tunnels.client;

import com.termlab.tunnels.model.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application service that manages active tunnel connections. Maps
 * tunnel UUID → {@link TunnelConnection}. Handles connect, disconnect,
 * and health monitoring.
 */
public final class TunnelConnectionManager {

    private static final Logger LOG = Logger.getInstance(TunnelConnectionManager.class);

    private final Map<UUID, TunnelConnection> connections = new ConcurrentHashMap<>();

    public TunnelConnectionManager() {}

    public @Nullable TunnelConnection getConnection(@NotNull UUID tunnelId) {
        return connections.get(tunnelId);
    }

    public @NotNull TunnelState getState(@NotNull UUID tunnelId) {
        TunnelConnection conn = connections.get(tunnelId);
        return conn != null ? conn.state() : TunnelState.DISCONNECTED;
    }

    /**
     * Connect a tunnel. Runs on the EDT — the blocking MINA connect
     * must be wrapped in a Task.Modal by the caller.
     */
    public void connect(
        @NotNull SshTunnel tunnel,
        @NotNull ClientSession session
    ) throws IOException {
        TunnelConnection conn = new TunnelConnection(session);
        connections.put(tunnel.id(), conn);

        try {
            SshdSocketAddress local = new SshdSocketAddress(tunnel.bindAddress(), tunnel.bindPort());
            SshdSocketAddress remote = new SshdSocketAddress(tunnel.targetHost(), tunnel.targetPort());

            SshdSocketAddress bound;
            if (tunnel.type() == TunnelType.LOCAL) {
                bound = session.startLocalPortForwarding(local, remote);
            } else {
                bound = session.startRemotePortForwarding(remote, local);
            }
            conn.markActive(bound);
            LOG.info("TermLab tunnel: activated '" + tunnel.label()
                + "' bound=" + bound + " type=" + tunnel.type());

            // Start health monitor
            startHealthMonitor(tunnel, conn, session);
        } catch (IOException e) {
            conn.markError(e.getMessage());
            conn.close();
            throw e;
        }
    }

    public void disconnect(@NotNull UUID tunnelId) {
        TunnelConnection conn = connections.remove(tunnelId);
        if (conn != null) {
            conn.close();
            conn.markDisconnected();
            LOG.info("TermLab tunnel: disconnected tunnel " + tunnelId);
        }
    }

    public void disconnectAll() {
        for (UUID id : new ArrayList<>(connections.keySet())) {
            disconnect(id);
        }
    }

    private void startHealthMonitor(
        @NotNull SshTunnel tunnel,
        @NotNull TunnelConnection conn,
        @NotNull ClientSession session
    ) {
        Thread monitor = new Thread(() -> {
            try {
                session.waitFor(
                    EnumSet.of(ClientSession.ClientSessionEvent.CLOSED),
                    0L);
            } catch (Exception ignored) {
            }
            if (conn.state() == TunnelState.ACTIVE) {
                conn.markError("Connection lost");
                connections.remove(tunnel.id());
                ApplicationManager.getApplication().invokeLater(() -> {
                    LOG.warn("TermLab tunnel: '" + tunnel.label() + "' disconnected unexpectedly");
                });
            }
        }, "TermLab-tunnel-monitor-" + tunnel.label());
        monitor.setDaemon(true);
        monitor.start();
    }
}
