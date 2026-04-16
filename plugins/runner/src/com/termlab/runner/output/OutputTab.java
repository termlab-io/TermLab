package com.termlab.runner.output;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.ui.JBUI;
import com.termlab.runner.execution.ScriptExecution;
import org.jetbrains.annotations.NotNull;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * One running or completed script execution tab.
 */
final class OutputTab extends JPanel {

    private static final Logger LOG = Logger.getInstance(OutputTab.class);
    private static final int BUFFER_SIZE = 4096;

    private final ScriptExecution execution;
    private final OutputTabHeader header;
    private final JTextArea textArea;
    private final JScrollPane scrollPane;
    private volatile boolean userScrolledAway = false;

    OutputTab(
        @NotNull ScriptExecution execution,
        @NotNull String interpreter,
        @NotNull String hostLabel
    ) {
        super(new BorderLayout());
        this.execution = execution;

        header = new OutputTabHeader(interpreter, hostLabel, Instant.now());
        add(header, BorderLayout.NORTH);

        textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        textArea.setBorder(JBUI.Borders.empty(4));

        scrollPane = new JScrollPane(textArea);
        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            JScrollBar scrollBar = scrollPane.getVerticalScrollBar();
            int extent = scrollBar.getModel().getExtent();
            userScrolledAway = (scrollBar.getValue() + extent) < scrollBar.getMaximum() - 10;
        });
        add(scrollPane, BorderLayout.CENTER);

        startOutputReader();

        execution.addTerminationListener(() -> SwingUtilities.invokeLater(() -> {
            Integer exitCode = execution.getExitCode();
            header.setFinished(exitCode != null ? exitCode : -1);
        }));
    }

    private void startOutputReader() {
        Thread reader = new Thread(() -> {
            try (InputStream input = execution.getOutputStream()) {
                byte[] buffer = new byte[BUFFER_SIZE];
                StringBuilder batch = new StringBuilder();
                long lastFlush = System.currentTimeMillis();
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    batch.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    long now = System.currentTimeMillis();
                    if (now - lastFlush >= 50 || batch.length() > 8192) {
                        String text = batch.toString();
                        batch.setLength(0);
                        lastFlush = now;
                        SwingUtilities.invokeLater(() -> appendText(text));
                    }
                }
                if (!batch.isEmpty()) {
                    String text = batch.toString();
                    SwingUtilities.invokeLater(() -> appendText(text));
                }
            } catch (Exception e) {
                LOG.warn("TermLab Runner: output reader error: " + e.getMessage());
                SwingUtilities.invokeLater(() -> header.setStatus("Output reader failed"));
            }
        }, "TermLabRunner-output-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void appendText(@NotNull String text) {
        textArea.append(text);
        if (!userScrolledAway) {
            textArea.setCaretPosition(textArea.getDocument().getLength());
        }
    }

    @NotNull ScriptExecution execution() {
        return execution;
    }

    void clearOutput() {
        textArea.setText("");
    }
}
