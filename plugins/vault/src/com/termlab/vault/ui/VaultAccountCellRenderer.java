package com.termlab.vault.ui;

import com.termlab.vault.model.AuthMethod;
import com.termlab.vault.model.VaultAccount;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/**
 * Two-line list cell renderer for {@link VaultAccount} — display name on
 * top, username + auth-method summary underneath.
 */
final class VaultAccountCellRenderer extends JPanel implements ListCellRenderer<VaultAccount> {

    private final JLabel name = new JLabel();
    private final JLabel subtitle = new JLabel();

    VaultAccountCellRenderer() {
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
    public Component getListCellRendererComponent(JList<? extends VaultAccount> list,
                                                  VaultAccount value, int index,
                                                  boolean isSelected, boolean cellHasFocus) {
        if (value == null) {
            name.setText("");
            subtitle.setText("");
            return this;
        }
        name.setText(value.displayName());
        subtitle.setText(value.username() + " · " + authSummary(value.auth()));

        Color bg = isSelected ? list.getSelectionBackground() : list.getBackground();
        Color fg = isSelected ? list.getSelectionForeground() : list.getForeground();
        setBackground(bg);
        name.setForeground(fg);
        if (!isSelected) {
            subtitle.setForeground(UIManager.getColor("Label.disabledForeground"));
        } else {
            subtitle.setForeground(fg);
        }
        return this;
    }

    private static String authSummary(AuthMethod auth) {
        return switch (auth) {
            case AuthMethod.Password ignored -> "password";
            case AuthMethod.Key k -> "key: " + shortPath(k.keyPath());
            case AuthMethod.KeyAndPassword kp -> "key+pw: " + shortPath(kp.keyPath());
            case AuthMethod.ApiToken ignored -> "api key";
            case AuthMethod.SecureNote ignored -> "secure note";
        };
    }

    private static String shortPath(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }
}
