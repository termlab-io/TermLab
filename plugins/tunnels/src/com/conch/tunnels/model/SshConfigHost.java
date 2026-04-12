package com.conch.tunnels.model;

import org.jetbrains.annotations.NotNull;

/**
 * References a {@code Host} alias from {@code ~/.ssh/config}. At
 * connect time, MINA resolves hostname, port, identity file, proxy
 * settings, etc. from the config file.
 *
 * @param alias the Host alias (e.g., "bastion", "prod-db")
 */
public record SshConfigHost(@NotNull String alias) implements TunnelHost {
}
