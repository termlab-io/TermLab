package com.termlab.core.terminal;

import com.termlab.core.settings.TermLabTerminalConfig;
import com.termlab.sdk.TerminalSessionProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerKeys;
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
import java.awt.datatransfer.DataFlavor;
import java.io.IOException;
import java.beans.PropertyChangeListener;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TermLabTerminalEditor extends UserDataHolderBase implements FileEditor {
    private final Project project;
    private final TermLabTerminalVirtualFile file;
    private final TermLabTerminalWidget terminalWidget;
    private final com.intellij.util.messages.MessageBusConnection messageBusConnection;
    private TtyConnector connector;
    private volatile boolean suppressExitClose;

    public TermLabTerminalEditor(@NotNull Project project,
                                @NotNull TermLabTerminalVirtualFile file) {
        this.project = project;
        this.file = file;
        this.terminalWidget = new TermLabTerminalWidget(new TermLabTerminalSettings());
        this.messageBusConnection = ApplicationManager.getApplication().getMessageBus().connect();
        installAppearanceListeners();
        initTerminalSession();
        installFileDropHandler();
    }

    private void initTerminalSession() {
        // The file owns the context — for local terminals this is a
        // lambda exposing the CWD, but providers like SSH can stash a
        // richer context via TermLabTerminalVirtualFile.setSessionContext()
        // before the editor is opened.
        TerminalSessionProvider.SessionContext context = file.getSessionContext();
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
            applyTerminalAppearance();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    applyTerminalAppearance();
                }
            });

            // Watch for shell exit and close the tab automatically
            startExitWatcher();
        }
    }

    private void startExitWatcher() {
        TtyConnector watchedConnector = connector;
        Thread watcher = new Thread(() -> {
            try {
                watchedConnector.waitFor();
            } catch (InterruptedException ignored) {
                return;
            }
            // Shell exited — close this tab on the EDT
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()
                    && !suppressExitClose
                    && watchedConnector == connector
                    && file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) != Boolean.TRUE) {
                    FileEditorManager.getInstance(project).closeFile(file);
                }
            });
        }, "TermLab-exit-watcher-" + file.getSessionId());
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
        messageBusConnection.disconnect();
        suppressExitClose = true;
        if (connector != null) {
            try { connector.close(); } catch (Exception ignored) {}
        }
    }

    private void installAppearanceListeners() {
        messageBusConnection.subscribe(EditorColorsManager.TOPIC, new EditorColorsListener() {
            @Override
            public void globalSchemeChange(@Nullable EditorColorsScheme scheme) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) {
                        return;
                    }
                    applyTerminalAppearance();
                });
            }
        });
    }

    private void applyTerminalAppearance() {
        terminalWidget.refreshAppearance();
        applyCursorShape();
    }

    private void applyCursorShape() {
        TermLabTerminalConfig config = TermLabTerminalConfig.getInstance();
        TermLabTerminalConfig.State s = config != null ? config.getState() : null;
        String shape = (s != null) ? s.cursorShape : "BLOCK";

        CursorShape cursorShape = switch (shape) {
            case "UNDERLINE" -> CursorShape.STEADY_UNDERLINE;
            case "VERTICAL_BAR" -> CursorShape.STEADY_VERTICAL_BAR;
            default -> CursorShape.STEADY_BLOCK;
        };
        terminalWidget.getTerminalPanel().setCursorShape(cursorShape);
        terminalWidget.getTerminalPanel().setDefaultCursorShape(cursorShape);
        terminalWidget.getTerminalPanel().repaint();
    }

    public @NotNull TermLabTerminalVirtualFile getTerminalFile() { return file; }
    public @NotNull JediTermWidget getTerminalWidget() { return terminalWidget; }

    private void installFileDropHandler() {
        TransferHandler handler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDrop() && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support) || connector == null || !connector.isConnected()) return false;
                try {
                    @SuppressWarnings("unchecked")
                    List<Object> dropped = (List<Object>) support.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                    List<Path> paths = new ArrayList<>(dropped.size());
                    for (Object item : dropped) {
                        if (item instanceof java.io.File file) {
                            paths.add(file.toPath());
                        }
                    }
                    String text = TerminalDroppedPathFormatter.formatDroppedPaths(paths);
                    if (text.isEmpty()) return false;
                    if (!terminalWidget.pasteText(text)) {
                        connector.write(text);
                    }
                    terminalWidget.getTerminalPanel().requestFocusInWindow();
                    return true;
                } catch (UnsupportedOperationException | IOException | java.awt.datatransfer.UnsupportedFlavorException e) {
                    return false;
                }
            }
        };
        terminalWidget.setTransferHandler(handler);
        terminalWidget.getTerminalPanel().setTransferHandler(handler);
    }
}
