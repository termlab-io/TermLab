package com.conch.ssh.toolwindow;

import com.conch.ssh.model.SshHost;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/**
 * Two-line {@link JBList} cell renderer for {@link SshHost} — label on
 * top, {@code host:port · username} underneath. Mirrors the vault's
 * two-line renderers so the Hosts tool window feels consistent with
 * the Credentials UI.
 */
public final class HostCellRenderer extends JPanel implements ListCellRenderer<SshHost> {

    private final JLabel label = new JLabel();
    private final JLabel subtitle = new JLabel();

    public HostCellRenderer() {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(4, 8));
        JPanel lines = new JPanel(new GridLayout(2, 1));
        lines.setOpaque(false);
        lines.add(label);
        lines.add(subtitle);
        add(lines, BorderLayout.CENTER);
        subtitle.setFont(subtitle.getFont().deriveFont(subtitle.getFont().getSize() - 1f));
        subtitle.setForeground(UIManager.getColor("Label.disabledForeground"));
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends SshHost> list,
                                                  SshHost value, int index,
                                                  boolean isSelected, boolean cellHasFocus) {
        if (value == null) {
            label.setText("");
            subtitle.setText("");
            return this;
        }
        label.setText(value.label());
        subtitle.setText(value.host() + ":" + value.port() + "  ·  " + value.username());

        Color bg = isSelected ? list.getSelectionBackground() : list.getBackground();
        Color fg = isSelected ? list.getSelectionForeground() : list.getForeground();
        setBackground(bg);
        label.setForeground(fg);
        subtitle.setForeground(isSelected ? fg : UIManager.getColor("Label.disabledForeground"));
        return this;
    }
}
