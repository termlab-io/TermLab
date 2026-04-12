package com.conch.tunnels.toolwindow;

import com.conch.tunnels.client.TunnelConnectionManager;
import com.conch.tunnels.model.SshTunnel;
import com.conch.tunnels.model.TunnelState;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/**
 * Single-line {@link JBList} cell renderer for {@link SshTunnel}.
 * Shows only a status icon + label. Users click "View Details"
 * in the context menu for full tunnel info.
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

    public TunnelCellRenderer() {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        setBorder(JBUI.Borders.empty(4, 8));
        add(statusLabel);
        add(nameLabel);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends SshTunnel> list,
                                                   SshTunnel value, int index,
                                                   boolean isSelected, boolean cellHasFocus) {
        if (value == null) {
            statusLabel.setText("");
            nameLabel.setText("");
            return this;
        }

        TunnelState state = resolveState(value);
        applyStatusIcon(state, isSelected);
        nameLabel.setText(value.label());

        Color bg = isSelected ? list.getSelectionBackground() : list.getBackground();
        Color fg = isSelected ? list.getSelectionForeground() : list.getForeground();
        setBackground(bg);
        nameLabel.setForeground(fg);
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

}
