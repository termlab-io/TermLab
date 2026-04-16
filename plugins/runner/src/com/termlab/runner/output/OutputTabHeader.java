package com.termlab.runner.output;

import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.FlowLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Header bar for a single output tab.
 */
final class OutputTabHeader extends JPanel {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private final JLabel statusLabel;

    OutputTabHeader(
        @NotNull String interpreter,
        @NotNull String hostLabel,
        @NotNull Instant startTime
    ) {
        super(new FlowLayout(FlowLayout.LEFT, 8, 2));
        setBorder(JBUI.Borders.emptyBottom(4));

        add(new JLabel(interpreter));
        add(new JLabel("•"));
        add(new JLabel(hostLabel));
        add(new JLabel("•"));
        add(new JLabel(TIME_FMT.format(startTime)));
        add(new JLabel("•"));

        statusLabel = new JLabel("Running...");
        add(statusLabel);
    }

    void setFinished(int exitCode) {
        if (exitCode == 0) {
            statusLabel.setText("Finished (exit 0)");
            statusLabel.setForeground(new Color(0, 128, 0));
        } else {
            statusLabel.setText("Failed (exit " + exitCode + ")");
            statusLabel.setForeground(new Color(192, 0, 0));
        }
    }

    void setStatus(@NotNull String text) {
        statusLabel.setText(text);
    }
}
