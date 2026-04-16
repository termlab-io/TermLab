package com.termlab.vault.ui;

import com.termlab.vault.keychain.DeviceSecret;
import com.termlab.vault.lock.LockManager;
import com.termlab.vault.model.Vault;
import com.termlab.vault.persistence.VaultFile;
import com.intellij.openapi.application.ApplicationManager;
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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * First-run setup dialog. Shown when the user invokes the vault but
 * {@code ~/.config/termlab/vault.enc} doesn't exist yet. Two password fields
 * (set + confirm), minimal validation, then creates an empty vault file
 * encrypted with the user's master password and the device secret.
 *
 * <p>On success, the newly-created vault is left <b>unlocked</b> in the
 * application's {@link LockManager} service — the caller can immediately
 * open {@link VaultDialog} without a second password prompt.
 */
public final class CreateVaultDialog extends DialogWrapper {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final JPasswordField passwordField = new JPasswordField(24);
    private final JPasswordField confirmField = new JPasswordField(24);

    public CreateVaultDialog(@Nullable Project project) {
        super(project, true);
        setTitle("Create Vault");
        setOKButtonText("Create Vault");
        init();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return passwordField;
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(420, 180));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        panel.add(new JLabel("Set a master password to protect your credentials."), c);
        c.gridy++; c.gridwidth = 2;
        panel.add(Box.createVerticalStrut(8), c);

        c.gridwidth = 1;
        c.gridy++; c.gridx = 0;
        panel.add(new JLabel("Master password:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(passwordField, c);

        c.gridy++; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Confirm:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(confirmField, c);

        c.gridy++; c.gridx = 0; c.gridwidth = 2;
        JLabel hint = new JLabel("Minimum " + MIN_PASSWORD_LENGTH + " characters. "
            + "If you forget this password, the vault is unrecoverable.");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize() - 1f));
        panel.add(hint, c);

        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        char[] pw = passwordField.getPassword();
        char[] cf = confirmField.getPassword();
        try {
            if (pw.length < MIN_PASSWORD_LENGTH) {
                return new ValidationInfo("Password must be at least " + MIN_PASSWORD_LENGTH + " characters", passwordField);
            }
            if (!Arrays.equals(pw, cf)) {
                return new ValidationInfo("Passwords do not match", confirmField);
            }
            return null;
        } finally {
            Arrays.fill(pw, '\0');
            Arrays.fill(cf, '\0');
        }
    }

    @Override
    protected void doOKAction() {
        char[] pwChars = passwordField.getPassword();
        byte[] password = toUtf8(pwChars);
        Arrays.fill(pwChars, '\0');

        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);

        ProgressManager.getInstance().run(new Task.Modal(null, "Creating Vault…", false) {
            private Exception failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    byte[] deviceSecret = DeviceSecret.defaultInstance().getOrCreate();
                    try {
                        VaultFile.save(lm.getVaultPath(), new Vault(), password, deviceSecret);
                        // Prime the LockManager so the caller can open VaultDialog immediately.
                        lm.unlock(password, deviceSecret);
                    } finally {
                        Arrays.fill(deviceSecret, (byte) 0);
                    }
                } catch (Exception e) {
                    failure = e;
                } finally {
                    Arrays.fill(password, (byte) 0);
                }
            }

            @Override
            public void onFinished() {
                if (failure != null) {
                    Messages.showErrorDialog(getContentPanel(),
                        "Could not create vault: " + failure.getMessage(),
                        "Vault Creation Failed");
                    return;
                }
                CreateVaultDialog.super.doOKAction();
            }
        });
    }

    private static byte[] toUtf8(char[] chars) {
        java.nio.CharBuffer cb = java.nio.CharBuffer.wrap(chars);
        java.nio.ByteBuffer bb = StandardCharsets.UTF_8.encode(cb);
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        // Clear intermediate buffer contents.
        if (bb.hasArray()) Arrays.fill(bb.array(), (byte) 0);
        return out;
    }
}
