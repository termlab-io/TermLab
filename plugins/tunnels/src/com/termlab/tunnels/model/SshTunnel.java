package com.termlab.tunnels.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A saved SSH tunnel definition.
 *
 * <p>Persisted to {@code ~/.config/termlab/tunnels.json}. The actual
 * forwarding is managed by {@code TunnelConnectionManager} at runtime;
 * this record is the at-rest configuration.
 *
 * @param id           stable UUID
 * @param label        user-facing name ("prod-db-proxy")
 * @param type         LOCAL (-L) or REMOTE (-R)
 * @param host         which host to tunnel through
 * @param bindPort     local port (LOCAL) or remote port (REMOTE)
 * @param bindAddress  bind address (default "localhost")
 * @param targetHost   destination hostname
 * @param targetPort   destination port
 * @param createdAt    when the tunnel was created
 * @param updatedAt    when the tunnel was last edited
 */
public record SshTunnel(
    @NotNull UUID id,
    @NotNull String label,
    @NotNull TunnelType type,
    @NotNull TunnelHost host,
    int bindPort,
    @NotNull String bindAddress,
    @NotNull String targetHost,
    int targetPort,
    @NotNull Instant createdAt,
    @NotNull Instant updatedAt
) {
    public static final String DEFAULT_BIND_ADDRESS = "localhost";

    public SshTunnel withLabel(@NotNull String newLabel) {
        return new SshTunnel(id, newLabel, type, host, bindPort, bindAddress,
            targetHost, targetPort, createdAt, Instant.now());
    }

    public SshTunnel withEdited(
        @NotNull String newLabel,
        @NotNull TunnelType newType,
        @NotNull TunnelHost newHost,
        int newBindPort,
        @NotNull String newBindAddress,
        @NotNull String newTargetHost,
        int newTargetPort
    ) {
        return new SshTunnel(id, newLabel, newType, newHost, newBindPort,
            newBindAddress, newTargetHost, newTargetPort, createdAt, Instant.now());
    }

    public static @NotNull SshTunnel create(
        @NotNull String label,
        @NotNull TunnelType type,
        @NotNull TunnelHost host,
        int bindPort,
        @NotNull String bindAddress,
        @NotNull String targetHost,
        int targetPort
    ) {
        Instant now = Instant.now();
        return new SshTunnel(UUID.randomUUID(), label, type, host,
            bindPort, bindAddress, targetHost, targetPort, now, now);
    }
}
