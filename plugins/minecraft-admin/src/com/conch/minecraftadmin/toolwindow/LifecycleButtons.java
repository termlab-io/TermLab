package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.ServerState;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;

/**
 * Start / Stop / Restart / Backup row. Enabled state is a pure function
 * of {@link ServerState#status}. All click handlers are injected so
 * tests can verify enablement without wiring a real poller.
 */
public final class LifecycleButtons extends JPanel {

    private final JButton start = new JButton("Start");
    private final JButton stop = new JButton("Stop");
    private final JButton restart = new JButton("Restart");
    private final JButton backup = new JButton("Backup");

    public LifecycleButtons(
        @NotNull Runnable onStart,
        @NotNull Runnable onStop,
        @NotNull Runnable onRestart,
        @NotNull Runnable onBackup
    ) {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(JBUI.Borders.empty(4, 8));
        start.addActionListener(e -> onStart.run());
        stop.addActionListener(e -> onStop.run());
        restart.addActionListener(e -> onRestart.run());
        backup.addActionListener(e -> onBackup.run());
        java.awt.Insets compactMargin = JBUI.insets(2, 10);
        start.setMargin(compactMargin);
        stop.setMargin(compactMargin);
        restart.setMargin(compactMargin);
        backup.setMargin(compactMargin);
        add(start);
        add(javax.swing.Box.createHorizontalStrut(6));
        add(stop);
        add(javax.swing.Box.createHorizontalStrut(6));
        add(restart);
        add(javax.swing.Box.createHorizontalStrut(6));
        add(backup);
    }

    public void update(@NotNull ServerState state) {
        McServerStatus s = state.status();
        if (s == McServerStatus.LOCKED) {
            start.setEnabled(false);
            stop.setEnabled(false);
            restart.setEnabled(false);
            backup.setEnabled(false);
            return;
        }
        start.setEnabled(s == McServerStatus.STOPPED || s == McServerStatus.CRASHED || s == McServerStatus.UNKNOWN);
        stop.setEnabled(s == McServerStatus.RUNNING);
        restart.setEnabled(s == McServerStatus.RUNNING);
        backup.setEnabled(s == McServerStatus.RUNNING || s == McServerStatus.CRASHED);
    }

    public JButton startButton() { return start; }
    public JButton stopButton() { return stop; }
    public JButton restartButton() { return restart; }
    public JButton backupButton() { return backup; }
}
