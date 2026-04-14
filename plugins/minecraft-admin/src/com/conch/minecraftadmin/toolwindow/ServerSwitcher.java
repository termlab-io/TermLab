package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.model.ServerProfile;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import java.util.List;
import java.util.function.Consumer;

/** Dropdown + gear/add buttons at the top of the tool window. */
public final class ServerSwitcher extends JPanel {

    private final DefaultComboBoxModel<ServerProfile> model = new DefaultComboBoxModel<>();
    private final JComboBox<ServerProfile> combo = new JComboBox<>(model);
    private final JButton addButton       = new JButton("+");
    private final JButton editButton      = new JButton("⚙");
    private final JButton duplicateButton = new JButton("📋");
    private final JButton deleteButton    = new JButton("🗑");

    public ServerSwitcher(
        @NotNull Consumer<ServerProfile> onSelect,
        @NotNull Runnable onAdd,
        @NotNull Consumer<ServerProfile> onEdit,
        @NotNull Consumer<ServerProfile> onDuplicate,
        @NotNull Consumer<ServerProfile> onDelete
    ) {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(JBUI.Borders.empty(4, 8));
        combo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                javax.swing.JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ServerProfile p) setText(p.label());
                else setText("(no server)");
                return this;
            }
        });
        combo.addActionListener(e -> {
            ServerProfile p = (ServerProfile) combo.getSelectedItem();
            if (p != null) onSelect.accept(p);
        });
        addButton.addActionListener(e -> onAdd.run());
        editButton.addActionListener(e -> {
            ServerProfile p = (ServerProfile) combo.getSelectedItem();
            if (p != null) onEdit.accept(p);
        });
        duplicateButton.addActionListener(e -> {
            ServerProfile p = (ServerProfile) combo.getSelectedItem();
            if (p != null) onDuplicate.accept(p);
        });
        deleteButton.addActionListener(e -> {
            ServerProfile p = (ServerProfile) combo.getSelectedItem();
            if (p != null) onDelete.accept(p);
        });
        add(combo);
        add(javax.swing.Box.createHorizontalStrut(4));
        add(addButton);
        add(javax.swing.Box.createHorizontalStrut(4));
        add(editButton);
        add(javax.swing.Box.createHorizontalStrut(4));
        add(duplicateButton);
        add(javax.swing.Box.createHorizontalStrut(4));
        add(deleteButton);
    }

    public void setProfiles(@NotNull List<ServerProfile> profiles, @Nullable ServerProfile selected) {
        model.removeAllElements();
        for (ServerProfile p : profiles) model.addElement(p);
        if (selected != null) combo.setSelectedItem(selected);
        boolean hasProfiles = !profiles.isEmpty();
        editButton.setEnabled(hasProfiles);
        duplicateButton.setEnabled(hasProfiles);
        deleteButton.setEnabled(hasProfiles);
    }
}
