package com.termlab.tunnels.model;

/**
 * Which host a tunnel connects through. Two variants:
 * <ul>
 *   <li>{@link InternalHost} — references an {@code SshHost} in the SSH
 *       plugin's {@code HostStore} by UUID.</li>
 *   <li>{@link SshConfigHost} — references a {@code Host} alias from
 *       {@code ~/.ssh/config}. MINA resolves connection details from the
 *       config file at connect time.</li>
 * </ul>
 */
public sealed interface TunnelHost permits InternalHost, SshConfigHost {
}
