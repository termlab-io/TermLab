package com.conch.core.terminal;

import com.conch.core.settings.ConchTerminalConfig;
import com.conch.sdk.TerminalSessionProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
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
    private final ConchTerminalWidget terminalWidget;
    private TtyConnector connector;

    public ConchTerminalEditor(@NotNull Project project,
                                @NotNull ConchTerminalVirtualFile file) {
        this.project = project;
        this.file = file;
        this.terminalWidget = new ConchTerminalWidget(new ConchTerminalSettings());
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
            // Wrap with OSC tracking for CWD sync and title updates
            connector = new OscTrackingTtyConnector(
                rawConnector,
                // OSC 7 — working directory changed
                newCwd -> {
                    file.setCurrentWorkingDirectory(newCwd);
                },
                // OSC 0/2 — terminal title changed (if shell emits them)
                this::updateTabTitle
            );

            terminalWidget.createTerminalSession(connector);
            terminalWidget.start();

            // Watch for shell exit and close the tab automatically
            startExitWatcher();
        }
    }

    private void startExitWatcher() {
        Thread watcher = new Thread(() -> {
            try {
                connector.waitFor();
            } catch (InterruptedException ignored) {
                return;
            }
            // Shell exited — close this tab on the EDT
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    FileEditorManager.getInstance(project).closeFile(file);
                }
            });
        }, "Conch-exit-watcher-" + file.getSessionId());
        watcher.setDaemon(true);
        watcher.start();
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

    private void updateTabTitle(String newTitle) {
        // If the user has explicitly renamed this tab, OSC 0/2 updates from
        // the shell shouldn't clobber the user's choice.
        if (file.hasManualTitleOverride()) return;

        if (newTitle != null && !newTitle.isBlank()) {
            file.setTerminalTitle(newTitle);
            // Trigger tab title refresh via EditorTabTitleProvider
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    FileEditorManager.getInstance(project).updateFilePresentation(file);
                }
            });
        }
    }

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
