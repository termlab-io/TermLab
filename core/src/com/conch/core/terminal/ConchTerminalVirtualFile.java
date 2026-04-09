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
