package com.conch.vault.ui;

import com.conch.vault.model.AuthMethod;
import com.conch.vault.model.VaultAccount;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

/**
 * Modal dialog for adding or editing a single {@link VaultAccount}. Supports
 * all three auth methods via radio buttons; only the fields relevant to the
 * selected method are enabled.
 *
 * <p>The caller is responsible for persisting the returned account into the
 * vault and calling {@link com.conch.vault.lock.LockManager#save()}.
 */
public final class AccountEditDialog extends DialogWrapper {

    private final @Nullable VaultAccount existing;

    private final JTextField nameField = new JTextField(24);
    private final JTextField usernameField = new JTextField(24);

    private final JRadioButton passwordRadio = new JRadioButton("Password", true);
    private final JRadioButton keyRadio = new JRadioButton("SSH key");
    private final JRadioButton keyAndPasswordRadio = new JRadioButton("SSH key + password");

    private final JPasswordField passwordOnlyField = new JPasswordField(24);

    private final TextFieldWithBrowseButton keyPathField = new TextFieldWithBrowseButton();
    private final JPasswordField keyPassphraseField = new JPasswordField(24);

    private final TextFieldWithBrowseButton kpKeyPathField = new TextFieldWithBrowseButton();
    private final JPasswordField kpPassphraseField = new JPasswordField(24);
    private final JPasswordField kpPasswordField = new JPasswordField(24);

    private VaultAccount result;

    public AccountEditDialog(@Nullable Project project, @Nullable VaultAccount existing) {
        super(project, true);
        this.existing = existing;
        setTitle(existing == null ? "Add Credential" : "Edit Credential");
        setOKButtonText(existing == null ? "Add" : "Save");
        wireKeyPathChoosers();
        init();
        populateFromExisting();
    }

    /** @return the account produced by this dialog, or null if cancelled. */
    public static @Nullable VaultAccount show(@Nullable Project project, @Nullable VaultAccount existing) {
        AccountEditDialog dlg = new AccountEditDialog(project, existing);
        return dlg.showAndGet() ? dlg.result : null;
    }

    private void wireKeyPathChoosers() {
        var descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor();
        keyPathField.addBrowseFolderListener(new TextBrowseFolderListener(descriptor));
        kpKeyPathField.addBrowseFolderListener(new TextBrowseFolderListener(descriptor));
    }

    private void populateFromExisting() {
        if (existing == null) {
            updateAuthEnablement();
            return;
        }
        nameField.setText(existing.displayName());
        usernameField.setText(existing.username());
        switch (existing.auth()) {
            case AuthMethod.Password p -> {
                passwordRadio.setSelected(true);
                passwordOnlyField.setText(p.password());
            }
            case AuthMethod.Key k -> {
                keyRadio.setSelected(true);
                keyPathField.setText(k.keyPath());
                if (k.passphrase() != null) keyPassphraseField.setText(k.passphrase());
            }
            case AuthMethod.KeyAndPassword kp -> {
                keyAndPasswordRadio.setSelected(true);
                kpKeyPathField.setText(kp.keyPath());
                if (kp.passphrase() != null) kpPassphraseField.setText(kp.passphrase());
                kpPasswordField.setText(kp.password());
            }
        }
        updateAuthEnablement();
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(520, 360));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        panel.add(new JLabel("Display name:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(nameField, c);

        c.gridy++; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Username:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(usernameField, c);

        ButtonGroup group = new ButtonGroup();
        group.add(passwordRadio);
        group.add(keyRadio);
        group.add(keyAndPasswordRadio);

        JPanel radioRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        radioRow.add(passwordRadio);
        radioRow.add(keyRadio);
        radioRow.add(keyAndPasswordRadio);
        c.gridy++; c.gridx = 0; c.gridwidth = 2;
        panel.add(radioRow, c);

        c.gridwidth = 1;

        // Password-only row.
        c.gridy++; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Password:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(passwordOnlyField, c);

        // Key rows.
        c.gridy++; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Key path:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(keyPathField, c);

        c.gridy++; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Passphrase:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(keyPassphraseField, c);

        // Key-and-password rows.
        c.gridy++; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Key path (K+P):"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(kpKeyPathField, c);

        c.gridy++; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Passphrase (K+P):"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(kpPassphraseField, c);

        c.gridy++; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Password (K+P):"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(kpPasswordField, c);

        passwordRadio.addActionListener(e -> updateAuthEnablement());
        keyRadio.addActionListener(e -> updateAuthEnablement());
        keyAndPasswordRadio.addActionListener(e -> updateAuthEnablement());

        return panel;
    }

    private void updateAuthEnablement() {
        boolean pw = passwordRadio.isSelected();
        boolean k = keyRadio.isSelected();
        boolean kp = keyAndPasswordRadio.isSelected();
        passwordOnlyField.setEnabled(pw);
        keyPathField.setEnabled(k);
        keyPassphraseField.setEnabled(k);
        kpKeyPathField.setEnabled(kp);
        kpPassphraseField.setEnabled(kp);
        kpPasswordField.setEnabled(kp);
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (nameField.getText().trim().isEmpty()) {
            return new ValidationInfo("Display name is required", nameField);
        }
        if (keyRadio.isSelected()) {
            String path = keyPathField.getText().trim();
            if (path.isEmpty()) return new ValidationInfo("Key path is required", keyPathField.getTextField());
            if (!Files.isRegularFile(Paths.get(path))) {
                return new ValidationInfo("Key file does not exist", keyPathField.getTextField());
            }
        }
        if (keyAndPasswordRadio.isSelected()) {
            String path = kpKeyPathField.getText().trim();
            if (path.isEmpty()) return new ValidationInfo("Key path is required", kpKeyPathField.getTextField());
            if (!Files.isRegularFile(Paths.get(path))) {
                return new ValidationInfo("Key file does not exist", kpKeyPathField.getTextField());
            }
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        AuthMethod auth;
        if (passwordRadio.isSelected()) {
            auth = new AuthMethod.Password(new String(passwordOnlyField.getPassword()));
        } else if (keyRadio.isSelected()) {
            char[] pass = keyPassphraseField.getPassword();
            auth = new AuthMethod.Key(
                keyPathField.getText().trim(),
                pass.length == 0 ? null : new String(pass));
        } else {
            char[] pass = kpPassphraseField.getPassword();
            auth = new AuthMethod.KeyAndPassword(
                kpKeyPathField.getText().trim(),
                pass.length == 0 ? null : new String(pass),
                new String(kpPasswordField.getPassword()));
        }

        Instant now = Instant.now();
        UUID id = existing != null ? existing.id() : UUID.randomUUID();
        Instant createdAt = existing != null ? existing.createdAt() : now;

        result = new VaultAccount(
            id,
            nameField.getText().trim(),
            usernameField.getText().trim(),
            auth,
            createdAt,
            now
        );

        super.doOKAction();
    }
}
