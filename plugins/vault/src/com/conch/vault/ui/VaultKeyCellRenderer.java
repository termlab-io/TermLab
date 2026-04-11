package com.conch.vault.ui;

import com.conch.vault.model.VaultKey;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/**
 * Two-line list cell renderer for {@link VaultKey} — name on top,
 * algorithm + fingerprint underneath.
 */
final class VaultKeyCellRenderer extends JPanel implements ListCellRenderer<VaultKey> {

    private final JLabel name = new JLabel();
    private final JLabel subtitle = new JLabel();

    VaultKeyCellRenderer() {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(4, 8));
        JPanel lines = new JPanel(new GridLayout(2, 1));
        lines.setOpaque(false);
        lines.add(name);
        lines.add(subtitle);
        add(lines, BorderLayout.CENTER);
        subtitle.setFont(subtitle.getFont().deriveFont(subtitle.getFont().getSize() - 1f));
        subtitle.setForeground(UIManager.getColor("Label.disabledForeground"));
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends VaultKey> list,
                                                  VaultKey value, int index,
                                                  boolean isSelected, boolean cellHasFocus) {
        if (value == null) {
            name.setText("");
            subtitle.setText("");
            return this;
        }
        name.setText(value.name());
        subtitle.setText(value.algorithm() + "  ·  " + value.fingerprint());

        Color bg = isSelected ? list.getSelectionBackground() : list.getBackground();
        Color fg = isSelected ? list.getSelectionForeground() : list.getForeground();
        setBackground(bg);
        name.setForeground(fg);
        subtitle.setForeground(isSelected ? fg : UIManager.getColor("Label.disabledForeground"));
        return this;
    }
}
