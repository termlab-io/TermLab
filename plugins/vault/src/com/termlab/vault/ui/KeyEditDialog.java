package com.termlab.vault.ui;

import com.termlab.vault.model.VaultKey;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Rename an existing {@link VaultKey}. Shows read-only details (algorithm,
 * fingerprint, paths) alongside the editable name field.
 *
 * <p>Renaming doesn't touch the key files on disk — only the vault metadata.
 * The generated {@code id_<algo>_<uuid>} filenames stay stable so external
 * tools referencing them by path keep working.
 */
public final class KeyEditDialog extends DialogWrapper {

    private final VaultKey original;
    private final JTextField nameField = new JTextField(24);

    private VaultKey result;

    private KeyEditDialog(@Nullable Project project, @NotNull VaultKey key) {
        super(project, true);
        this.original = key;
        nameField.setText(key.name());
        setTitle("Edit SSH Key");
        setOKButtonText("Save");
        init();
    }

    /** @return the renamed key, or {@code null} if cancelled. */
    public static @Nullable VaultKey show(@Nullable Project project, @NotNull VaultKey key) {
        KeyEditDialog dlg = new KeyEditDialog(project, key);
        return dlg.showAndGet() ? dlg.result : null;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return nameField;
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(560, 260));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridy = 0;
        c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Name:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(nameField, c);

        c.gridy++;
        addReadOnlyRow(panel, c, "Algorithm:", original.algorithm());

        c.gridy++;
        addReadOnlyRow(panel, c, "Fingerprint:", original.fingerprint());

        c.gridy++;
        addReadOnlyRow(panel, c, "Comment:",
            original.comment() == null || original.comment().isBlank()
                ? "(none)" : original.comment());

        c.gridy++;
        addReadOnlyRow(panel, c, "Private key:", original.privatePath());

        c.gridy++;
        addReadOnlyRow(panel, c, "Public key:", original.publicPath());

        return panel;
    }

    private static void addReadOnlyRow(JPanel panel, GridBagConstraints c, String label, String value) {
        c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        JLabel valueLabel = new JLabel(value);
        valueLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(valueLabel, c);
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (nameField.getText().trim().isEmpty()) {
            return new ValidationInfo("Name is required", nameField);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        result = original.withName(nameField.getText().trim());
        super.doOKAction();
    }
}
