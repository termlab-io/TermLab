package com.conch.ssh.ui;

import com.conch.sdk.CredentialProvider;
import com.conch.sdk.CredentialProvider.CredentialDescriptor;
import com.conch.ssh.model.KeyFileAuth;
import com.conch.ssh.model.PromptPasswordAuth;
import com.conch.ssh.model.SshAuth;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.model.VaultAuth;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Modal add/edit dialog for a single {@link SshHost}. Produces either a
 * brand-new host (add mode, {@code existing == null}) or an edited copy
 * of an existing one (edit mode).
 *
 * <p>Three auth modes, picked via radio buttons that mirror the layout
 * of the vault plugin's {@code AccountEditDialog}:
 * <ul>
 *   <li><b>Vault credential</b> — combo populated from every
 *       {@link CredentialProvider} extension filtered to SSH-usable
 *       kinds, plus a {@code <no credential>} option that produces
 *       {@code VaultAuth(null)} and makes the session provider run the
 *       vault picker at connect time.</li>
 *   <li><b>Password (prompt every connect)</b> — no fields; the connect
 *       flow shows {@code InlineCredentialPromptDialog.promptPassword}
 *       every time.</li>
 *   <li><b>SSH key file</b> — key path with browse button; passphrase
 *       is prompted at connect time by
 *       {@code InlineCredentialPromptDialog.promptPassphrase}.</li>
 * </ul>
 */
public final class HostEditDialog extends DialogWrapper {

    private static final ExtensionPointName<CredentialProvider> EP_NAME =
        ExtensionPointName.create("com.conch.core.credentialProvider");

    private static final Set<CredentialProvider.Kind> SUPPORTED_KINDS = EnumSet.of(
        CredentialProvider.Kind.ACCOUNT_PASSWORD,
        CredentialProvider.Kind.ACCOUNT_KEY,
        CredentialProvider.Kind.ACCOUNT_KEY_AND_PASSWORD,
        CredentialProvider.Kind.SSH_KEY
    );

    private final @Nullable SshHost existing;

    private final JTextField labelField = new JTextField(24);
    private final JTextField hostField = new JTextField(24);
    private final JSpinner portSpinner = new JSpinner(
        new SpinnerNumberModel(SshHost.DEFAULT_PORT, 1, 65535, 1));
    private final JTextField usernameField = new JTextField(24);

    private final JRadioButton vaultRadio = new JRadioButton("Vault credential", true);
    private final JRadioButton promptRadio = new JRadioButton("Password (prompt every connect)");
    private final JRadioButton keyFileRadio = new JRadioButton("SSH key file");

    private final JComboBox<CredentialEntry> credentialCombo = new JComboBox<>();
    private final TextFieldWithBrowseButton keyPathField = new TextFieldWithBrowseButton();

    private SshHost result;

    public HostEditDialog(@Nullable Project project, @Nullable SshHost existing) {
        super(project, true);
        this.existing = existing;
        setTitle(existing == null ? "Add SSH Host" : "Edit SSH Host");
        setOKButtonText(existing == null ? "Add" : "Save");
        wireKeyPathChooser();
        populateCredentialCombo();
        init();
        populateFromExisting();
        updateEnablement();
    }

    public static @Nullable SshHost show(@Nullable Project project, @Nullable SshHost existing) {
        HostEditDialog dlg = new HostEditDialog(project, existing);
        return dlg.showAndGet() ? dlg.result : null;
    }

    private void wireKeyPathChooser() {
        keyPathField.addBrowseFolderListener(new TextBrowseFolderListener(
            FileChooserDescriptorFactory.createSingleFileDescriptor()));
    }

    private void populateCredentialCombo() {
        credentialCombo.removeAllItems();
        credentialCombo.addItem(CredentialEntry.NONE);
        if (ApplicationManager.getApplication() == null) return;

        List<CredentialEntry> entries = new ArrayList<>();
        for (CredentialProvider provider : EP_NAME.getExtensionList()) {
            if (!provider.isAvailable()) continue;
            for (CredentialDescriptor d : provider.listCredentials()) {
                if (!SUPPORTED_KINDS.contains(d.kind())) continue;
                entries.add(new CredentialEntry(d));
            }
        }
        entries.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
        for (CredentialEntry e : entries) credentialCombo.addItem(e);
    }

    private void populateFromExisting() {
        if (existing == null) {
            vaultRadio.setSelected(true);
            credentialCombo.setSelectedItem(CredentialEntry.NONE);
            return;
        }
        labelField.setText(existing.label());
        hostField.setText(existing.host());
        portSpinner.setValue(existing.port());
        usernameField.setText(existing.username());

        switch (existing.auth()) {
            case VaultAuth v -> {
                vaultRadio.setSelected(true);
                selectVaultEntry(v.credentialId());
            }
            case PromptPasswordAuth p -> promptRadio.setSelected(true);
            case KeyFileAuth k -> {
                keyFileRadio.setSelected(true);
                keyPathField.setText(k.keyFilePath());
            }
        }
    }

