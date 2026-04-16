package com.termlab.tunnels.model;

/**
 * Lifecycle state of a tunnel.
 */
public enum TunnelState {
    /** Saved but not active. */
    DISCONNECTED,
    /** Connection in progress (credential resolution, TCP handshake, auth). */
    CONNECTING,
    /** Port forwarding established and healthy. */
    ACTIVE,
    /** Connection failed or tunnel dropped mid-session. */
    ERROR
}
