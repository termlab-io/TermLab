package com.conch.core.terminal;

import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.SettingsProvider;

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