    private void selectVaultEntry(@Nullable UUID savedId) {
        if (savedId == null) {
            credentialCombo.setSelectedItem(CredentialEntry.NONE);
            return;
        }
        for (int i = 0; i < credentialCombo.getItemCount(); i++) {
            CredentialEntry entry = credentialCombo.getItemAt(i);
            if (entry.matches(savedId)) {
                credentialCombo.setSelectedIndex(i);
                return;
            }
        }
        CredentialEntry missing = CredentialEntry.missing(savedId);
        credentialCombo.addItem(missing);
        credentialCombo.setSelectedItem(missing);
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(520, 320));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        panel.add(new JLabel("Label:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(labelField, c);

        c.gridy++; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Host:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(hostField, c);

        c.gridy++; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Port:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(portSpinner, c);

        c.gridy++; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Username:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(usernameField, c);

        ButtonGroup group = new ButtonGroup();
        group.add(vaultRadio);
        group.add(promptRadio);
        group.add(keyFileRadio);

        c.gridy++; c.gridx = 0; c.gridwidth = 2; c.weightx = 1;
        panel.add(new JLabel("Auth method:"), c);

        c.gridy++; c.gridx = 0; c.gridwidth = 2;
        panel.add(vaultRadio, c);
        c.gridy++; c.gridx = 0; c.gridwidth = 1; c.weightx = 0;
        panel.add(Box.createHorizontalStrut(24), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(credentialCombo, c);

        c.gridy++; c.gridx = 0; c.gridwidth = 2; c.weightx = 1;
        panel.add(promptRadio, c);

        c.gridy++; c.gridx = 0; c.gridwidth = 2;
        panel.add(keyFileRadio, c);
        c.gridy++; c.gridx = 0; c.gridwidth = 1; c.weightx = 0;
        panel.add(Box.createHorizontalStrut(24), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(keyPathField, c);

        vaultRadio.addActionListener(e -> updateEnablement());
        promptRadio.addActionListener(e -> updateEnablement());
        keyFileRadio.addActionListener(e -> updateEnablement());

        return panel;
    }

    private void updateEnablement() {
        credentialCombo.setEnabled(vaultRadio.isSelected());
        keyPathField.setEnabled(keyFileRadio.isSelected());
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return labelField;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (labelField.getText().trim().isEmpty()) {
            return new ValidationInfo("Label is required", labelField);
        }
        if (hostField.getText().trim().isEmpty()) {
            return new ValidationInfo("Host is required", hostField);
        }
        if (usernameField.getText().trim().isEmpty()) {
            return new ValidationInfo("Username is required", usernameField);
        }
        if (keyFileRadio.isSelected()) {
            String path = keyPathField.getText().trim();
            if (path.isEmpty()) {
                return new ValidationInfo("Key file path is required", keyPathField.getTextField());
            }
            if (!Files.isRegularFile(Paths.get(path))) {
                return new ValidationInfo("Key file does not exist", keyPathField.getTextField());
            }
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        String label = labelField.getText().trim();
        String host = hostField.getText().trim();
        int port = (Integer) portSpinner.getValue();
        String username = usernameField.getText().trim();

        SshAuth auth;
        if (promptRadio.isSelected()) {
            auth = new PromptPasswordAuth();
        } else if (keyFileRadio.isSelected()) {
            auth = new KeyFileAuth(keyPathField.getText().trim());
        } else {
            CredentialEntry selected = (CredentialEntry) credentialCombo.getSelectedItem();
            UUID credentialId = selected == null ? null : selected.id();
            auth = new VaultAuth(credentialId);
        }

        if (existing == null) {
            result = SshHost.create(label, host, port, username, auth);
        } else {
            result = existing.withEdited(label, host, port, username, auth);
        }

        super.doOKAction();
    }

    // -- combo entry ---------------------------------------------------------

    private static final class CredentialEntry {
        static final CredentialEntry NONE = new CredentialEntry(null, "<no credential>", null);

        private final @Nullable UUID id;
        private final @NotNull String label;
        private final @Nullable CredentialProvider.Kind kind;

        private CredentialEntry(@Nullable UUID id,
                                @NotNull String label,
                                @Nullable CredentialProvider.Kind kind) {
            this.id = id;
            this.label = label;
            this.kind = kind;
        }

        CredentialEntry(@NotNull CredentialDescriptor descriptor) {
            this(descriptor.id(),
                descriptor.displayName() + "  ·  " + descriptor.subtitle(),
                descriptor.kind());
        }

        static CredentialEntry missing(@NotNull UUID id) {
            return new CredentialEntry(id, "<missing credential " + id + ">", null);
        }

        @Nullable UUID id() { return id; }
        @NotNull String label() { return label; }
        boolean matches(@NotNull UUID other) { return Objects.equals(id, other); }

        @Override public String toString() { return label; }
    }
}
