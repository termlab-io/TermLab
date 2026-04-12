package com.conch.ssh.ui;

import com.conch.sdk.CredentialProvider;
import com.conch.sdk.CredentialProvider.CredentialDescriptor;
import com.conch.ssh.client.ConchSshClient;
import com.conch.ssh.model.HostStore;
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
import org.apache.sshd.client.config.hosts.HostConfigEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
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

    private final JToggleButton advancedToggle = new JToggleButton("Advanced >", false);
    private final JPanel advancedBody = new JPanel(new GridBagLayout());

    private final JRadioButton noProxyRadio = new JRadioButton("No proxy", true);
    private final JRadioButton proxyCommandRadio = new JRadioButton("Proxy command");
    private final JRadioButton proxyJumpRadio = new JRadioButton("Proxy jump host");

    private final JTextField proxyCommandField = new JTextField(24);
    private final JComboBox<ProxyJumpEntry> proxyJumpCombo = new JComboBox<>();

    private SshHost result;

    public HostEditDialog(@Nullable Project project, @Nullable SshHost existing) {
        super(project, true);
        this.existing = existing;
        setTitle(existing == null ? "Add SSH Host" : "Edit SSH Host");
        setOKButtonText(existing == null ? "Add" : "Save");
        wireKeyPathChooser();
        populateCredentialCombo();
        populateProxyJumpCombo();
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

    private void populateProxyJumpCombo() {
        proxyJumpCombo.removeAllItems();
        proxyJumpCombo.addItem(ProxyJumpEntry.NONE);

        Map<String, ProxyJumpEntry> byValue = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        HostStore store = ApplicationManager.getApplication() == null
            ? null
            : ApplicationManager.getApplication().getService(HostStore.class);
        if (store != null) {
            for (SshHost host : store.getHosts()) {
                if (existing != null && existing.id().equals(host.id())) continue;
                String jumpSpec = buildProxyJumpSpec(host);
                byValue.putIfAbsent(jumpSpec,
                    new ProxyJumpEntry(jumpSpec, host.label() + "  ->  " + jumpSpec));
            }
        }

        Path configPath = HostConfigEntry.getDefaultHostConfigFile();
        if (Files.isRegularFile(configPath)) {
            try {
                List<HostConfigEntry> entries = HostConfigEntry.readHostConfigEntries(configPath);
                for (HostConfigEntry entry : entries) {
                    String patterns = entry.getHost();
                    if (patterns == null) continue;
                    for (String token : HostConfigEntry.parseConfigValue(patterns)) {
                        String alias = token.trim();
                        if (alias.isEmpty()) continue;
                        if (alias.startsWith("!")) continue;
                        if (alias.contains("*") || alias.contains("?")) continue;
                        byValue.putIfAbsent(alias, new ProxyJumpEntry(alias, alias + "  (.ssh/config)"));
                    }
                }
            } catch (Exception ignored) {
                // Best effort: a malformed ~/.ssh/config should not block editing hosts.
            }
        }

        for (ProxyJumpEntry entry : byValue.values()) {
            proxyJumpCombo.addItem(entry);
        }
    }

    private static @NotNull String buildProxyJumpSpec(@NotNull SshHost host) {
        String userPrefix = host.username().isBlank() ? "" : host.username() + "@";
        String portSuffix = host.port() == SshHost.DEFAULT_PORT ? "" : ":" + host.port();
        return userPrefix + host.host() + portSuffix;
    }

    private void populateFromExisting() {
        if (existing == null) {
            vaultRadio.setSelected(true);
            credentialCombo.setSelectedItem(CredentialEntry.NONE);
            noProxyRadio.setSelected(true);
            setAdvancedExpanded(false);
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

        if (existing.proxyCommand() != null) {
            proxyCommandRadio.setSelected(true);
            proxyCommandField.setText(existing.proxyCommand());
            setAdvancedExpanded(true);
        } else if (existing.proxyJump() != null) {
            proxyJumpRadio.setSelected(true);
            selectProxyJumpEntry(existing.proxyJump());
            setAdvancedExpanded(true);
        } else {
            noProxyRadio.setSelected(true);
            setAdvancedExpanded(false);
        }
    }

    private void selectProxyJumpEntry(@NotNull String jump) {
        for (int i = 0; i < proxyJumpCombo.getItemCount(); i++) {
            ProxyJumpEntry item = proxyJumpCombo.getItemAt(i);
            if (item != null && item.matches(jump)) {
                proxyJumpCombo.setSelectedIndex(i);
                return;
            }
        }

        ProxyJumpEntry custom = ProxyJumpEntry.custom(jump);
        proxyJumpCombo.addItem(custom);
        proxyJumpCombo.setSelectedItem(custom);
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
        panel.setPreferredSize(new Dimension(560, 460));

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
        panel.add(new JLabel("Host:"), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(hostField, c);

        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        panel.add(new JLabel("Port:"), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(portSpinner, c);

        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        panel.add(new JLabel("Username:"), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(usernameField, c);

        ButtonGroup authGroup = new ButtonGroup();
        authGroup.add(vaultRadio);
        authGroup.add(promptRadio);
        authGroup.add(keyFileRadio);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        panel.add(new JLabel("Auth method:"), c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 2;
        panel.add(vaultRadio, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        panel.add(Box.createHorizontalStrut(24), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(credentialCombo, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        panel.add(promptRadio, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 2;
        panel.add(keyFileRadio, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        panel.add(Box.createHorizontalStrut(24), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(keyPathField, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        panel.add(advancedToggle, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        panel.add(createAdvancedBody(), c);

        vaultRadio.addActionListener(e -> updateEnablement());
        promptRadio.addActionListener(e -> updateEnablement());
        keyFileRadio.addActionListener(e -> updateEnablement());

        noProxyRadio.addActionListener(e -> updateEnablement());
        proxyCommandRadio.addActionListener(e -> updateEnablement());
        proxyJumpRadio.addActionListener(e -> updateEnablement());

        advancedToggle.addActionListener(e -> {
            setAdvancedExpanded(advancedToggle.isSelected());
            updateEnablement();
        });

        return panel;
    }

    private @NotNull JPanel createAdvancedBody() {
        advancedBody.setBorder(JBUI.Borders.empty(0, 14, 0, 0));

        ButtonGroup proxyGroup = new ButtonGroup();
        proxyGroup.add(noProxyRadio);
        proxyGroup.add(proxyCommandRadio);
        proxyGroup.add(proxyJumpRadio);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        advancedBody.add(noProxyRadio, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 2;
        advancedBody.add(proxyCommandRadio, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        advancedBody.add(Box.createHorizontalStrut(24), c);
        c.gridx = 1;
        c.weightx = 1;
        advancedBody.add(proxyCommandField, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        advancedBody.add(proxyJumpRadio, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        advancedBody.add(Box.createHorizontalStrut(24), c);
        c.gridx = 1;
        c.weightx = 1;
        advancedBody.add(proxyJumpCombo, c);

        return advancedBody;
    }

    private void setAdvancedExpanded(boolean expanded) {
        advancedToggle.setSelected(expanded);
        advancedToggle.setText(expanded ? "Advanced v" : "Advanced >");
        advancedBody.setVisible(expanded);
    }

    private void updateEnablement() {
        credentialCombo.setEnabled(vaultRadio.isSelected());
        keyPathField.setEnabled(keyFileRadio.isSelected());

        proxyCommandField.setEnabled(proxyCommandRadio.isSelected());
        proxyJumpCombo.setEnabled(proxyJumpRadio.isSelected());

        advancedBody.setVisible(advancedToggle.isSelected());
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

        if (proxyCommandRadio.isSelected()) {
            String command = proxyCommandField.getText().trim();
            if (command.isEmpty()) {
                return new ValidationInfo("Proxy command is required", proxyCommandField);
            }
            if (ConchSshClient.proxyJumpFromProxyCommand(command) == null) {
                return new ValidationInfo(
                    "Supported form: ssh -W %h:%p <jump-host>",
                    proxyCommandField);
            }
        }

        if (proxyJumpRadio.isSelected()) {
            ProxyJumpEntry selected = (ProxyJumpEntry) proxyJumpCombo.getSelectedItem();
            if (selected == null || selected.value() == null) {
                return new ValidationInfo("Select a proxy jump host", proxyJumpCombo);
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

        String proxyCommand = null;
        String proxyJump = null;
        if (proxyCommandRadio.isSelected()) {
            proxyCommand = trimToNull(proxyCommandField.getText());
        } else if (proxyJumpRadio.isSelected()) {
            ProxyJumpEntry selected = (ProxyJumpEntry) proxyJumpCombo.getSelectedItem();
            proxyJump = selected == null ? null : trimToNull(selected.value());
        }

        if (existing == null) {
            result = SshHost.create(label, host, port, username, auth, proxyCommand, proxyJump);
        } else {
            result = existing.withEdited(label, host, port, username, auth, proxyCommand, proxyJump);
        }

        super.doOKAction();
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    private static final class ProxyJumpEntry {
        static final ProxyJumpEntry NONE = new ProxyJumpEntry(null, "<select jump host>");

        private final @Nullable String value;
        private final @NotNull String label;

        ProxyJumpEntry(@Nullable String value, @NotNull String label) {
            this.value = value;
            this.label = label;
        }

        static @NotNull ProxyJumpEntry custom(@NotNull String value) {
            return new ProxyJumpEntry(value, value + "  (custom)");
        }

        @Nullable String value() {
            return value;
        }

        boolean matches(@NotNull String other) {
            return Objects.equals(value, other);
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
