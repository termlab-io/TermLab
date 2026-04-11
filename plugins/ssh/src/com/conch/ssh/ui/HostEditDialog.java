package com.conch.ssh.ui;

import com.conch.sdk.CredentialProvider;
import com.conch.sdk.CredentialProvider.CredentialDescriptor;
import com.conch.ssh.model.SshAuth;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.model.VaultAuth;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Modal add/edit dialog for a single {@link SshHost}. Produces either a
 * brand-new host (add mode, {@code existing == null}) or an edited copy
 * of an existing one (edit mode) via
 * {@link SshHost#withEdited(String, String, int, String, SshAuth)}.
 *
 * <p>The credential combo is populated from every
 * {@link CredentialProvider} extension in the application area,
 * filtered to kinds that make sense for SSH ({@code ACCOUNT_PASSWORD},
 * {@code ACCOUNT_KEY}, {@code ACCOUNT_KEY_AND_PASSWORD}, {@code SSH_KEY}).
 * The first entry is always a synthetic {@code <no credential>} option —
 * picking it stores {@code credentialId = null} on the host, which makes
 * the session provider fall back to the vault picker at connect time.
 *
 * <p>Caller is responsible for persisting the returned host via
 * {@code HostStore.addHost} / {@code HostStore.updateHost} + {@code save()}.
 */
public final class HostEditDialog extends DialogWrapper {

    private static final ExtensionPointName<CredentialProvider> EP_NAME =
        ExtensionPointName.create("com.conch.core.credentialProvider");

    /** Kinds the SSH plugin can actually authenticate with. */
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
    private final JComboBox<CredentialEntry> credentialCombo = new JComboBox<>();

    private SshHost result;

    public HostEditDialog(@Nullable Project project, @Nullable SshHost existing) {
        super(project, true);
        this.existing = existing;
        setTitle(existing == null ? "Add SSH Host" : "Edit SSH Host");
        setOKButtonText(existing == null ? "Add" : "Save");
        populateCredentialCombo();
        init();
        populateFromExisting();
    }

    /** @return the host produced by this dialog, or {@code null} if cancelled. */
    public static @Nullable SshHost show(@Nullable Project project, @Nullable SshHost existing) {
        HostEditDialog dlg = new HostEditDialog(project, existing);
        return dlg.showAndGet() ? dlg.result : null;
    }

    private void populateCredentialCombo() {
        credentialCombo.removeAllItems();
        credentialCombo.addItem(CredentialEntry.NONE);

        if (ApplicationManager.getApplication() == null) return;

        List<CredentialEntry> entries = new ArrayList<>();
        for (CredentialProvider provider : EP_NAME.getExtensionList()) {
            if (!provider.isAvailable()) continue;
            for (CredentialDescriptor descriptor : provider.listCredentials()) {
                if (!SUPPORTED_KINDS.contains(descriptor.kind())) continue;
                entries.add(new CredentialEntry(descriptor));
            }
        }
        entries.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
        for (CredentialEntry e : entries) credentialCombo.addItem(e);
    }

    private void populateFromExisting() {
        if (existing == null) {
            credentialCombo.setSelectedItem(CredentialEntry.NONE);
            return;
        }
        labelField.setText(existing.label());
        hostField.setText(existing.host());
        portSpinner.setValue(existing.port());
        usernameField.setText(existing.username());

        UUID savedId = existing.auth() instanceof VaultAuth vaultAuth
            ? vaultAuth.credentialId()
            : null;
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
        // Saved credential is no longer available (vault locked, deleted,
        // etc). Keep the id around as a "missing" entry so saving without
        // touching the combo doesn't silently drop the reference.
        CredentialEntry missing = CredentialEntry.missing(savedId);
        credentialCombo.addItem(missing);
        credentialCombo.setSelectedItem(missing);
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(460, 200));

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

        c.gridy++; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Credential:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(credentialCombo, c);

        return panel;
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
        return null;
    }

    @Override
    protected void doOKAction() {
        String label = labelField.getText().trim();
        String host = hostField.getText().trim();
        int port = (Integer) portSpinner.getValue();
        String username = usernameField.getText().trim();

        CredentialEntry selected = (CredentialEntry) credentialCombo.getSelectedItem();
        UUID credentialId = selected == null ? null : selected.id();
        SshAuth auth = new VaultAuth(credentialId);

        if (existing == null) {
            result = SshHost.create(label, host, port, username, auth);
        } else {
            result = existing.withEdited(label, host, port, username, auth);
        }

        super.doOKAction();
    }

    /**
     * Combo entry. Wraps either a real {@link CredentialDescriptor}, a
     * "no credential" marker, or a "missing" marker for a saved id that
     * no provider currently resolves.
     */
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

        boolean matches(@NotNull UUID other) {
            return Objects.equals(id, other);
        }

        @Override public String toString() { return label; }
    }
}
