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

    public enum TablePane {
        LOCAL,
        REMOTE
    }

    public static final class State {
        public @Nullable String lastLocalPath;
        public @Nullable String lastRemoteHostId;
        public @Nullable String viewMode;
        public boolean showHiddenLocalFiles;
        public boolean showHiddenRemoteFiles;
        public boolean showLocalNameColumn = true;
        public boolean showLocalSizeColumn = true;
        public boolean showLocalModifiedColumn = true;
        public boolean showLocalPermissionsColumn = true;
        public boolean showRemoteNameColumn = true;
        public boolean showRemoteSizeColumn = true;
        public boolean showRemoteModifiedColumn = true;
        public boolean showRemotePermissionsColumn = true;
        public int localNameColumnWidth;
        public int localSizeColumnWidth;
        public int localModifiedColumnWidth;
        public int localPermissionsColumnWidth;
        public int remoteNameColumnWidth;
        public int remoteSizeColumnWidth;
        public int remoteModifiedColumnWidth;
        public int remotePermissionsColumnWidth;
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

    public boolean isColumnVisible(@NotNull TablePane pane, int columnIndex) {
        return switch (pane) {
            case LOCAL -> getLocalColumnVisible(columnIndex);
            case REMOTE -> getRemoteColumnVisible(columnIndex);
        };
    }

    public void setColumnVisible(@NotNull TablePane pane, int columnIndex, boolean visible) {
        switch (pane) {
            case LOCAL -> setLocalColumnVisible(columnIndex, visible);
            case REMOTE -> setRemoteColumnVisible(columnIndex, visible);
        }
    }

    public int getColumnWidth(@NotNull TablePane pane, int columnIndex) {
        return switch (pane) {
            case LOCAL -> getLocalColumnWidth(columnIndex);
            case REMOTE -> getRemoteColumnWidth(columnIndex);
        };
    }

    public void setColumnWidth(@NotNull TablePane pane, int columnIndex, int width) {
        if (width <= 0) return;
        switch (pane) {
            case LOCAL -> setLocalColumnWidth(columnIndex, width);
            case REMOTE -> setRemoteColumnWidth(columnIndex, width);
        }
    }

    private int getLocalColumnWidth(int columnIndex) {
        return switch (columnIndex) {
            case 0 -> state.localNameColumnWidth;
            case 1 -> state.localSizeColumnWidth;
            case 2 -> state.localModifiedColumnWidth;
            case 3 -> state.localPermissionsColumnWidth;
            default -> 0;
        };
    }

    private int getRemoteColumnWidth(int columnIndex) {
        return switch (columnIndex) {
            case 0 -> state.remoteNameColumnWidth;
            case 1 -> state.remoteSizeColumnWidth;
            case 2 -> state.remoteModifiedColumnWidth;
            case 3 -> state.remotePermissionsColumnWidth;
            default -> 0;
        };
    }

    private void setLocalColumnWidth(int columnIndex, int width) {
        switch (columnIndex) {
            case 0 -> state.localNameColumnWidth = width;
            case 1 -> state.localSizeColumnWidth = width;
            case 2 -> state.localModifiedColumnWidth = width;
            case 3 -> state.localPermissionsColumnWidth = width;
            default -> {
            }
        }
    }

    private void setRemoteColumnWidth(int columnIndex, int width) {
        switch (columnIndex) {
            case 0 -> state.remoteNameColumnWidth = width;
            case 1 -> state.remoteSizeColumnWidth = width;
            case 2 -> state.remoteModifiedColumnWidth = width;
            case 3 -> state.remotePermissionsColumnWidth = width;
            default -> {
            }
        }
    }

    private boolean getLocalColumnVisible(int columnIndex) {
        return switch (columnIndex) {
            case 0 -> state.showLocalNameColumn;
            case 1 -> state.showLocalSizeColumn;
            case 2 -> state.showLocalModifiedColumn;
            case 3 -> state.showLocalPermissionsColumn;
            default -> true;
        };
    }

    private boolean getRemoteColumnVisible(int columnIndex) {
        return switch (columnIndex) {
            case 0 -> state.showRemoteNameColumn;
            case 1 -> state.showRemoteSizeColumn;
            case 2 -> state.showRemoteModifiedColumn;
            case 3 -> state.showRemotePermissionsColumn;
            default -> true;
        };
    }

    private void setLocalColumnVisible(int columnIndex, boolean visible) {
        switch (columnIndex) {
            case 0 -> state.showLocalNameColumn = visible;
            case 1 -> state.showLocalSizeColumn = visible;
            case 2 -> state.showLocalModifiedColumn = visible;
            case 3 -> state.showLocalPermissionsColumn = visible;
            default -> {
            }
        }
    }

    private void setRemoteColumnVisible(int columnIndex, boolean visible) {
        switch (columnIndex) {
            case 0 -> state.showRemoteNameColumn = visible;
            case 1 -> state.showRemoteSizeColumn = visible;
            case 2 -> state.showRemoteModifiedColumn = visible;
            case 3 -> state.showRemotePermissionsColumn = visible;
            default -> {
            }
        }
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
