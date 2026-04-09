package com.conch.core.miniwindow;

import com.conch.core.settings.ConchTerminalConfig;
import com.conch.core.terminal.ConchTerminalSettings;
import com.conch.core.terminal.LocalPtySessionProvider;
import com.conch.sdk.TerminalSessionProvider;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public final class MiniTerminalWindow {
    private final JFrame frame;
    private final JediTermWidget terminalWidget;
    private TtyConnector connector;

    public MiniTerminalWindow() {
        frame = new JFrame("Conch \u2014 Quick Terminal");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(800, 500);
        frame.setLocationRelativeTo(null);

        // Apply IntelliJ's current Look and Feel so the mini-window follows the main theme
        frame.getRootPane().putClientProperty("JRootPane.titleBarBackground",
            javax.swing.UIManager.getColor("Panel.background"));
        frame.getRootPane().putClientProperty("JRootPane.titleBarForeground",
            javax.swing.UIManager.getColor("Panel.foreground"));
        frame.getContentPane().setBackground(javax.swing.UIManager.getColor("Panel.background"));

        terminalWidget = new JediTermWidget(new ConchTerminalSettings());

        // Apply cursor shape from settings
        ConchTerminalConfig config = ConchTerminalConfig.getInstance();
        ConchTerminalConfig.State s = config != null ? config.getState() : null;
        String shape = (s != null) ? s.cursorShape : "BLOCK";
        CursorShape cursorShape = switch (shape) {
            case "UNDERLINE" -> CursorShape.STEADY_UNDERLINE;
            case "VERTICAL_BAR" -> CursorShape.STEADY_VERTICAL_BAR;
            default -> CursorShape.STEADY_BLOCK;
        };
        terminalWidget.getTerminalPanel().setDefaultCursorShape(cursorShape);

        LocalPtySessionProvider ptyProvider = new LocalPtySessionProvider();
        TerminalSessionProvider.SessionContext context = () -> System.getProperty("user.home");
        connector = ptyProvider.createSession(context);

        if (connector != null) {
            terminalWidget.createTerminalSession(connector);
            terminalWidget.start();
        }

        frame.getContentPane().add(terminalWidget, BorderLayout.CENTER);

        // Close window on Cmd+W or Escape
        KeyStroke cmdW = KeyStroke.getKeyStroke(KeyEvent.VK_W,
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);

        JRootPane rootPane = frame.getRootPane();
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(cmdW, "closeWindow");
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escape, "closeWindow");
        rootPane.getActionMap().put("closeWindow", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { close(); }
        });

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (connector != null) {
                    try { connector.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    public void show() {
        frame.setVisible(true);
        terminalWidget.requestFocusInWindow();
    }

    public void close() {
        frame.dispose();
    }
}
