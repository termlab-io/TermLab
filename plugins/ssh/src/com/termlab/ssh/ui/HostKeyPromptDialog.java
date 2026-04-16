package com.termlab.ssh.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;

/**
 * First-contact host-key prompt, shown when
 * {@code KnownHostsFile.match} reports {@code UNKNOWN}. The user sees:
 *
 * <ul>
 *   <li>The host address being connected to</li>
 *   <li>The server key's key type ({@code ssh-ed25519}, {@code ssh-rsa}, …)</li>
 *   <li>The SHA-256 fingerprint — same format {@code ssh-keygen -l} prints</li>
 * </ul>
 *
 * and picks one of three {@link Decision}s.
 *
 * <p>This dialog intentionally never appears on a {@code MISMATCH} — that's
 * a hard reject handled upstream by the verifier. "Accept anyway" is not
 * something we want the user able to click on a MITM signal.
 */
public final class HostKeyPromptDialog extends DialogWrapper {

    /** User's choice from the host-key prompt. */
    public enum Decision {
        /** Trust now and write to {@code known_hosts}. */
        ACCEPT_AND_SAVE,
        /** Trust for this session only — don't persist. */
        ACCEPT_ONCE,
        /** Abort the connection. */
        REJECT
    }

    private final String hostSpec;
    private final String keyType;
    private final String fingerprint;

    private Decision decision = Decision.REJECT;

    public HostKeyPromptDialog(@Nullable Project project,
                                @NotNull String hostSpec,
                                @NotNull String keyType,
                                @NotNull String fingerprint) {
        super(project, true);
        this.hostSpec = hostSpec;
        this.keyType = keyType;
        this.fingerprint = fingerprint;
        setTitle("Unknown Host Key");
        setOKButtonText("Accept && Save");
        setCancelButtonText("Reject");
        init();
    }

    /** @return the user's choice. {@link Decision#REJECT} if cancelled. */
    public @NotNull Decision showAndDecide() {
        show();
        // If the user clicked Cancel or closed the dialog, decision stays
        // REJECT (its initialized value). OK / Accept Once both overwrite it.
        if (getExitCode() == OK_EXIT_CODE && decision == Decision.REJECT) {
            decision = Decision.ACCEPT_AND_SAVE;
        }
        return decision;
    }

    @Override
    protected Action @NotNull [] createActions() {
        // Three-way choice: Accept & Save (OK), Accept Once (extra), Reject (Cancel).
        Action acceptOnce = new AbstractAction("Accept Once") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                decision = Decision.ACCEPT_ONCE;
                close(OK_EXIT_CODE);
            }
        };
        return new Action[]{getOKAction(), acceptOnce, getCancelAction()};
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setBorder(JBUI.Borders.empty(12));
        panel.setPreferredSize(new Dimension(560, 220));

        JLabel icon = new JLabel(AllIcons.General.QuestionDialog);
        icon.setVerticalAlignment(SwingConstants.TOP);
        panel.add(icon, BorderLayout.WEST);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("The authenticity of '" + hostSpec + "' can't be established.");
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        body.add(title);

        body.add(Box.createVerticalStrut(8));

        JLabel summary = new JLabel(
            "<html>TermLab has not seen this host before. Verify the fingerprint "
                + "out of band (phone the admin, check the control-panel, etc.) "
                + "before trusting it.</html>");
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(summary);

        body.add(Box.createVerticalStrut(12));

        body.add(labelRow("Key type:", keyType));
        body.add(Box.createVerticalStrut(4));
        body.add(labelRow("Fingerprint:", fingerprint));

        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private static JComponent labelRow(String label, String value) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel lbl = new JLabel(label);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        JTextField val = new JTextField(value);
        val.setEditable(false);
        val.setBorder(null);
        val.setOpaque(false);
        val.setColumns(Math.max(20, value.length()));
        ((JTextComponent) val).setCaretPosition(0);
        row.add(lbl);
        row.add(val);
        return row;
    }

    @Override
    protected void doOKAction() {
        decision = Decision.ACCEPT_AND_SAVE;
        super.doOKAction();
    }
}
