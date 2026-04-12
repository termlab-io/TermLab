package com.conch.ssh.ui;

import com.conch.ssh.model.SshHost;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Small modal that pops during connect when the host is configured for
 * {@code PromptPasswordAuth} or {@code KeyFileAuth}. One
 * {@link JPasswordField}, a contextual headline, and — for the
 * passphrase variant — a "leave blank if unencrypted" hint.
 *
 * <p>Returns a freshly-allocated {@code char[]} on OK (caller owns and
 * is expected to zero it after use); {@code null} on cancel. The
 * session provider already runs on the EDT before handing off to
 * {@code Task.Modal}, so this dialog can be shown synchronously from
 * {@code createSession}.
 */
public final class InlineCredentialPromptDialog extends DialogWrapper {

    private final String headline;
    private final @Nullable String hint;
    private final JPasswordField field = new JPasswordField(24);

    private char @Nullable [] result;

    private InlineCredentialPromptDialog(@Nullable Project project,
                                          @NotNull String title,
                                          @NotNull String headline,
                                          @Nullable String hint) {
        super(project, true);
        this.headline = headline;
        this.hint = hint;
        setTitle(title);
        setOKButtonText("Connect");
        init();
    }

    /** Prompt for a password. Returns chars on OK or null on cancel. */
    public static char @Nullable [] promptPassword(@Nullable Project project, @NotNull SshHost host) {
        InlineCredentialPromptDialog dlg = new InlineCredentialPromptDialog(
            project,
            "SSH Password",
            "Password for " + host.username() + "@" + host.host() + ":" + host.port(),
            null);
        return dlg.showAndGet() ? dlg.result : null;
    }

    /**
     * Prompt for a key passphrase. Returns chars on OK, {@code null} on cancel.
     * Empty input is still returned as an empty {@code char[]} — callers should
     * treat zero-length as "no passphrase" when handing to MINA.
     */
    public static char @Nullable [] promptPassphrase(
        @Nullable Project project,
        @NotNull SshHost host,
        @NotNull String keyFilePath
    ) {
        InlineCredentialPromptDialog dlg = new InlineCredentialPromptDialog(
            project,
            "SSH Key Passphrase",
            "Passphrase for " + keyFilePath
                + " (" + host.username() + "@" + host.host() + ")",
            null);
        return dlg.showAndGet() ? dlg.result : null;
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(12));
        panel.setPreferredSize(new Dimension(460, hint == null ? 100 : 130));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;

        JLabel headlineLabel = new JLabel(headline);
        headlineLabel.setFont(headlineLabel.getFont().deriveFont(Font.BOLD));
        panel.add(headlineLabel, c);

        c.gridy++;
        c.gridwidth = 1;
        c.weightx = 0;
        panel.add(new JLabel("Secret:"), c);

        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);

        if (hint != null) {
            c.gridx = 0;
            c.gridy++;
            c.gridwidth = 2;
            c.weightx = 1;
            JLabel hintLabel = new JLabel(hint);
            hintLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            panel.add(hintLabel, c);
        }

        return panel;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return field;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        return null;  // empty input is legal (unencrypted key case)
    }

    @Override
    protected void doOKAction() {
        result = field.getPassword();  // fresh char[] allocation — caller owns it
        super.doOKAction();
    }
}
