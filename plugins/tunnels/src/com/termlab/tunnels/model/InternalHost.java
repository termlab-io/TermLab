package com.termlab.tunnels.model;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * References an {@code SshHost} in the SSH plugin's {@code HostStore}.
 *
 * @param hostId the host's stable UUID
 */
public record InternalHost(@NotNull UUID hostId) implements TunnelHost {
}
