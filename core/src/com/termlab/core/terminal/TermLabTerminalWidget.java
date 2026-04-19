package com.termlab.core.terminal;

import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.TerminalStarter;
import com.jediterm.terminal.TtyBasedArrayDataStream;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.emulator.mouse.MouseButtonCodes;
import com.jediterm.terminal.emulator.mouse.MouseButtonModifierFlags;
import com.jediterm.terminal.emulator.mouse.TerminalMouseListener;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalAction;
import com.jediterm.terminal.ui.TerminalActionProvider;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.input.AwtMouseEvent;
import com.jediterm.terminal.ui.input.AwtMouseWheelEvent;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Custom JediTermWidget with a dark, auto-hiding scrollbar that matches
 * the terminal background. Only visible when hovering or scrolling.
 */
public class TermLabTerminalWidget extends JediTermWidget {
    private static final String BRACKETED_PASTE_PREFIX = "\u001b[200~";
    private static final String BRACKETED_PASTE_SUFFIX = "\u001b[201~";
    private StyleState styleState;

    public TermLabTerminalWidget(SettingsProvider settingsProvider) {
        super(settingsProvider);
    }

    @Override
    protected StyleState createDefaultStyle() {
        styleState = super.createDefaultStyle();
        return styleState;
    }

    @Override
    protected TerminalStarter createTerminalStarter(@NotNull JediTerminal terminal,
                                                     @NotNull TtyConnector connector) {
        return new TermLabTerminalStarter(
            terminal,
            connector,
            new TtyBasedArrayDataStream(connector, getTypeAheadManager()::onTerminalStateChanged),
            getTypeAheadManager(),
            getExecutorServiceManager()
        );
    }

    @Override
    protected TerminalPanel createTerminalPanel(@NotNull SettingsProvider settings,
                                                @NotNull StyleState styleState,
                                                @NotNull TerminalTextBuffer textBuffer) {
        return new TermLabTerminalPanel(settings, textBuffer, styleState);
    }

    @Override
    protected JediTerminal createTerminal(@NotNull TerminalDisplay display,
                                          @NotNull TerminalTextBuffer textBuffer,
                                          @NotNull StyleState styleState) {
        return new TermLabJediTerminal(display, textBuffer, styleState);
    }

    public void refreshAppearance() {
        if (styleState != null) {
            styleState.setDefaultStyle(mySettingsProvider.getDefaultStyle());
            styleState.reset();
        }
        myTerminalPanel.repaint();
    }

    /**
     * {@link JediTerminal} subclass that fixes upstream JediTerm's
     * dropped right-click mouse reporting. In JediTerm 3.64,
     * {@code AwtMouseEvent.createButtonCode} explicitly returns
     * {@code -1} for right-click, and {@code JediTerminal.mousePressed}
     * bails out with {@code if (btnCode == -1) return}, so right-click
     * mouse events never reach the PTY. Apps like tmux / vim / htop
     * that rely on button-3 reporting (context menus, pane resize,
     * visual selection) see nothing.
     *
     * <p>Additionally, suppress out-of-bounds mouse coordinates.
     * During frame drags/resizes AWT can transiently report negative
     * terminal cells; emitting those in SGR mode produces malformed
     * escape fragments like {@code 0;86;-1m} that can leak into the
     * shell prompt.
     */
    private static final class TermLabJediTerminal extends JediTerminal {

        TermLabJediTerminal(@NotNull TerminalDisplay display,
                          @NotNull TerminalTextBuffer buffer,
                          @NotNull StyleState styleState) {
            super(display, buffer, styleState);
        }

        @Override
        public void cursorShape(@org.jetbrains.annotations.Nullable CursorShape shape) {
            // Keep the user-configured cursor style authoritative.
            // Shells and apps like tmux often emit DECSCUSR escapes
            // (CSI Ps q) to switch cursor shape dynamically, but in
            // TermLab that conflicts with the explicit Appearance
            // setting and causes the cursor to drift back to block.
        }

