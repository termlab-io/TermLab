package com.termlab.core.terminal;

import com.termlab.core.settings.TermLabTerminalConfig;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.editor.colors.EditorColorsListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.ide.ui.UISettings;
import com.intellij.ide.ui.UISettingsListener;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;
import com.intellij.ui.Gray;
import com.intellij.ui.TextIcon;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.datatransfer.DataFlavor;
import java.io.IOException;
import java.beans.PropertyChangeListener;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.awt.BorderLayout;

public final class TermLabTerminalEditor extends UserDataHolderBase implements FileEditor {
    private final Project project;
    private final TermLabTerminalVirtualFile file;
    private final TermLabTerminalVirtualFile.SharedTerminalSession session;
    private final TermLabTerminalWidget terminalWidget;
    private final JPanel rootPanel;
    private final com.intellij.util.messages.MessageBusConnection messageBusConnection;
    private final TtyConnector connector;
    private final ActionGroup tabActions;
    private final ComponentAdapter resizeListener = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
            scheduleTerminalResize();
        }

        @Override
        public void componentShown(ComponentEvent e) {
            scheduleTerminalResize();
        }
    };
    private final HierarchyListener hierarchyListener = e -> {
        long flags = e.getChangeFlags();
        if ((flags & HierarchyEvent.PARENT_CHANGED) != 0
            || (flags & HierarchyEvent.SHOWING_CHANGED) != 0
            || (flags & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
            scheduleTerminalResize();
        }
    };
    private final FocusAdapter focusListener = new FocusAdapter() {
        @Override
        public void focusGained(FocusEvent e) {
            TermLabMultiExecManager.getInstance(project).onTerminalFocused(file);
        }
    };
    private boolean resizeQueued;

    public TermLabTerminalEditor(@NotNull Project project,
                                @NotNull TermLabTerminalVirtualFile file) {
        this.project = project;
        this.file = file;
        this.session = file.acquireSession(project);
        this.terminalWidget = session.getWidget();
        this.connector = session.getConnector();
        this.messageBusConnection = ApplicationManager.getApplication().getMessageBus().connect();
        this.tabActions = new DefaultActionGroup(new SelectTabNumberHintAction(project, file));
        this.rootPanel = new JPanel(new BorderLayout());
        rootPanel.add(terminalWidget, BorderLayout.CENTER);
        installAppearanceListeners();
        applyTerminalAppearance();
        installFileDropHandler();
        installResizeListeners();
        installFocusListeners();
        scheduleTerminalResize();
    }

    @Override public @NotNull JComponent getComponent() { return rootPanel; }
    @Override public @Nullable JComponent getPreferredFocusedComponent() { return terminalWidget; }
    @Override public @NotNull String getName() { return "Terminal"; }
    @Override public boolean isModified() { return false; }
    @Override public boolean isValid() { return connector != null && connector.isConnected(); }
    @Override public VirtualFile getFile() { return file; }
    @Override public @Nullable ActionGroup getTabActions() { return tabActions; }
    @Override public void setState(@NotNull FileEditorState state) {}
    @Override public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {}
    @Override public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {}

    @Override
    public void dispose() {
        terminalWidget.removeComponentListener(resizeListener);
        terminalWidget.removeHierarchyListener(hierarchyListener);
        terminalWidget.removeFocusListener(focusListener);
        terminalWidget.getTerminalPanel().removeComponentListener(resizeListener);
        terminalWidget.getTerminalPanel().removeHierarchyListener(hierarchyListener);
        terminalWidget.getTerminalPanel().removeFocusListener(focusListener);
        messageBusConnection.disconnect();
        session.release();
    }

    private void installAppearanceListeners() {
        messageBusConnection.subscribe(EditorColorsManager.TOPIC, new EditorColorsListener() {
            @Override
            public void globalSchemeChange(@Nullable EditorColorsScheme scheme) {
                scheduleAppearanceRefresh();
            }
        });
        messageBusConnection.subscribe(UISettingsListener.TOPIC, new UISettingsListener() {
            @Override
            public void uiSettingsChanged(@NotNull UISettings uiSettings) {
                scheduleAppearanceRefresh();
            }
        });
    }

    private void applyTerminalAppearance() {
        terminalWidget.refreshAppearance();
        terminalWidget.refreshFontMetrics();
        applyCursorShape();
    }

    private void scheduleAppearanceRefresh() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            applyTerminalAppearance();
            scheduleTerminalResize();
        });
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

    public void refreshMultiExecHeader() {
    }

    private void installResizeListeners() {
        terminalWidget.addComponentListener(resizeListener);
        terminalWidget.addHierarchyListener(hierarchyListener);
        terminalWidget.getTerminalPanel().addComponentListener(resizeListener);
        terminalWidget.getTerminalPanel().addHierarchyListener(hierarchyListener);
    }

    private void installFocusListeners() {
        terminalWidget.addFocusListener(focusListener);
        terminalWidget.getTerminalPanel().addFocusListener(focusListener);
    }

    private void scheduleTerminalResize() {
        if (resizeQueued || project.isDisposed()) {
            return;
        }
        resizeQueued = true;
        ApplicationManager.getApplication().invokeLater(() -> {
            resizeQueued = false;
            if (project.isDisposed() || !isValid()) {
                return;
            }
            if (!terminalWidget.isShowing() || terminalWidget.getTerminalPanel().getWidth() <= 0
                || terminalWidget.getTerminalPanel().getHeight() <= 0) {
                return;
            }
            terminalWidget.getTerminalStarter().postResize(
                terminalWidget.getTerminalPanel().getTerminalSizeFromComponent(),
                RequestOrigin.User);
        }, project.getDisposed());
    }

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

    private static final class SelectTabNumberHintAction extends AnAction {
        private final Project project;
        private final VirtualFile file;

        private SelectTabNumberHintAction(@NotNull Project project, @NotNull VirtualFile file) {
            this.project = project;
            this.file = file;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            EditorWindow window = TermLabTabNumberSupport.resolveWindow(project, e.getData(EditorWindow.DATA_KEY), file);
            if (window == null) {
                return;
            }
            window.setAsCurrentWindow(true);
            window.setSelectedComposite(file, true);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            if (project.isDisposed()) {
                e.getPresentation().setEnabledAndVisible(false);
                return;
            }

            String shortcutHint = TermLabTabNumberSupport.getShortcutHint(project, e.getData(EditorWindow.DATA_KEY), file);
            if (shortcutHint == null) {
                e.getPresentation().setEnabledAndVisible(false);
                e.getPresentation().setIcon(null);
                return;
            }

            TextIcon icon = new TextIcon(shortcutHint, UIUtil.getLabelForeground(), Gray.TRANSPARENT, 0);
            icon.setFont(JBFont.label().deriveFont(UIUtil.getFontSize(UIUtil.FontSize.MINI) - 1));
            icon.setInsets(0, 6, 0, 2);

            e.getPresentation().setText("");
            e.getPresentation().setDescription("Shortcut for selecting this tab");
            e.getPresentation().setIcon(icon);
            e.getPresentation().setEnabledAndVisible(true);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }
}
