package com.conch.vault.ui;

import com.conch.vault.keygen.KeyGenAlgorithm;
import com.conch.vault.keygen.SshKeyGenerator;
import com.conch.vault.lock.LockManager;
import com.conch.vault.model.Vault;
import com.conch.vault.model.VaultKey;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

/**
 * Generate a new SSH key pair and record it in the vault.
 *
 * <p>Three fields:
 * <ul>
 *   <li><b>Name</b> — required, user-facing label stored in {@link VaultKey#name()}.
 *       Shown in the vault's Keys tab and in the credential picker that the
 *       future SSH plugin will use.</li>
 *   <li><b>Algorithm</b> — Ed25519 by default, also ECDSA P-256/P-384,
 *       RSA 3072/4096.</li>
 *   <li><b>Comment</b> — optional. Appended to the generated public key line
 *       (goes into the {@code .pub} file itself). Cosmetic.</li>
 * </ul>
 *
 * <p>The heavy lifting (key generation — slow for RSA-4096) runs on a
 * background thread via {@link ProgressManager.Task.Modal}, so the Swing
 * UI stays responsive. On success, a {@link VaultKey} is added to the
 * unlocked vault's {@code keys} list and the vault is re-encrypted via
 * {@link LockManager#save()}.
 */
public final class KeyGenDialog extends DialogWrapper {

    private final LockManager lockManager;

    private final JTextField nameField = new JTextField(24);
    private final JComboBox<KeyGenAlgorithm> algorithmCombo =
        new JComboBox<>(KeyGenAlgorithm.values());
    private final JTextField commentField = new JTextField(24);

    public KeyGenDialog(@Nullable Project project, @NotNull LockManager lockManager) {
        super(project, true);
        this.lockManager = lockManager;
        setTitle("Generate SSH Key");
        setOKButtonText("Generate");
        algorithmCombo.setSelectedItem(KeyGenAlgorithm.ED25519);
        init();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return nameField;
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(480, 220));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridy = 0; c.gridwidth = 2; c.weightx = 1;
        panel.add(new JLabel("Generate a new SSH key pair under ~/.ssh/conch_vault/."), c);

        c.gridwidth = 1;
        c.gridy++;
        c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Name:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(nameField, c);

        c.gridy++;
        c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Algorithm:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(algorithmCombo, c);

        c.gridy++;
        c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Comment:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(commentField, c);

        c.gridy++; c.gridwidth = 2; c.gridx = 0;
        JLabel hint = new JLabel("<html>"
            + "<b>Name</b> labels the key in the vault and in the SSH plugin's picker.<br>"
            + "<b>Comment</b> is appended to the public key line "
            + "(usually your email or hostname). Cosmetic only."
            + "</html>");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize() - 1f));
        panel.add(hint, c);

        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (nameField.getText().trim().isEmpty()) {
            return new ValidationInfo("Name is required", nameField);
        }
        Vault vault = lockManager.getVault();
        if (vault == null) {
            return new ValidationInfo("Vault must be unlocked to generate a key", algorithmCombo);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        KeyGenAlgorithm algo = (KeyGenAlgorithm) algorithmCombo.getSelectedItem();
        String name = nameField.getText().trim();
        String comment = commentField.getText().trim();
        if (algo == null) return;

        ProgressManager.getInstance().run(new Task.Modal(null, "Generating " + algo.displayName() + "…", false) {
            private VaultKey result;
            private Exception failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    result = new SshKeyGenerator().generate(algo, name, comment);
                } catch (Exception e) {
                    failure = e;
                }
            }

            @Override
            public void onFinished() {
                if (failure != null) {
                    Messages.showErrorDialog(getContentPanel(),
                        "Could not generate key: " + failure.getMessage(),
                        "Key Generation Failed");
                    return;
                }
                if (result == null) return;

                Vault vault = lockManager.getVault();
                if (vault == null) {
                    Messages.showErrorDialog(getContentPanel(),
                        "Vault was locked before the key could be recorded. The key files on "
                            + "disk are still valid, but the vault doesn't know about them.",
                        "Vault Locked");
                    return;
                }
                vault.keys.add(result);
                try {
                    lockManager.save();
                } catch (IOException e) {
                    Messages.showErrorDialog(getContentPanel(),
                        "Key generated and written to disk, but could not update vault file: "
                            + e.getMessage(),
                        "Vault Save Failed");
                    return;
                }

                Messages.showInfoMessage(getContentPanel(),
                    "Key generated:\n"
                        + "  " + result.privatePath() + "\n"
                        + "  " + result.publicPath() + "\n\n"
                        + "Fingerprint: " + result.fingerprint(),
                    "Key Generated");
                KeyGenDialog.super.doOKAction();
            }
        });
    }
}