        @Override
        public void mousePressed(int x, int y, @NotNull com.jediterm.core.input.MouseEvent event) {
            if (isOutOfBoundsCell(x, y)) return;
            super.mousePressed(x, y, event);
        }

        @Override
        public void mouseReleased(int x, int y, @NotNull com.jediterm.core.input.MouseEvent event) {
            if (isOutOfBoundsCell(x, y)) return;
            super.mouseReleased(x, y, event);
        }

        @Override
        public void mouseMoved(int x, int y, @NotNull com.jediterm.core.input.MouseEvent event) {
            if (isOutOfBoundsCell(x, y)) return;
            super.mouseMoved(x, y, event);
        }

        @Override
        public void mouseDragged(int x, int y, @NotNull com.jediterm.core.input.MouseEvent event) {
            if (isOutOfBoundsCell(x, y)) return;
            super.mouseDragged(x, y, event);
        }

        private static boolean isOutOfBoundsCell(int x, int y) {
            return x < 0 || y < 0;
        }
    }

    /**
     * {@link TerminalPanel} subclass that fixes upstream JediTerm's
     * unconditional right-click popup. JediTerm 3.64's
     * {@code TerminalPanel.mouseClicked} always shows the context
     * menu on button-3, with no {@code isLocalMouseAction} check —
     * unlike the button-1 and button-2 branches. That means inside
     * interactive apps like tmux / vim / htop, JediTerm's menu
     * steals the right-click even though the PTY has already
     * received the mouse event via the separate
     * {@code TerminalMouseListener} path, so tmux's own menu never
     * becomes visible.
     *
     * <p>Fix: track the terminal's current {@link MouseMode} by
     * overriding {@link #terminalMouseModeSet}, and override
     * {@link #createPopupMenu} to return a popup whose
     * {@link JPopupMenu#show} is a no-op while mouse reporting is
     * active. Native TermLab actions (Shift+right-click still counts
     * as {@code isLocalMouseAction}) continue to hit the popup
     * because JediTerm's emulator only marks the mode non-NONE
     * when a remote app asks for it with {@code CSI ? 1000 h} et al.
     */
    private static final class TermLabTerminalPanel extends TerminalPanel {
        private static final Method PANEL_TO_CHAR_COORDS_METHOD = lookupPanelToCharCoordsMethod();
        private static final Method UPDATE_SELECTION_METHOD = lookupUpdateSelectionMethod();
        private static final Field TERMINAL_STARTER_FIELD = lookupTerminalStarterField();

        private final SettingsProvider settingsProvider;
        private volatile MouseMode currentMouseMode = MouseMode.MOUSE_REPORTING_NONE;
        private volatile boolean bracketedPasteModeEnabled;

        TermLabTerminalPanel(@NotNull SettingsProvider settings,
                           @NotNull TerminalTextBuffer buffer,
                           @NotNull StyleState styleState) {
            super(settings, buffer, styleState);
            this.settingsProvider = settings;
        }

        @Override
        public void terminalMouseModeSet(MouseMode mode) {
            this.currentMouseMode = mode;
            super.terminalMouseModeSet(mode);
        }

        @Override
        public void addTerminalMouseListener(final TerminalMouseListener listener) {
            RemoteMouseGestureRouter router = new RemoteMouseGestureRouter(listener);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (!settingsProvider.enableMouseReporting() || !isRemoteMouseAction(e)) return;
                    router.mousePressed(toCharCoords(e), e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (!settingsProvider.enableMouseReporting() || !isRemoteMouseAction(e)) return;
                    router.mouseReleased(toCharCoords(e), e);
                }
            });

