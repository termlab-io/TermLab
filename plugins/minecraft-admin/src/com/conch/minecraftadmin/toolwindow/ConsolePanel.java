package com.conch.minecraftadmin.toolwindow;

import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionListener;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Scrollable log tail + command send box + Broadcast button. Polling is
 * driven externally by {@code ProfileController} which calls
 * {@link #appendLines} with new entries from {@code AmpClient}.
 *
 * <p>Command history is in-memory, capped at 20 entries. Up/Down cycles
 * through history; reaching the ends clamps rather than wraps.
 */
public final class ConsolePanel extends JPanel {

    private static final int HISTORY_CAP = 20;

    private final JTextArea textArea = new JTextArea();
    private final JBTextField commandField = new JBTextField();
    private final JButton broadcastButton = new JButton("Broadcast\u2026");
    private final Function<String, String> commandSink;
    private final Consumer<String> broadcastSink;

    private final Deque<String> history = new ArrayDeque<>();
    private int historyIndex = -1;

    public ConsolePanel(
        @NotNull Function<String, String> commandSink,
        @NotNull Consumer<String> broadcastSink
    ) {
        super(new BorderLayout());
        this.commandSink = commandSink;
        this.broadcastSink = broadcastSink;
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        add(new JBScrollPane(textArea), BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.X_AXIS));
        bottom.setBorder(JBUI.Borders.empty(4, 6));
        bottom.add(commandField);
        bottom.add(javax.swing.Box.createHorizontalStrut(6));
        bottom.add(broadcastButton);
        add(bottom, BorderLayout.SOUTH);

        commandField.addActionListener(sendAction());
        broadcastButton.addActionListener(e -> {
            String msg = commandField.getText();
            if (msg.isBlank()) return;
            broadcastSink.accept(msg);
            commandField.setText("");
        });
        bindHistoryKeys();
    }

    public void appendLines(@NotNull List<String> lines) {
        if (lines.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        textArea.append(sb.toString());
        // Auto-scroll to the bottom.
        textArea.setCaretPosition(textArea.getDocument().getLength());
    }

    /** Visible for tests. */
    public String textAreaContents() { return textArea.getText(); }

    /** Visible for tests. */
    public void sendForTest(String command) { send(command); }

    /** Visible for tests. */
    public String historyUpForTest() {
        historyIndex = Math.min(historyIndex + 1, history.size() - 1);
        return snapshotAtIndex();
    }

    /** Visible for tests. */
    public String historyDownForTest() {
        historyIndex = Math.max(historyIndex - 1, 0);
        return snapshotAtIndex();
    }

    /** Visible for tests. */
    public int historySize() { return history.size(); }

    private ActionListener sendAction() {
        return e -> {
            String cmd = commandField.getText();
            if (cmd.isBlank()) return;
            send(cmd);
            commandField.setText("");
        };
    }

    private void send(String command) {
        pushHistory(command);
        try {
            String reply = commandSink.apply(command);
            appendLines(List.of("> " + command, reply));
        } catch (Exception ex) {
            appendLines(List.of("> " + command, "[error] " + ex.getMessage()));
        }
    }

    private void pushHistory(String command) {
        history.addFirst(command);
        while (history.size() > HISTORY_CAP) history.removeLast();
        historyIndex = -1;
    }

    private String snapshotAtIndex() {
        if (history.isEmpty() || historyIndex < 0) return "";
        int i = 0;
        for (String item : history) {
            if (i == historyIndex) return item;
            i++;
        }
        return "";
    }

    private void bindHistoryKeys() {
        commandField.getInputMap().put(KeyStroke.getKeyStroke("UP"), "historyUp");
        commandField.getInputMap().put(KeyStroke.getKeyStroke("DOWN"), "historyDown");
        commandField.getActionMap().put("historyUp", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                commandField.setText(historyUpForTest());
            }
        });
        commandField.getActionMap().put("historyDown", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                commandField.setText(historyDownForTest());
            }
        });
        commandField.setPreferredSize(new Dimension(200, commandField.getPreferredSize().height));
    }
}
