package com.conch.core.terminal;

import com.conch.sdk.TerminalSessionProvider;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class ConchTerminalVirtualFile extends LightVirtualFile {
    private final String sessionId;
    private final TerminalSessionProvider provider;
    private String currentWorkingDirectory;
    private volatile String terminalTitle;
    private volatile boolean manualTitleOverride;
    private volatile TerminalSessionProvider.SessionContext sessionContext;

    public ConchTerminalVirtualFile(@NotNull String title,
                                     @NotNull TerminalSessionProvider provider) {
        super(title, ConchTerminalFileType.INSTANCE, "");
        this.sessionId = UUID.randomUUID().toString();
        this.provider = provider;
    }

    public @NotNull String getSessionId() { return sessionId; }
    public @NotNull TerminalSessionProvider getProvider() { return provider; }
    public @Nullable String getCurrentWorkingDirectory() { return currentWorkingDirectory; }
    public void setCurrentWorkingDirectory(@Nullable String cwd) { this.currentWorkingDirectory = cwd; }
    public @Nullable String getTerminalTitle() { return terminalTitle; }
    public void setTerminalTitle(@Nullable String title) { this.terminalTitle = title; }

    /**
     * Optional session context the provider should receive on
     * {@link TerminalSessionProvider#createSession(TerminalSessionProvider.SessionContext) createSession}.
     *
     * <p>Plugins like the SSH session provider stash a richer context
     * here (carrying an {@code SshHost}, for example) so the editor
     * doesn't have to know about the plugin's context subtype. When
     * unset, {@link #getSessionContext()} returns a default
     * CWD-only context derived from {@link #getCurrentWorkingDirectory()}.
     */
    public void setSessionContext(@Nullable TerminalSessionProvider.SessionContext context) {
        this.sessionContext = context;
    }

    /**
     * Resolve the context to pass to {@code createSession()}. Returns the
     * caller-supplied {@link #setSessionContext(TerminalSessionProvider.SessionContext)}
     * when set; otherwise a fresh lambda that exposes
     * {@link #getCurrentWorkingDirectory()}, falling back to the user's
     * home directory.
     */
    public @NotNull TerminalSessionProvider.SessionContext getSessionContext() {
        TerminalSessionProvider.SessionContext stashed = sessionContext;
        if (stashed != null) return stashed;
        String cwd = currentWorkingDirectory != null
            ? currentWorkingDirectory
            : System.getProperty("user.home");
        return () -> cwd;
    }

    /**
     * When {@code true}, OSC 0/2 title updates from the shell are ignored and
     * the current {@link #terminalTitle} stays pinned. Set by the Rename
     * action so a user-chosen tab name doesn't get clobbered the next time
     * the shell prompt repaints. Clear it again by renaming back to an empty
     * string, which also restores auto-titling.
     */
    public boolean hasManualTitleOverride() { return manualTitleOverride; }
    public void setManualTitleOverride(boolean manualTitleOverride) {
        this.manualTitleOverride = manualTitleOverride;
    }

    @Override public boolean isWritable() { return false; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConchTerminalVirtualFile other)) return false;
        return sessionId.equals(other.sessionId);
    }

    @Override
    public int hashCode() { return sessionId.hashCode(); }
}