            addMouseWheelListener(e -> {
                if (settingsProvider.enableMouseReporting() && isRemoteMouseAction(e)) {
                    clearSelection();
                    router.mouseWheelMoved(toCharCoords(e), e);
                }
                else if (getTerminalTextBuffer().isUsingAlternateBuffer() &&
                    settingsProvider.simulateMouseScrollWithArrowKeysInAlternativeScreen() &&
                    !e.isShiftDown()
                ) {
                    Integer key;
                    if (e.getWheelRotation() < 0) {
                        key = KeyEvent.VK_UP;
                    }
                    else if (e.getWheelRotation() > 0) {
                        key = KeyEvent.VK_DOWN;
                    }
                    else {
                        key = null;
                    }
                    if (key != null) {
                        TerminalStarter starter = getTerminalStarterReflect();
                        byte[] arrowKeys = starter.getTerminal().getCodeForKey(key, 0);
                        for (int i = 0; i < Math.abs(e.getUnitsToScroll()); i++) {
                            starter.sendBytes(arrowKeys, false);
                        }
                        e.consume();
                    }
                }
            });

            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    if (!settingsProvider.enableMouseReporting() || !isRemoteMouseAction(e)) return;
                    router.mouseMoved(toCharCoords(e), e);
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (!settingsProvider.enableMouseReporting() || !isRemoteMouseAction(e)) return;
                    router.mouseDragged(toCharCoords(e), e);
                }
            });
        }

        @Override
        public void setBracketedPasteMode(boolean enabled) {
            this.bracketedPasteModeEnabled = enabled;
            super.setBracketedPasteMode(enabled);
        }

        @Override
        protected JPopupMenu createPopupMenu(TerminalActionProvider provider) {
            JPopupMenu menu = new JPopupMenu() {
                @Override
                public void show(Component invoker, int x, int y) {
                    // Mouse reporting on → let tmux/vim/htop own the
                    // click. The PTY has already received the event
                    // through the TerminalMouseListener chain.
                    if (currentMouseMode != MouseMode.MOUSE_REPORTING_NONE) return;
                    super.show(invoker, x, y);
                }
            };
            TerminalAction.fillMenu(menu, provider);
            return menu;
        }

        boolean isBracketedPasteModeEnabled() {
            return bracketedPasteModeEnabled;
        }

        @Override
        public void processKeyEvent(KeyEvent e) {
            if (shouldClearSelectionOnKeyEvent(e)) {
                clearSelectionHighlight();
            }
            super.processKeyEvent(e);
        }

        private static Method lookupUpdateSelectionMethod() {
            try {
                Method method = TerminalPanel.class.getDeclaredMethod("updateSelection", TerminalSelection.class);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to access JediTerm selection updater", e);
            }
        }

        private static Method lookupPanelToCharCoordsMethod() {
            try {
                Method method = TerminalPanel.class.getDeclaredMethod("panelToCharCoords", java.awt.Point.class);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to access JediTerm coordinate mapper", e);
            }
        }

        private static Field lookupTerminalStarterField() {
            try {
                Field field = TerminalPanel.class.getDeclaredField("myTerminalStarter");
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to access JediTerm terminal starter", e);
            }
        }

        private boolean shouldClearSelectionOnKeyEvent(@NotNull KeyEvent e) {
            if (getSelection() == null) return false;
            return switch (e.getID()) {
                case KeyEvent.KEY_TYPED -> !Character.isISOControl(e.getKeyChar());
                case KeyEvent.KEY_PRESSED -> !isModifierOnlyKey(e.getKeyCode());
                default -> false;
            };
        }

        private static boolean isModifierOnlyKey(int keyCode) {
            return keyCode == KeyEvent.VK_SHIFT
                || keyCode == KeyEvent.VK_CONTROL
                || keyCode == KeyEvent.VK_ALT
                || keyCode == KeyEvent.VK_ALT_GRAPH
                || keyCode == KeyEvent.VK_META;
        }

        private @NotNull com.jediterm.core.compatibility.Point toCharCoords(@NotNull MouseEvent event) {
            try {
                return (com.jediterm.core.compatibility.Point) PANEL_TO_CHAR_COORDS_METHOD.invoke(this, event.getPoint());
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to map mouse event to terminal cell", exception);
            }
        }

        private @NotNull TerminalStarter getTerminalStarterReflect() {
            try {
                return (TerminalStarter) TERMINAL_STARTER_FIELD.get(this);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to access JediTerm terminal starter", e);
            }
        }

        private void clearSelection() {
            try {
                UPDATE_SELECTION_METHOD.invoke(this, new Object[]{null});
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to clear JediTerm selection", e);
            }
        }

        private void clearSelectionHighlight() {
            clearSelection();
            repaint();
        }
    }

    public static final class RemoteMouseGestureRouter {
        private final TerminalMouseListener listener;
        private @Nullable PendingSecondaryPress pendingSecondaryPress;
        private boolean secondaryButtonDragActive;

        public RemoteMouseGestureRouter(@NotNull TerminalMouseListener listener) {
            this.listener = listener;
        }

        public void mousePressed(@NotNull com.jediterm.core.compatibility.Point point, @NotNull MouseEvent event) {
            if (event.getButton() == MouseEvent.BUTTON3) {
                pendingSecondaryPress = new PendingSecondaryPress(point.x, point.y, getModifierKeys(event));
                secondaryButtonDragActive = false;
                return;
            }
            dispatchPressed(point, new AwtMouseEvent(event));
        }

        public void mouseReleased(@NotNull com.jediterm.core.compatibility.Point point, @NotNull MouseEvent event) {
            if (event.getButton() == MouseEvent.BUTTON3) {
                flushPendingSecondaryPress();
                if (pendingSecondaryPress == null && !secondaryButtonDragActive) return;
                listener.mouseReleased(point.x, point.y, rightButtonEvent(event));
                secondaryButtonDragActive = false;
                pendingSecondaryPress = null;
                return;
            }
            dispatchReleased(point, new AwtMouseEvent(event));
        }

        public void mouseMoved(@NotNull com.jediterm.core.compatibility.Point point, @NotNull MouseEvent event) {
            listener.mouseMoved(point.x, point.y, new AwtMouseEvent(event));
        }

        public void mouseDragged(@NotNull com.jediterm.core.compatibility.Point point, @NotNull MouseEvent event) {
            if (pendingSecondaryPress != null || secondaryButtonDragActive) {
                flushPendingSecondaryPress();
                listener.mouseDragged(point.x, point.y, rightButtonEvent(event));
                return;
            }
            dispatchDragged(point, new AwtMouseEvent(event));
        }

        public void mouseWheelMoved(@NotNull com.jediterm.core.compatibility.Point point, @NotNull MouseWheelEvent event) {
            pendingSecondaryPress = null;
            listener.mouseWheelMoved(point.x, point.y, new AwtMouseWheelEvent(event));
        }

        private void flushPendingSecondaryPress() {
            if (pendingSecondaryPress == null) return;
            listener.mousePressed(
                pendingSecondaryPress.x,
                pendingSecondaryPress.y,
                rightButtonEvent(pendingSecondaryPress.modifierKeys)
            );
            secondaryButtonDragActive = true;
            pendingSecondaryPress = null;
        }

        private void dispatchPressed(
            @NotNull com.jediterm.core.compatibility.Point point,
            @NotNull com.jediterm.core.input.MouseEvent event
        ) {
            if (event.getButtonCode() == MouseButtonCodes.NONE) return;
            listener.mousePressed(point.x, point.y, event);
        }

        private void dispatchReleased(
            @NotNull com.jediterm.core.compatibility.Point point,
            @NotNull com.jediterm.core.input.MouseEvent event
        ) {
            if (event.getButtonCode() == MouseButtonCodes.NONE) return;
            listener.mouseReleased(point.x, point.y, event);
        }

        private void dispatchDragged(
            @NotNull com.jediterm.core.compatibility.Point point,
            @NotNull com.jediterm.core.input.MouseEvent event
        ) {
            if (event.getButtonCode() == MouseButtonCodes.NONE) return;
            listener.mouseDragged(point.x, point.y, event);
        }

        private static @NotNull com.jediterm.core.input.MouseEvent rightButtonEvent(@NotNull MouseEvent event) {
            return rightButtonEvent(getModifierKeys(event));
        }

        private static @NotNull com.jediterm.core.input.MouseEvent rightButtonEvent(int modifierKeys) {
            return new com.jediterm.core.input.MouseEvent(MouseButtonCodes.RIGHT, modifierKeys);
        }

        private static int getModifierKeys(@NotNull MouseEvent awtMouseEvent) {
            int modifier = 0;
            if (awtMouseEvent.isControlDown()) modifier |= MouseButtonModifierFlags.MOUSE_BUTTON_CTRL_FLAG;
            if (awtMouseEvent.isShiftDown()) modifier |= MouseButtonModifierFlags.MOUSE_BUTTON_SHIFT_FLAG;
            if (awtMouseEvent.isMetaDown()) modifier |= MouseButtonModifierFlags.MOUSE_BUTTON_META_FLAG;
            return modifier;
        }

        private static final class PendingSecondaryPress {
            private final int x;
            private final int y;
            private final int modifierKeys;

            private PendingSecondaryPress(int x, int y, int modifierKeys) {
                this.x = x;
                this.y = y;
                this.modifierKeys = modifierKeys;
            }
        }
    }

    @Override
    protected JScrollBar createScrollBar() {
        JScrollBar scrollBar = super.createScrollBar();
        styleScrollBar(scrollBar);
        return scrollBar;
    }

    private void styleScrollBar(JScrollBar scrollBar) {
        scrollBar.setOpaque(false);
        scrollBar.setPreferredSize(new Dimension(8, Integer.MAX_VALUE));

        // Start hidden, show on hover
        scrollBar.setVisible(false);

        // Show scrollbar when mouse enters the terminal area, hide when it leaves
        MouseAdapter hoverListener = new MouseAdapter() {
            private Timer hideTimer;

            @Override
            public void mouseEntered(MouseEvent e) {
                scrollBar.setVisible(true);
                cancelHide();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                scheduleHide();
            }

            private void scheduleHide() {
                cancelHide();
                hideTimer = new Timer(1500, evt -> scrollBar.setVisible(false));
                hideTimer.setRepeats(false);
                hideTimer.start();
            }

            private void cancelHide() {
                if (hideTimer != null) {
                    hideTimer.stop();
                    hideTimer = null;
                }
            }
        };

        scrollBar.addMouseListener(hoverListener);
        this.addMouseListener(hoverListener);

        // Custom dark UI
        scrollBar.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = new Color(100, 100, 100, 120);
                trackColor = new Color(0, 0, 0, 0);
                thumbDarkShadowColor = new Color(0, 0, 0, 0);
                thumbHighlightColor = new Color(0, 0, 0, 0);
                thumbLightShadowColor = new Color(0, 0, 0, 0);
                trackHighlightColor = new Color(0, 0, 0, 0);
            }

            @Override
            protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
                if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(150, 150, 150, 80));
                g2.fillRoundRect(thumbBounds.x + 1, thumbBounds.y + 1,
                    thumbBounds.width - 2, thumbBounds.height - 2, 6, 6);
                g2.dispose();
            }

            @Override
            protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
                // Transparent track — no painting
            }

            @Override
            protected JButton createDecreaseButton(int orientation) {
                return createZeroButton();
            }

            @Override
            protected JButton createIncreaseButton(int orientation) {
                return createZeroButton();
            }

            private JButton createZeroButton() {
                JButton button = new JButton();
                button.setPreferredSize(new Dimension(0, 0));
                button.setMaximumSize(new Dimension(0, 0));
                button.setMinimumSize(new Dimension(0, 0));
                return button;
            }
        });
    }

    public boolean pasteText(@NotNull String text) {
        TerminalStarter starter = getTerminalStarter();
        if (starter == null || text.isEmpty()) return false;

        String payload = text;
        if (myTerminalPanel instanceof TermLabTerminalPanel panel && panel.isBracketedPasteModeEnabled()) {
            payload = BRACKETED_PASTE_PREFIX + text + BRACKETED_PASTE_SUFFIX;
        }

        starter.sendString(payload, true);
        return true;
    }
}
