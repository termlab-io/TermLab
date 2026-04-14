package com.conch.core.terminal;

import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.TerminalStarter;
import com.jediterm.terminal.TtyBasedArrayDataStream;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalAction;
import com.jediterm.terminal.ui.TerminalActionProvider;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Custom JediTermWidget with a dark, auto-hiding scrollbar that matches
 * the terminal background. Only visible when hovering or scrolling.
 */
public class ConchTerminalWidget extends JediTermWidget {

    public ConchTerminalWidget(SettingsProvider settingsProvider) {
        super(settingsProvider);
    }

    @Override
    protected TerminalStarter createTerminalStarter(@NotNull JediTerminal terminal,
                                                     @NotNull TtyConnector connector) {
        return new ConchTerminalStarter(
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
        return new ConchTerminalPanel(settings, textBuffer, styleState);
    }

    @Override
    protected JediTerminal createTerminal(@NotNull TerminalDisplay display,
                                          @NotNull TerminalTextBuffer textBuffer,
                                          @NotNull StyleState styleState) {
        return new ConchJediTerminal(display, textBuffer, styleState);
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
     * <p>Fix: remap {@code buttonCode == -1} to {@code 2} — xterm's
     * right-button code — on every mouse method before delegating to
     * the parent. We apply this to press, release, move, and drag so
     * drag-right-click interactions work too.
     */
    private static final class ConchJediTerminal extends JediTerminal {

        ConchJediTerminal(@NotNull TerminalDisplay display,
                          @NotNull TerminalTextBuffer buffer,
                          @NotNull StyleState styleState) {
            super(display, buffer, styleState);
        }

        @Override
        public void mousePressed(int x, int y, @NotNull com.jediterm.core.input.MouseEvent event) {
            super.mousePressed(x, y, remapRightButton(event));
        }

        @Override
        public void mouseReleased(int x, int y, @NotNull com.jediterm.core.input.MouseEvent event) {
            super.mouseReleased(x, y, remapRightButton(event));
        }

        @Override
        public void mouseMoved(int x, int y, @NotNull com.jediterm.core.input.MouseEvent event) {
            super.mouseMoved(x, y, remapRightButton(event));
        }

        @Override
        public void mouseDragged(int x, int y, @NotNull com.jediterm.core.input.MouseEvent event) {
            super.mouseDragged(x, y, remapRightButton(event));
        }

        private static @NotNull com.jediterm.core.input.MouseEvent remapRightButton(
            @NotNull com.jediterm.core.input.MouseEvent event
        ) {
            if (event.getButtonCode() != -1) return event;
            // JediTerm's AwtMouseEvent.createButtonCode returns -1 on a
            // right-click press/release/drag. xterm mouse button code 2
            // is right-button. Rebuild the event so
            // JediTerminal.mousePressed's `if (btnCode == -1) return`
            // doesn't short-circuit the report.
            return new com.jediterm.core.input.MouseEvent(2, event.getModifierKeys());
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
     * active. Native Conch actions (Shift+right-click still counts
     * as {@code isLocalMouseAction}) continue to hit the popup
     * because JediTerm's emulator only marks the mode non-NONE
     * when a remote app asks for it with {@code CSI ? 1000 h} et al.
     */
    private static final class ConchTerminalPanel extends TerminalPanel {

        private volatile MouseMode currentMouseMode = MouseMode.MOUSE_REPORTING_NONE;

        ConchTerminalPanel(@NotNull SettingsProvider settings,
                           @NotNull TerminalTextBuffer buffer,
                           @NotNull StyleState styleState) {
            super(settings, buffer, styleState);
        }

        @Override
        public void terminalMouseModeSet(MouseMode mode) {
            this.currentMouseMode = mode;
            super.terminalMouseModeSet(mode);
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
}
