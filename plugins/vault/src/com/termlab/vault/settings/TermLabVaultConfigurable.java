package com.termlab.vault.settings;

import com.termlab.vault.lock.LockManager;
import com.termlab.vault.persistence.VaultFile;
import com.termlab.vault.persistence.VaultPaths;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Top-level "Credential Vault" entry in the Settings dialog (registered with
 * {@code parentId="root"}). Exposes the vault file location so the user can
 * store it wherever they want — the XDG-style default
 * {@code ~/.config/termlab/vault.enc}, a Dropbox/iCloud folder for sync, or a
 * USB key.
 *
 * <p>Non-path vault tuning (auto-lock minutes, push-to-system-agent, etc.)
 * lives inside each vault's {@link com.termlab.vault.model.VaultSettings}, not
 * in application-wide settings — so this panel only has one field.
 *
 * <p>On apply, if the user changed the path and the vault is currently
 * unlocked, the vault is sealed so nothing accidentally writes to the old
 * location with the new path pointer.
 */
public final class TermLabVaultConfigurable implements SearchableConfigurable {

    public static final String ID = "termlab.vault.settings";

    private JPanel mainPanel;
    private JRadioButton useDefaultRadio;
    private JRadioButton useCustomRadio;
    private TextFieldWithBrowseButton pathField;
    private JLabel effectiveLabel;

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Credential Vault";
    }

    @Override
    public @Nullable JComponent createComponent() {
        mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(JBUI.Borders.empty(12));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;

        // Header.
        JLabel heading = new JLabel("Vault file location");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        c.gridy = 0; c.gridwidth = 2; c.weightx = 1;
        mainPanel.add(heading, c);

        c.gridy++;
        JLabel hint = new JLabel("<html>The encrypted vault file (<code>vault.enc</code>). "
            + "The device-bound secret stays in your OS keychain regardless of where this file lives, "
            + "so a copied vault file is undecryptable without both pieces.</html>");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize() - 1f));
        mainPanel.add(hint, c);

        // Radio buttons.
        useDefaultRadio = new JRadioButton("Use default location");
        useCustomRadio = new JRadioButton("Use custom location:");
        ButtonGroup group = new ButtonGroup();
        group.add(useDefaultRadio);
        group.add(useCustomRadio);

        c.gridy++;
        mainPanel.add(Box.createVerticalStrut(8), c);

        c.gridy++;
        mainPanel.add(useDefaultRadio, c);

        c.gridy++;
        JLabel defaultPathLabel = new JLabel("  " + VaultPaths.defaultVaultFile());
        defaultPathLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        defaultPathLabel.setFont(defaultPathLabel.getFont().deriveFont(
            defaultPathLabel.getFont().getSize() - 1f));
        mainPanel.add(defaultPathLabel, c);

        c.gridy++;
        mainPanel.add(useCustomRadio, c);

        pathField = new TextFieldWithBrowseButton();
        FileChooserDescriptor descriptor =
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Choose Vault File")
                .withDescription("Pick an existing vault.enc or a location for a new one.");
        pathField.addBrowseFolderListener(new TextBrowseFolderListener(descriptor));
        c.gridy++;
        mainPanel.add(pathField, c);

        c.gridy++;
        mainPanel.add(Box.createVerticalStrut(12), c);

        // Effective-path readout.
        c.gridy++;
        JLabel effectiveHeader = new JLabel("Effective path:");
        effectiveHeader.setFont(effectiveHeader.getFont().deriveFont(Font.BOLD));
        mainPanel.add(effectiveHeader, c);

        effectiveLabel = new JLabel();
        c.gridy++;
        mainPanel.add(effectiveLabel, c);

        // Enablement tracking.
        useDefaultRadio.addActionListener(e -> updateEnablement());
        useCustomRadio.addActionListener(e -> updateEnablement());

        // Spacer.
        c.gridy++;
        c.weighty = 1;
        mainPanel.add(Box.createVerticalGlue(), c);

        reset();
        return mainPanel;
    }

    private void updateEnablement() {
        pathField.setEnabled(useCustomRadio.isSelected());
    }

    private void refreshEffectiveLabel() {
        Path path = effectivePathFromForm();
        effectiveLabel.setText(path.toString());
    }

    private Path effectivePathFromForm() {
        if (useCustomRadio.isSelected()) {
            String text = pathField.getText().trim();
            if (!text.isEmpty()) return Paths.get(text);
        }
        return VaultPaths.defaultVaultFile();
    }

    @Override
    public boolean isModified() {
        String configured = TermLabVaultConfig.getInstance().getState().vaultFilePath;
        boolean currentlyCustom = configured != null && !configured.isBlank();

        if (currentlyCustom != useCustomRadio.isSelected()) return true;
        if (currentlyCustom) {
            String formValue = pathField.getText().trim();
            return !formValue.equals(configured);
        }
        return false;
    }

    @Override
    public void apply() throws ConfigurationException {
        String newPath;
        if (useCustomRadio.isSelected()) {
            newPath = pathField.getText().trim();
            if (newPath.isEmpty()) {
                throw new ConfigurationException("Custom path cannot be empty.");
            }
            // Don't require the file to exist — the user may be pointing at a
            // location where they'll create a vault next. But the parent dir
            // should be writable so the first save doesn't fail.
            Path resolved = Paths.get(newPath);
            Path parent = resolved.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                throw new ConfigurationException(
                    "Parent directory does not exist: " + parent);
            }
        } else {
            newPath = null;  // fall back to default
        }

        TermLabVaultConfig.State state = TermLabVaultConfig.getInstance().getState();
        boolean pathChanging = !java.util.Objects.equals(state.vaultFilePath, newPath);
        state.vaultFilePath = newPath;
        TermLabVaultConfig.getInstance().save();

        if (pathChanging) {
            // Seal the vault so the next access picks up the new path cleanly.
            LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
            if (lm != null && !lm.isLocked()) {
                lm.lock();
                Messages.showInfoMessage(mainPanel,
                    "Vault location changed. Your vault has been locked and will use the new path on next unlock.",
                    "Vault Location Updated");
            }
        }

        refreshEffectiveLabel();
    }

    @Override
    public void reset() {
        String configured = TermLabVaultConfig.getInstance().getState().vaultFilePath;
        boolean custom = configured != null && !configured.isBlank();
        useCustomRadio.setSelected(custom);
        useDefaultRadio.setSelected(!custom);
        pathField.setText(custom ? configured : "");
        updateEnablement();
        refreshEffectiveLabel();
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        useDefaultRadio = null;
        useCustomRadio = null;
        pathField = null;
        effectiveLabel = null;
    }

    // Unused; kept to satisfy the SearchableConfigurable contract on older
    // IntelliJ builds that required it.
    @SuppressWarnings("unused")
    private static boolean fileLooksLikeVault(@NotNull Path p) {
        return VaultFile.exists(p);
    }
}
