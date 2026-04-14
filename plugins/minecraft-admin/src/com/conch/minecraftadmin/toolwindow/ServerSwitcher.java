package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.model.ServerProfile;
import com.intellij.icons.AllIcons;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.util.List;
import java.util.function.Consumer;

/** Dropdown + gear/add buttons at the top of the tool window. */
public final class ServerSwitcher extends JPanel {

    private final DefaultComboBoxModel<ServerProfile> model = new DefaultComboBoxModel<>();
    private final JComboBox<ServerProfile> combo = new JComboBox<>(model);
    private final JButton addButton       = new JButton(AllIcons.General.Add);
    private final JButton editButton      = new JButton(AllIcons.Actions.Edit);
    private final JButton duplicateButton = new JButton(AllIcons.Actions.Copy);
    private final JButton deleteButton    = new JButton(AllIcons.General.Remove);

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
        combo.setMinimumSize(new Dimension(140, combo.getPreferredSize().height));
        combo.setPreferredSize(new Dimension(180, combo.getPreferredSize().height));
        combo.setMaximumSize(new Dimension(260, combo.getPreferredSize().height));
        combo.addActionListener(e -> {
            ServerProfile p = (ServerProfile) combo.getSelectedItem();
            if (p != null) onSelect.accept(p);
        });
        addButton.addActionListener(e -> onAdd.run());
        addButton.setToolTipText("Add new server profile");
        addButton.setMargin(JBUI.insets(2));
        addButton.setPreferredSize(new Dimension(28, 28));
        addButton.setMinimumSize(new Dimension(28, 28));
        addButton.setMaximumSize(new Dimension(28, 28));
        addButton.setFocusPainted(false);

        editButton.addActionListener(e -> {
            ServerProfile p = (ServerProfile) combo.getSelectedItem();
            if (p != null) onEdit.accept(p);
        });
        editButton.setToolTipText("Edit the current server profile");
        editButton.setMargin(JBUI.insets(2));
        editButton.setPreferredSize(new Dimension(28, 28));
        editButton.setMinimumSize(new Dimension(28, 28));
        editButton.setMaximumSize(new Dimension(28, 28));
        editButton.setFocusPainted(false);

        duplicateButton.addActionListener(e -> {
            ServerProfile p = (ServerProfile) combo.getSelectedItem();
            if (p != null) onDuplicate.accept(p);
        });
        duplicateButton.setToolTipText("Duplicate the current server profile");
        duplicateButton.setMargin(JBUI.insets(2));
        duplicateButton.setPreferredSize(new Dimension(28, 28));
        duplicateButton.setMinimumSize(new Dimension(28, 28));
        duplicateButton.setMaximumSize(new Dimension(28, 28));
        duplicateButton.setFocusPainted(false);

        deleteButton.addActionListener(e -> {
            ServerProfile p = (ServerProfile) combo.getSelectedItem();
            if (p != null) onDelete.accept(p);
        });
        deleteButton.setToolTipText("Delete the current server profile");
        deleteButton.setMargin(JBUI.insets(2));
        deleteButton.setPreferredSize(new Dimension(28, 28));
        deleteButton.setMinimumSize(new Dimension(28, 28));
        deleteButton.setMaximumSize(new Dimension(28, 28));
        deleteButton.setFocusPainted(false);

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
