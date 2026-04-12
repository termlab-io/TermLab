package com.conch.tunnels.toolwindow;

import com.conch.tunnels.client.TunnelConnectionManager;
import com.conch.tunnels.model.InternalHost;
import com.conch.tunnels.model.SshConfigHost;
import com.conch.tunnels.model.SshTunnel;
import com.conch.tunnels.model.TunnelState;
import com.conch.tunnels.model.TunnelType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/**
 * Two-line {@link JBList} cell renderer for {@link SshTunnel}.
 *
 * <p>Top line: status icon + label.
 * <br>Bottom line: direction ({@code L} or {@code R}) +
 * {@code bindAddress:bindPort → targetHost:targetPort}.
 *
 * <p>Status icons:
 * <ul>
 *   <li>ACTIVE: green "●"</li>
 *   <li>ERROR: yellow "⚠"</li>
 *   <li>DISCONNECTED/CONNECTING: gray "○"</li>
 * </ul>
 */
public final class TunnelCellRenderer extends JPanel implements ListCellRenderer<SshTunnel> {

    private static final Color GREEN  = new Color(0, 180, 0);
    private static final Color YELLOW = new Color(220, 160, 0);

    private final JLabel statusLabel = new JLabel();
    private final JLabel nameLabel   = new JLabel();
    private final JLabel subtitle    = new JLabel();

    public TunnelCellRenderer() {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(4, 8));

        JPanel topLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        topLine.setOpaque(false);
        topLine.add(statusLabel);
        topLine.add(nameLabel);

        JPanel lines = new JPanel(new GridLayout(2, 1));
        lines.setOpaque(false);
        lines.add(topLine);
        lines.add(subtitle);
        add(lines, BorderLayout.CENTER);

        subtitle.setFont(subtitle.getFont().deriveFont(subtitle.getFont().getSize() - 1f));
        subtitle.setForeground(UIManager.getColor("Label.disabledForeground"));
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends SshTunnel> list,
                                                   SshTunnel value, int index,
                                                   boolean isSelected, boolean cellHasFocus) {
        if (value == null) {
            statusLabel.setText("");
            nameLabel.setText("");
            subtitle.setText("");
            return this;
        }

        TunnelState state = resolveState(value);
        applyStatusIcon(state, isSelected);

        nameLabel.setText(value.label());

        String direction = value.type() == TunnelType.LOCAL ? "L" : "R";
        String hostDisplay = hostDisplayName(value);
        String subtitleText = direction + "  " + hostDisplay
            + "  " + value.bindAddress() + ":" + value.bindPort()
            + " → " + value.targetHost() + ":" + value.targetPort();
        subtitle.setText(subtitleText);

        Color bg = isSelected ? list.getSelectionBackground() : list.getBackground();
        Color fg = isSelected ? list.getSelectionForeground() : list.getForeground();
        setBackground(bg);
        nameLabel.setForeground(fg);
        subtitle.setForeground(isSelected ? fg : UIManager.getColor("Label.disabledForeground"));
        return this;
    }

    private static @org.jetbrains.annotations.NotNull TunnelState resolveState(
        @org.jetbrains.annotations.NotNull SshTunnel tunnel
    ) {
        if (ApplicationManager.getApplication() == null) return TunnelState.DISCONNECTED;
        TunnelConnectionManager mgr =
            ApplicationManager.getApplication().getService(TunnelConnectionManager.class);
        if (mgr == null) return TunnelState.DISCONNECTED;
        return mgr.getState(tunnel.id());
    }

    private void applyStatusIcon(@org.jetbrains.annotations.NotNull TunnelState state, boolean selected) {
        switch (state) {
            case ACTIVE -> {
                statusLabel.setText("●");
                statusLabel.setForeground(GREEN);
            }
            case ERROR -> {
                statusLabel.setText("⚠");
                statusLabel.setForeground(YELLOW);
            }
            default -> {
                statusLabel.setText("○");
                statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            }
        }
    }

    private static @org.jetbrains.annotations.NotNull String hostDisplayName(
        @org.jetbrains.annotations.NotNull SshTunnel tunnel
    ) {
        return switch (tunnel.host()) {
            case InternalHost h -> "host:" + h.hostId().toString().substring(0, 8) + "…";
            case SshConfigHost s -> s.alias();
        };
    }
}
