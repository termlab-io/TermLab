package com.conch.sftp.persistence;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Application-level persistence for the Conch SFTP plugin. Keeps
 * track of the user's last local directory and last connected host
 * so the tool window restores sensible context across restarts.
 */
@State(
    name = "ConchSftpConfig",
    storages = @Storage("conch-sftp.xml")
)
public final class ConchSftpConfig implements PersistentStateComponent<ConchSftpConfig.State> {

    public static final class State {
        public @Nullable String lastLocalPath;
        public @Nullable String lastRemoteHostId;
    }

    private State state = new State();

    public static @NotNull ConchSftpConfig getInstance() {
        return ApplicationManager.getApplication().getService(ConchSftpConfig.class);
    }

    public @Nullable String getLastLocalPath() {
        return state.lastLocalPath;
    }

    public void setLastLocalPath(@Nullable String path) {
        state.lastLocalPath = path;
    }

    public @Nullable String getLastRemoteHostId() {
        return state.lastRemoteHostId;
    }

    public void setLastRemoteHostId(@Nullable String hostId) {
        state.lastRemoteHostId = hostId;
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }
}
