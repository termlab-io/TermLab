package com.conch.vault.ui;

import com.conch.vault.crypto.WrongPasswordException;
import com.conch.vault.keychain.DeviceSecret;
import com.conch.vault.lock.LockManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Prompts for the master password and unlocks the supplied
 * {@link LockManager}. Argon2id runs on a background thread via
 * {@link ProgressManager} so the Swing UI stays responsive; wrong-password
 * failures are shown inline without dismissing the dialog.
 */
public final class UnlockDialog extends DialogWrapper {

    private final LockManager lockManager;
    private final JPasswordField passwordField = new JPasswordField(24);
    private final JLabel errorLabel = new JLabel(" ");

    public UnlockDialog(@Nullable Project project, @NotNull LockManager lockManager) {
        super(project, true);
        this.lockManager = lockManager;
        setTitle("Unlock Vault");
        setOKButtonText("Unlock");
        errorLabel.setForeground(new Color(0xCC6666));
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
        panel.setPreferredSize(new Dimension(380, 140));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
        panel.add(new JLabel("Enter your master password."), c);

        c.gridwidth = 1;
        c.gridy++; c.gridx = 0;
        panel.add(new JLabel("Password:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(passwordField, c);

        c.gridy++; c.gridx = 0; c.gridwidth = 2;
        panel.add(errorLabel, c);

        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (passwordField.getPassword().length == 0) {
            return new ValidationInfo("Password is required", passwordField);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        char[] pwChars = passwordField.getPassword();
        byte[] password = toUtf8(pwChars);
        Arrays.fill(pwChars, '\0');

        // Clear any prior error.
        errorLabel.setText(" ");

        ProgressManager.getInstance().run(new Task.Modal(null, "Unlocking Vault…", false) {
            private Exception failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                byte[] deviceSecret = null;
                try {
                    deviceSecret = DeviceSecret.defaultInstance().getOrCreate();
                    lockManager.unlock(password, deviceSecret);
                } catch (Exception e) {
                    failure = e;
                } finally {
                    Arrays.fill(password, (byte) 0);
                    if (deviceSecret != null) Arrays.fill(deviceSecret, (byte) 0);
                }
            }

            @Override
            public void onFinished() {
                if (failure instanceof WrongPasswordException) {
                    errorLabel.setText("Wrong password");
                    passwordField.requestFocusInWindow();
                    passwordField.selectAll();
                    return;
                }
                if (failure != null) {
                    errorLabel.setText("Unlock failed: " + failure.getMessage());
                    return;
                }
                UnlockDialog.super.doOKAction();
            }
        });
    }

    private static byte[] toUtf8(char[] chars) {
        java.nio.CharBuffer cb = java.nio.CharBuffer.wrap(chars);
        java.nio.ByteBuffer bb = StandardCharsets.UTF_8.encode(cb);
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        if (bb.hasArray()) Arrays.fill(bb.array(), (byte) 0);
        return out;
    }
}
