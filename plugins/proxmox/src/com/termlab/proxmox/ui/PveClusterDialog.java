package com.termlab.proxmox.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import com.termlab.proxmox.credentials.PveCredentialResolver;
import com.termlab.proxmox.model.PveCluster;
import com.termlab.sdk.CredentialProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.net.URI;
import java.util.UUID;

public final class PveClusterDialog extends DialogWrapper {
    private final @Nullable PveCluster existing;
    private final PveCredentialResolver credentialResolver;
    private final JTextField labelField = new JTextField(28);
    private final JTextField endpointField = new JTextField(28);
    private final JComboBox<CredentialEntry> credentialCombo = new JComboBox<>();
    private PveCluster result;

    public PveClusterDialog(@Nullable Project project, @Nullable PveCluster existing) {
        this(project, existing, new PveCredentialResolver());
    }

    public PveClusterDialog(
        @Nullable Project project,
        @Nullable PveCluster existing,
        @NotNull PveCredentialResolver credentialResolver
    ) {
        super(project, true);
        this.existing = existing;
        this.credentialResolver = credentialResolver;
        setTitle(existing == null ? "Add Proxmox Cluster" : "Edit Proxmox Cluster");
        setOKButtonText(existing == null ? "Add" : "Save");
        populateCredentials();
        init();
        populateExisting();
    }

    public static @Nullable PveCluster show(@Nullable Project project, @Nullable PveCluster existing) {
        PveClusterDialog dialog = new PveClusterDialog(project, existing);
        return dialog.showAndGet() ? dialog.result : null;
    }

    public @Nullable PveCluster result() {
        return result;
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(520, 180));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        panel.add(new JLabel("Label:"), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(labelField, c);

        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        panel.add(new JLabel("Endpoint:"), c);
        c.gridx = 1;
        c.weightx = 1;
        endpointField.putClientProperty("JTextField.placeholderText", "https://proxmox.example.com:8006");
        panel.add(endpointField, c);

        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        panel.add(new JLabel("API key:"), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(credentialCombo, c);

        c.gridy++;
        c.gridx = 1;
        panel.add(new JLabel("Use a vault API Key whose key ID is user@realm!tokenid."), c);

        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (labelField.getText().trim().isEmpty()) {
            return new ValidationInfo("Label is required", labelField);
        }
        String endpoint = endpointField.getText().trim();
        if (endpoint.isEmpty()) return new ValidationInfo("Endpoint is required", endpointField);
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return new ValidationInfo("Endpoint must be an HTTPS URL", endpointField);
            }
        } catch (Exception e) {
            return new ValidationInfo("Endpoint must be a valid HTTPS URL", endpointField);
        }
        if (!(credentialCombo.getSelectedItem() instanceof CredentialEntry entry) || entry.id() == null) {
            return new ValidationInfo("Select an API token credential", credentialCombo);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        CredentialEntry entry = (CredentialEntry) credentialCombo.getSelectedItem();
        UUID id = existing == null ? UUID.randomUUID() : existing.id();
        String trusted = existing == null ? null : existing.trustedCertificateSha256();
        result = new PveCluster(
            id,
            labelField.getText().trim(),
            normalizeEndpoint(endpointField.getText().trim()),
            entry.id(),
            trusted
        );
        super.doOKAction();
    }

    private void populateCredentials() {
        credentialCombo.removeAllItems();
        credentialCombo.addItem(CredentialEntry.NONE);
        for (CredentialProvider.CredentialDescriptor descriptor : credentialResolver.listUsableDescriptors()) {
            credentialCombo.addItem(new CredentialEntry(descriptor));
        }
    }

    private void populateExisting() {
        if (existing == null) return;
        labelField.setText(existing.label());
        endpointField.setText(existing.endpoint());
        for (int i = 0; i < credentialCombo.getItemCount(); i++) {
            CredentialEntry entry = credentialCombo.getItemAt(i);
            if (existing.credentialId().equals(entry.id())) {
                credentialCombo.setSelectedIndex(i);
                break;
            }
        }
    }

    private static @NotNull String normalizeEndpoint(@NotNull String endpoint) {
        String value = endpoint.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private record CredentialEntry(
        UUID id,
        @NotNull String label
    ) {
        static final CredentialEntry NONE = new CredentialEntry(null, "<select API token credential>");

        CredentialEntry(@NotNull CredentialProvider.CredentialDescriptor descriptor) {
            this(descriptor.id(), descriptor.displayName() + "  ->  " + descriptor.subtitle());
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
