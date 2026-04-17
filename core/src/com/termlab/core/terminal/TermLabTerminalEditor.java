package com.termlab.core.terminal;

import com.termlab.core.settings.TermLabTerminalConfig;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
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
import java.awt.datatransfer.DataFlavor;
import java.io.IOException;
import java.beans.PropertyChangeListener;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TermLabTerminalEditor extends UserDataHolderBase implements FileEditor {
    private final Project project;
    private final TermLabTerminalVirtualFile file;
    private final TermLabTerminalVirtualFile.SharedTerminalSession session;
    private final TermLabTerminalWidget terminalWidget;
    private final com.intellij.util.messages.MessageBusConnection messageBusConnection;
    private final TtyConnector connector;

    public TermLabTerminalEditor(@NotNull Project project,
                                @NotNull TermLabTerminalVirtualFile file) {
        this.project = project;
        this.file = file;
        this.session = file.acquireSession(project);
        this.terminalWidget = session.getWidget();
        this.connector = session.getConnector();
        this.messageBusConnection = ApplicationManager.getApplication().getMessageBus().connect();
        installAppearanceListeners();
        applyTerminalAppearance();
        installFileDropHandler();
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
        messageBusConnection.disconnect();
        session.release();
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
