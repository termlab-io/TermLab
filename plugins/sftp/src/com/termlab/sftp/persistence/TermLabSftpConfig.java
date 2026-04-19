package com.termlab.sftp.persistence;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Application-level persistence for the SFTP plugin. Keeps
 * track of the user's last local directory and last connected host
 * so the tool window restores sensible context across restarts.
 */
@State(
    name = "TermLabSftpConfig",
    storages = @Storage("termlab-sftp.xml")
)
public final class TermLabSftpConfig implements PersistentStateComponent<TermLabSftpConfig.State> {

    public enum ViewMode {
        BOTH,
        LOCAL_ONLY,
        REMOTE_ONLY
    }

    public static final class State {
        public @Nullable String lastLocalPath;
        public @Nullable String lastRemoteHostId;
        public @Nullable String viewMode;
        public boolean showHiddenLocalFiles;
        public boolean showHiddenRemoteFiles;
    }

    private State state = new State();

    public static @NotNull TermLabSftpConfig getInstance() {
        return ApplicationManager.getApplication().getService(TermLabSftpConfig.class);
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

    public @NotNull ViewMode getViewMode() {
        if (state.viewMode == null || state.viewMode.isBlank()) {
            return ViewMode.BOTH;
        }
        try {
            return ViewMode.valueOf(state.viewMode);
        } catch (IllegalArgumentException ignored) {
            return ViewMode.BOTH;
        }
    }

    public void setViewMode(@NotNull ViewMode mode) {
        state.viewMode = mode.name();
    }

    public boolean isShowHiddenLocalFiles() {
        return state.showHiddenLocalFiles;
    }

    public void setShowHiddenLocalFiles(boolean showHiddenLocalFiles) {
        state.showHiddenLocalFiles = showHiddenLocalFiles;
    }

    public boolean isShowHiddenRemoteFiles() {
        return state.showHiddenRemoteFiles;
    }

    public void setShowHiddenRemoteFiles(boolean showHiddenRemoteFiles) {
        state.showHiddenRemoteFiles = showHiddenRemoteFiles;
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
