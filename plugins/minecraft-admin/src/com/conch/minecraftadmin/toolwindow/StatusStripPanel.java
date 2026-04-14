package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.ServerState;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import java.awt.Color;
import java.time.Duration;

/**
 * Six-cell horizontal status strip shown at the top of the tool window.
 * Cells (left to right): status dot + text, players, TPS, CPU, RAM, uptime.
 * AMP-sourced cells (CPU, RAM, uptime) gray out when {@code state.ampError}
 * is present; RCON-sourced cells (players, TPS) gray out when
 * {@code state.rconError} is present.
 */
public final class StatusStripPanel extends JPanel {

    private static final Color HEALTHY = JBUI.CurrentTheme.Label.foreground();
    private static final Color UNAVAILABLE = JBUI.CurrentTheme.Label.disabledForeground();

    private final JBLabel statusLabel = new JBLabel();
    private final JBLabel playersLabel = new JBLabel();
    private final JBLabel tpsLabel = new JBLabel();
    private final JBLabel cpuLabel = new JBLabel();
    private final JBLabel ramLabel = new JBLabel();
    private final JBLabel uptimeLabel = new JBLabel();
    private final JBLabel ampPill = new JBLabel();
    private final JBLabel rconPill = new JBLabel();

    public StatusStripPanel() {
        setLayout(new WrapLayout(WrapLayout.LEFT, 12, 2));
        setBorder(JBUI.Borders.empty(4, 8));
        add(statusLabel);
        add(playersLabel);
        add(tpsLabel);
        add(cpuLabel);
        add(ramLabel);
        add(uptimeLabel);
        add(ampPill);
        add(rconPill);
    }

    public void update(@NotNull ServerState state) {
        statusLabel.setText(renderStatus(state.status()));
        statusLabel.setForeground(HEALTHY);

        boolean rconOk = state.isRconHealthy();
        playersLabel.setText("Players: " + state.playersOnline() + "/" + state.playersMax());
        playersLabel.setForeground(rconOk ? HEALTHY : UNAVAILABLE);
        tpsLabel.setText("TPS: " + (Double.isNaN(state.tps()) ? "—" : String.format("%.1f", state.tps())));
        tpsLabel.setForeground(rconOk ? HEALTHY : UNAVAILABLE);

        boolean ampOk = state.isAmpHealthy();
        cpuLabel.setText("CPU: " + (Double.isNaN(state.cpuPercent()) ? "—" : String.format("%.0f%%", state.cpuPercent())));
        cpuLabel.setForeground(ampOk ? HEALTHY : UNAVAILABLE);
        ramLabel.setText("RAM: " + state.ramUsedMb() + "/" + state.ramMaxMb() + " MB");
        ramLabel.setForeground(ampOk ? HEALTHY : UNAVAILABLE);
        uptimeLabel.setText("Up: " + formatUptime(state.uptime()));
        uptimeLabel.setForeground(ampOk ? HEALTHY : UNAVAILABLE);

        ampPill.setVisible(!ampOk);
        ampPill.setText(ampOk ? "" : "⚠ AMP offline");
        ampPill.setToolTipText(state.ampError().orElse(null));
        rconPill.setVisible(!rconOk);
        rconPill.setText(rconOk ? "" : "⚠ RCON offline");
        rconPill.setToolTipText(state.rconError().orElse(null));
    }

    private static String renderStatus(McServerStatus status) {
        return switch (status) {
            case RUNNING -> "● Running";
            case STARTING -> "◐ Starting";
            case STOPPING -> "◐ Stopping";
            case STOPPED -> "○ Stopped";
            case CRASHED -> "✗ Crashed";
            case UNKNOWN -> "? Unknown";
            case LOCKED -> "🔒 Vault locked";
        };
    }

    private static String formatUptime(Duration uptime) {
        long totalMinutes = Math.max(0, uptime.toMinutes());
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return hours + "h " + minutes + "m";
    }
}
