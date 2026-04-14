package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.ServerState;
import com.intellij.icons.AllIcons;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.Dimension;

/**
 * Start / Stop / Restart / Backup row. Enabled state is a pure function
 * of {@link ServerState#status}. All click handlers are injected so
 * tests can verify enablement without wiring a real poller.
 */
public final class LifecycleButtons extends JPanel {

    private final JButton start   = new JButton(AllIcons.Actions.Execute);
    private final JButton stop    = new JButton(AllIcons.Process.Stop);
    private final JButton restart = new JButton(AllIcons.Actions.Restart);
    private final JButton backup  = new JButton(AllIcons.Actions.MenuSaveall);

    public LifecycleButtons(
        @NotNull Runnable onStart,
        @NotNull Runnable onStop,
        @NotNull Runnable onRestart,
        @NotNull Runnable onBackup
    ) {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(JBUI.Borders.empty(4, 8));

        start.addActionListener(e -> onStart.run());
        start.setToolTipText("Start server");
        start.setMargin(JBUI.insets(2));
        start.setPreferredSize(new Dimension(28, 28));
        start.setMinimumSize(new Dimension(28, 28));
        start.setMaximumSize(new Dimension(28, 28));
        start.setFocusPainted(false);

        stop.addActionListener(e -> onStop.run());
        stop.setToolTipText("Stop server");
        stop.setMargin(JBUI.insets(2));
        stop.setPreferredSize(new Dimension(28, 28));
        stop.setMinimumSize(new Dimension(28, 28));
        stop.setMaximumSize(new Dimension(28, 28));
        stop.setFocusPainted(false);

        restart.addActionListener(e -> onRestart.run());
        restart.setToolTipText("Restart server");
        restart.setMargin(JBUI.insets(2));
        restart.setPreferredSize(new Dimension(28, 28));
        restart.setMinimumSize(new Dimension(28, 28));
        restart.setMaximumSize(new Dimension(28, 28));
        restart.setFocusPainted(false);

        backup.addActionListener(e -> onBackup.run());
        backup.setToolTipText("Backup now");
        backup.setMargin(JBUI.insets(2));
        backup.setPreferredSize(new Dimension(28, 28));
        backup.setMinimumSize(new Dimension(28, 28));
        backup.setMaximumSize(new Dimension(28, 28));
        backup.setFocusPainted(false);

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

    public JButton startButton()   { return start; }
    public JButton stopButton()    { return stop; }
    public JButton restartButton() { return restart; }
    public JButton backupButton()  { return backup; }
}
