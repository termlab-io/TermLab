package com.conch.ssh.client;

import org.jetbrains.annotations.NotNull;

/**
 * Typed failure for {@link ConchSshClient#connect}. The {@link #kind}
 * drives UX — the session provider decides whether to re-prompt for
 * credentials, show a scary MITM warning, or just surface a generic
 * "can't connect" message based on this category.
 */
public final class SshConnectException extends Exception {

    public enum Kind {
        /** Couldn't reach the host at the TCP layer (DNS, firewall, down). */
        HOST_UNREACHABLE,
        /**
         * TCP connected but the SSH handshake or authentication failed.
         * Re-prompting with different credentials is the natural recovery.
         */
        AUTH_FAILED,
        /**
         * {@code known_hosts} lookup returned {@code MISMATCH}. Hard
         * reject — never offer to "accept anyway", this is the signal
         * a known_hosts check exists to catch.
         */
        HOST_KEY_REJECTED,
        /**
         * Authenticated successfully but couldn't open the shell channel
         * (rare — usually means the remote sshd config forbids shell
         * sessions for this user).
         */
        CHANNEL_OPEN_FAILED,
        /** Proxy command / jump host configuration is invalid. */
        INVALID_PROXY_CONFIG,
        /** Everything else. */
        UNKNOWN
    }

    private final Kind kind;

    public SshConnectException(@NotNull Kind kind, @NotNull String message) {
        super(message);
        this.kind = kind;
    }

    public SshConnectException(@NotNull Kind kind, @NotNull String message, @NotNull Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public @NotNull Kind kind() {
        return kind;
    }
}
