package com.conch.core.terminal;

import com.conch.core.explorer.CwdSyncManager;
import com.conch.core.settings.ConchTerminalConfig;
import com.conch.sdk.TerminalSessionProvider;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.*;
import java.beans.PropertyChangeListener;

public final class ConchTerminalEditor extends UserDataHolderBase implements FileEditor {
    private final Project project;
    private final ConchTerminalVirtualFile file;
    private final JediTermWidget terminalWidget;
    private TtyConnector connector;

    public ConchTerminalEditor(@NotNull Project project,
                                @NotNull ConchTerminalVirtualFile file) {
        this.project = project;
        this.file = file;
        this.terminalWidget = new JediTermWidget(new ConchTerminalSettings());
        applyCursorShape();
        initTerminalSession();
    }

    private void initTerminalSession() {
        String cwd = file.getCurrentWorkingDirectory();
        if (cwd == null) cwd = System.getProperty("user.home");
        String workDir = cwd;

        TerminalSessionProvider.SessionContext context = () -> workDir;
        TtyConnector rawConnector = file.getProvider().createSession(context);

        if (rawConnector != null) {
            // Wrap with OSC 7 tracking for CWD sync
            connector = new OscTrackingTtyConnector(rawConnector, newCwd -> {
                file.setCurrentWorkingDirectory(newCwd);
                CwdSyncManager cwdSync = CwdSyncManager.getInstance(project);
                cwdSync.onWorkingDirectoryChanged(newCwd);
            });

            terminalWidget.createTerminalSession(connector);
            terminalWidget.start();
        }
    }

    @Override public @NotNull JComponent getComponent() { return terminalWidget; }
    @Override public @Nullable JComponent getPreferredFocusedComponent() { return terminalWidget; }
    @Override public @NotNull String getName() { return "Terminal"; }
    @Override public boolean isModified() { return false; }
    @Override public boolean isValid() { return connector != null && connector.isConnected(); }
    @Override public VirtualFile getFile() { return file; }
    @Override public void setState(@NotNull FileEditorState state) {}
    @Override public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {}
    @Override public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {}

    @Override
    public void dispose() {
        if (connector != null) {
            try { connector.close(); } catch (Exception ignored) {}
        }
    }

    private void applyCursorShape() {
        ConchTerminalConfig config = ConchTerminalConfig.getInstance();
        ConchTerminalConfig.State s = config != null ? config.getState() : null;
        String shape = (s != null) ? s.cursorShape : "BLOCK";

        CursorShape cursorShape = switch (shape) {
            case "UNDERLINE" -> CursorShape.STEADY_UNDERLINE;
            case "VERTICAL_BAR" -> CursorShape.STEADY_VERTICAL_BAR;
            default -> CursorShape.STEADY_BLOCK;
        };
        terminalWidget.getTerminalPanel().setDefaultCursorShape(cursorShape);
    }

    public @NotNull ConchTerminalVirtualFile getTerminalFile() { return file; }
    public @NotNull JediTermWidget getTerminalWidget() { return terminalWidget; }
}
