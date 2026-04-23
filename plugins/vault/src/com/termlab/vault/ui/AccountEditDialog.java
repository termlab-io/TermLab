package com.termlab.vault.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import com.termlab.vault.model.AuthMethod;
import com.termlab.vault.model.VaultAccount;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

public final class AccountEditDialog extends DialogWrapper {
    private static final String LOGIN_CARD = "login";
    private static final String API_KEY_CARD = "apiKey";
    private static final String SECURE_NOTE_CARD = "secureNote";

    private final @Nullable VaultAccount existing;
    private final JComboBox<CredentialType> typeCombo = new JComboBox<>(CredentialType.values());
    private final JTextField nameField = new JTextField(28);
    private final JPanel cards = new JPanel(new CardLayout());

    private final JTextField loginUsernameField = new JTextField(28);
    private final JPasswordField loginPasswordField = new JPasswordField(28);
    private final JCheckBox sshKeyLoginCheckBox = new JCheckBox("Use SSH key for this login");
    private final TextFieldWithBrowseButton loginKeyPathField = new TextFieldWithBrowseButton();
    private final JPasswordField loginKeyPassphraseField = new JPasswordField(28);

    private final JTextField apiKeyIdField = new JTextField(28);
    private final JPasswordField apiKeySecretField = new JPasswordField(28);

    private final JTextArea noteArea = new JTextArea(10, 36);

    private VaultAccount result;

    public AccountEditDialog(@Nullable Project project, @Nullable VaultAccount existing) {
        this(project, existing, existing == null ? CredentialType.LOGIN : CredentialType.fromAuth(existing.auth()));
    }

    public AccountEditDialog(
        @Nullable Project project,
        @Nullable VaultAccount existing,
        @NotNull CredentialType initialType
    ) {
        super(project, true);
        this.existing = existing;
        setTitle(existing == null ? "Add Credential" : "Edit Credential");
        setOKButtonText(existing == null ? "Add" : "Save");
        wireKeyPathChooser();
        typeCombo.setSelectedItem(initialType);
        init();
        populateFromExisting();
        updateCard();
        updateLoginKeyEnablement();
    }

    public static @Nullable VaultAccount show(@Nullable Project project, @Nullable VaultAccount existing) {
        return show(project, existing, existing == null ? CredentialType.LOGIN : CredentialType.fromAuth(existing.auth()));
    }

    public static @Nullable VaultAccount show(
        @Nullable Project project,
        @Nullable VaultAccount existing,
        @NotNull CredentialType initialType
    ) {
        AccountEditDialog dlg = new AccountEditDialog(project, existing, initialType);
        return dlg.showAndGet() ? dlg.result : null;
    }

