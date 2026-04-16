package com.termlab.tunnels.model;

/**
 * SSH tunnel direction.
 *
 * <ul>
 *   <li>{@link #LOCAL} — binds a local port and forwards traffic to a
 *       remote target through the SSH connection ({@code -L}).</li>
 *   <li>{@link #REMOTE} — binds a port on the remote host and forwards
 *       traffic back to a local target ({@code -R}).</li>
 * </ul>
 */
public enum TunnelType {
    LOCAL, REMOTE
}
