package com.conch.ssh.provider;

import com.conch.sdk.TerminalSessionProvider;
import com.conch.ssh.model.SshHost;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Session context used by {@link SshSessionProvider}. Extends the SDK's
 * {@link TerminalSessionProvider.SessionContext} with an
 * {@link SshHost}, so the provider knows which host to connect to
 * without having to run its own picker first.
 *
 * <p>{@link #getWorkingDirectory()} is always {@code null} for SSH
 * sessions — the remote shell's CWD is whatever the shell's dotfiles
 * set it to, not anything Conch can (or should) force client-side.
 */
public final class SshSessionContext implements TerminalSessionProvider.SessionContext {

    private final SshHost host;

    public SshSessionContext(@NotNull SshHost host) {
        this.host = host;
    }

    public @NotNull SshHost host() {
        return host;
    }

    @Override
    public @Nullable String getWorkingDirectory() {
        return null;
    }
}