    private void wireKeyPathChooser() {
        loginKeyPathField.addBrowseFolderListener(new TextBrowseFolderListener(
            FileChooserDescriptorFactory.createSingleFileDescriptor()));
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(JBUI.Borders.empty(8));
        root.setPreferredSize(new Dimension(560, 430));

        JPanel header = new JPanel(new GridBagLayout());
        GridBagConstraints c = constraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        header.add(new JLabel("Credential Type:"), c);
        c.gridx = 1;
        c.weightx = 1;
        header.add(typeCombo, c);

        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        header.add(new JLabel("Name:"), c);
        c.gridx = 1;
        c.weightx = 1;
        header.add(nameField, c);
        root.add(header, BorderLayout.NORTH);

        cards.add(loginPanel(), LOGIN_CARD);
        cards.add(apiKeyPanel(), API_KEY_CARD);
        cards.add(secureNotePanel(), SECURE_NOTE_CARD);
        root.add(cards, BorderLayout.CENTER);

        typeCombo.addActionListener(e -> updateCard());
        sshKeyLoginCheckBox.addActionListener(e -> updateLoginKeyEnablement());
        return root;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (nameField.getText().trim().isEmpty()) {
            return new ValidationInfo("Name is required", nameField);
        }
        CredentialType type = selectedType();
        if (type == CredentialType.LOGIN) {
            if (loginUsernameField.getText().trim().isEmpty()) {
                return new ValidationInfo("Username is required", loginUsernameField);
            }
            if (sshKeyLoginCheckBox.isSelected()) {
                String path = loginKeyPathField.getText().trim();
                if (path.isEmpty()) return new ValidationInfo("Key path is required", loginKeyPathField.getTextField());
                if (!Files.isRegularFile(Paths.get(path))) {
                    return new ValidationInfo("Key file does not exist", loginKeyPathField.getTextField());
                }
            }
        } else if (type == CredentialType.API_KEY) {
            if (apiKeyIdField.getText().trim().isEmpty()) {
                return new ValidationInfo("Key identifier is required", apiKeyIdField);
            }
            if (apiKeySecretField.getPassword().length == 0) {
                return new ValidationInfo("Secret is required", apiKeySecretField);
            }
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        CredentialType type = selectedType();
        AuthMethod auth;
        String username;
        if (type == CredentialType.LOGIN) {
            username = loginUsernameField.getText().trim();
            char[] password = loginPasswordField.getPassword();
            if (sshKeyLoginCheckBox.isSelected()) {
                char[] passphrase = loginKeyPassphraseField.getPassword();
                String keyPath = loginKeyPathField.getText().trim();
                auth = password.length == 0
                    ? new AuthMethod.Key(keyPath, passphrase.length == 0 ? null : new String(passphrase))
                    : new AuthMethod.KeyAndPassword(
                        keyPath,
                        passphrase.length == 0 ? null : new String(passphrase),
                        new String(password));
            } else {
                auth = new AuthMethod.Password(new String(password));
            }
        } else if (type == CredentialType.API_KEY) {
            username = apiKeyIdField.getText().trim();
            auth = new AuthMethod.ApiToken(new String(apiKeySecretField.getPassword()));
        } else {
            username = "";
            auth = new AuthMethod.SecureNote(noteArea.getText());
        }

        Instant now = Instant.now();
        UUID id = existing != null ? existing.id() : UUID.randomUUID();
        Instant createdAt = existing != null ? existing.createdAt() : now;
        result = new VaultAccount(
            id,
            nameField.getText().trim(),
            username,
            auth,
            createdAt,
            now
        );
        super.doOKAction();
    }

    private @NotNull JPanel loginPanel() {
        JPanel panel = formPanel();
        GridBagConstraints c = constraints();
        addRow(panel, c, 0, "Username:", loginUsernameField);
        addRow(panel, c, 1, "Password:", loginPasswordField);
        c.gridy = 2;
        c.gridx = 1;
        c.weightx = 1;
        panel.add(sshKeyLoginCheckBox, c);
        addRow(panel, c, 3, "Key path:", loginKeyPathField);
        addRow(panel, c, 4, "Passphrase:", loginKeyPassphraseField);
        c.gridy = 5;
        c.gridx = 1;
        c.weighty = 1;
        panel.add(Box.createVerticalGlue(), c);
        return panel;
    }

    private @NotNull JPanel apiKeyPanel() {
        JPanel panel = formPanel();
        apiKeyIdField.putClientProperty("JTextField.placeholderText", "user@realm!tokenid, service key id, etc.");
        addRow(panel, constraints(), 0, "Key ID:", apiKeyIdField);
        addRow(panel, constraints(), 1, "Secret:", apiKeySecretField);
        return panel;
    }

    private @NotNull JPanel secureNotePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel("Note:"), BorderLayout.NORTH);
        noteArea.setLineWrap(true);
        noteArea.setWrapStyleWord(true);
        panel.add(new JScrollPane(noteArea), BorderLayout.CENTER);
        return panel;
    }

    private static @NotNull JPanel formPanel() {
        return new JPanel(new GridBagLayout());
    }

    private static @NotNull GridBagConstraints constraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private static void addRow(
        @NotNull JPanel panel,
        @NotNull GridBagConstraints c,
        int row,
        @NotNull String label,
        @NotNull JComponent field
    ) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);
    }

    private void populateFromExisting() {
        if (existing == null) return;
        nameField.setText(existing.displayName());
        switch (existing.auth()) {
            case AuthMethod.Password p -> {
                loginUsernameField.setText(existing.username());
                loginPasswordField.setText(p.password());
            }
            case AuthMethod.Key k -> {
                loginUsernameField.setText(existing.username());
                sshKeyLoginCheckBox.setSelected(true);
                loginKeyPathField.setText(k.keyPath());
                if (k.passphrase() != null) loginKeyPassphraseField.setText(k.passphrase());
            }
            case AuthMethod.KeyAndPassword kp -> {
                loginUsernameField.setText(existing.username());
                loginPasswordField.setText(kp.password());
                sshKeyLoginCheckBox.setSelected(true);
                loginKeyPathField.setText(kp.keyPath());
                if (kp.passphrase() != null) loginKeyPassphraseField.setText(kp.passphrase());
            }
            case AuthMethod.ApiToken token -> {
                apiKeyIdField.setText(existing.username());
                apiKeySecretField.setText(token.token());
            }
            case AuthMethod.SecureNote note -> noteArea.setText(note.note());
        }
    }

    private @NotNull CredentialType selectedType() {
        Object selected = typeCombo.getSelectedItem();
        return selected instanceof CredentialType type ? type : CredentialType.LOGIN;
    }

    private void updateCard() {
        CardLayout layout = (CardLayout) cards.getLayout();
        switch (selectedType()) {
            case LOGIN -> layout.show(cards, LOGIN_CARD);
            case API_KEY -> layout.show(cards, API_KEY_CARD);
            case SECURE_NOTE -> layout.show(cards, SECURE_NOTE_CARD);
        }
    }

    private void updateLoginKeyEnablement() {
        boolean enabled = sshKeyLoginCheckBox.isSelected();
        loginKeyPathField.setEnabled(enabled);
        loginKeyPassphraseField.setEnabled(enabled);
    }
}
