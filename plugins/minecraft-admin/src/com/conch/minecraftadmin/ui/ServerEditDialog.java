package com.conch.minecraftadmin.ui;

import com.conch.minecraftadmin.model.ServerProfile;
import com.conch.sdk.CredentialProvider;
import com.conch.sdk.CredentialProvider.CredentialDescriptor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Optional;
import java.util.UUID;

/**
 * Modal add/edit dialog for a single {@link ServerProfile}. Produces either a
 * brand-new profile (add mode, {@code existing == null}) or an edited copy of
 * an existing one (edit mode).
 *
 * <p>Two vault-picker buttons let the user associate an AMP password credential
 * and an RCON password credential with the profile. Credentials are stored only
 * as opaque {@link UUID}s — the plaintext password never lives in the dialog.
 */
public final class ServerEditDialog extends DialogWrapper {

    private static final ExtensionPointName<CredentialProvider> EP_NAME =
        ExtensionPointName.create("com.conch.core.credentialProvider");

    private final @Nullable ServerProfile existing;

    // Text fields
    private final JBTextField labelField       = new JBTextField(24);
    private final JBTextField ampUrlField      = new JBTextField(24);
    private final JBTextField ampInstanceField = new JBTextField(24);
    private final JBTextField ampUsernameField = new JBTextField(24);
    private final JBTextField rconHostField    = new JBTextField(24);

    // RCON port spinner
    private final JSpinner rconPortSpinner = new JSpinner(
        new SpinnerNumberModel(ServerProfile.DEFAULT_RCON_PORT, 1, 65535, 1));

    // AMP credential state
    private @Nullable UUID    ampCredentialId   = null;
    private @NotNull  String  ampCredentialName = "<not picked>";

    // RCON credential state
    private @Nullable UUID    rconCredentialId   = null;
    private @NotNull  String  rconCredentialName = "<not picked>";

    // Credential display labels
    private final JBLabel ampCredentialLabel  = new JBLabel("<not picked>");
    private final JBLabel rconCredentialLabel = new JBLabel("<not picked>");

    // Credential pick buttons
    private final JButton ampPickButton   = new JButton("Pick\u2026");
    private final JButton rconPickButton  = new JButton("Pick\u2026");
    private final JButton rconClearButton = new JButton("Clear");

    public ServerEditDialog(@Nullable Project project, @Nullable ServerProfile existing) {
        super(project, true);
        this.existing = existing;
        setTitle(existing == null ? "Add Minecraft Server" : "Edit Minecraft Server");
        setOKButtonText(existing == null ? "Add" : "Save");
        init();
        populateFromExisting();
        wirePickButtons();
    }

    /**
     * Convenience factory: show the dialog and return the result.
     *
     * @return the new or edited {@link ServerProfile}, or {@link Optional#empty()} on cancel
     */
    public Optional<ServerProfile> showAndGetResult() {
        show();
        if (!isOK()) return Optional.empty();
        return Optional.of(buildResult());
    }

    // -------------------------------------------------------------------------
    // Pre-populate (edit mode)
    // -------------------------------------------------------------------------

    private void populateFromExisting() {
        if (existing == null) return;

        labelField.setText(existing.label());
        ampUrlField.setText(existing.ampUrl());
        ampInstanceField.setText(existing.ampInstanceName());
        ampUsernameField.setText(existing.ampUsername());
        rconHostField.setText(existing.rconHost());
        rconPortSpinner.setValue(existing.rconPort());

        // Restore AMP credential — try to look up display name from providers
        ampCredentialId = existing.ampCredentialId();
        ampCredentialName = resolveDisplayName(ampCredentialId);
        ampCredentialLabel.setText(ampCredentialName);

        // Restore RCON credential (may be null for passwordless servers)
        rconCredentialId = existing.rconCredentialId();
        rconCredentialName = rconCredentialId == null
            ? "(none \u2014 passwordless)"
            : resolveDisplayName(rconCredentialId);
        rconCredentialLabel.setText(rconCredentialName);
    }

    /** Walk registered providers to find the display name for a saved credential id. */
    private @NotNull String resolveDisplayName(@Nullable UUID id) {
        if (id == null) return "<not picked>";
        if (ApplicationManager.getApplication() == null) return "<credential " + id + ">";
        for (CredentialProvider provider : EP_NAME.getExtensionList()) {
            if (!provider.isAvailable()) continue;
            for (CredentialDescriptor d : provider.listCredentials()) {
                if (id.equals(d.id())) {
                    return d.displayName() + "  \u00b7  " + d.subtitle();
                }
            }
        }
        return "<missing credential " + id + ">";
    }

    // -------------------------------------------------------------------------
    // Pick-button wiring
    // -------------------------------------------------------------------------

    private void wirePickButtons() {
        ampPickButton.addActionListener(e -> {
            CredentialDescriptor picked = runPicker();
            if (picked != null) {
                ampCredentialId   = picked.id();
                ampCredentialName = picked.displayName() + "  \u00b7  " + picked.subtitle();
                ampCredentialLabel.setText(ampCredentialName);
            }
        });

        rconPickButton.addActionListener(e -> {
            CredentialDescriptor picked = runPicker();
            if (picked != null) {
                rconCredentialId   = picked.id();
                rconCredentialName = picked.displayName() + "  \u00b7  " + picked.subtitle();
                rconCredentialLabel.setText(rconCredentialName);
            }
        });

        rconClearButton.addActionListener(e -> {
            rconCredentialId   = null;
            rconCredentialName = "(none \u2014 passwordless)";
            rconCredentialLabel.setText(rconCredentialName);
        });
    }

    /**
     * Open the vault picker via the first available {@link CredentialProvider}
     * and return the chosen descriptor, or {@code null} if the user cancelled
     * or no provider is available.
     */
    private @Nullable CredentialDescriptor runPicker() {
        if (ApplicationManager.getApplication() == null) return null;
        for (CredentialProvider provider : EP_NAME.getExtensionList()) {
            if (!provider.isAvailable()) {
                provider.ensureAvailable();
            }
            if (!provider.isAvailable()) continue;

            // promptForCredential returns a full Credential — we only need the id.
            // List credentials and show a simple chooser, mirroring the SSH combo approach.
            java.util.List<CredentialDescriptor> descriptors = provider.listCredentials();
            if (descriptors.isEmpty()) continue;

            // Use the platform's built-in list chooser for the picker dialog.
            CredentialDescriptor[] items = descriptors.toArray(new CredentialDescriptor[0]);
            CredentialDescriptor selected = (CredentialDescriptor) JOptionPane.showInputDialog(
                getContentPanel(),
                "Select a credential:",
                "Pick Credential",
                JOptionPane.PLAIN_MESSAGE,
                null,
                items,
                items[0]
            );
            return selected; // null if user cancelled
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Dialog panel layout
    // -------------------------------------------------------------------------

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(520, 340));

        GridBagConstraints c = new GridBagConstraints();
        c.insets  = JBUI.insets(4);
        c.anchor  = GridBagConstraints.WEST;
        c.fill    = GridBagConstraints.HORIZONTAL;

        // Row helper — label in col 0, component in col 1 (stretching)
        int row = 0;

        row = addRow(panel, c, row, "Label:",             labelField);
        row = addRow(panel, c, row, "AMP URL:",           ampUrlField);
        row = addRow(panel, c, row, "AMP Instance Name:", ampInstanceField);
        row = addRow(panel, c, row, "AMP Username:",      ampUsernameField);
        row = addPickerRow(panel, c, row, "AMP Password:", ampCredentialLabel, ampPickButton);
        row = addRow(panel, c, row, "RCON Host:",         rconHostField);
        row = addRow(panel, c, row, "RCON Port:",         rconPortSpinner);
        row = addPickerRow(panel, c, row, "RCON Password:", rconCredentialLabel, rconPickButton, rconClearButton);

        // Filler row so the form doesn't stretch awkwardly
        c.gridy   = row;
        c.gridx   = 0;
        c.gridwidth = 2;
        c.weighty = 1.0;
        panel.add(Box.createVerticalGlue(), c);

        return panel;
    }

    /** Add a label + single component row; returns the next row index. */
    private static int addRow(JPanel panel, GridBagConstraints c,
                              int row, String labelText, JComponent field) {
        c.gridy    = row;
        c.gridx    = 0;
        c.gridwidth = 1;
        c.weightx  = 0;
        c.weighty  = 0;
        panel.add(new JLabel(labelText), c);

        c.gridx    = 1;
        c.weightx  = 1;
        panel.add(field, c);

        return row + 1;
    }

    /** Add a label + (display label + Pick button) row; returns the next row index. */
    private static int addPickerRow(JPanel panel, GridBagConstraints c,
                                    int row, String labelText,
                                    JBLabel displayLabel, JButton pickButton) {
        c.gridy    = row;
        c.gridx    = 0;
        c.gridwidth = 1;
        c.weightx  = 0;
        c.weighty  = 0;
        panel.add(new JLabel(labelText), c);

        JPanel pickerRow = new JPanel(new BorderLayout(6, 0));
        pickerRow.setOpaque(false);
        pickerRow.add(displayLabel, BorderLayout.CENTER);
        pickerRow.add(pickButton,   BorderLayout.EAST);

        c.gridx   = 1;
        c.weightx = 1;
        panel.add(pickerRow, c);

        return row + 1;
    }

    /**
     * Add a label + (display label + Pick button + Clear button) row; returns the next row index.
     * Used for optional credential pickers where the user may want to remove the selection.
     */
    private static int addPickerRow(JPanel panel, GridBagConstraints c,
                                    int row, String labelText,
                                    JBLabel displayLabel, JButton pickButton, JButton clearButton) {
        c.gridy    = row;
        c.gridx    = 0;
        c.gridwidth = 1;
        c.weightx  = 0;
        c.weighty  = 0;
        panel.add(new JLabel(labelText), c);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.setOpaque(false);
        buttons.add(pickButton);
        buttons.add(clearButton);

        JPanel pickerRow = new JPanel(new BorderLayout(6, 0));
        pickerRow.setOpaque(false);
        pickerRow.add(displayLabel, BorderLayout.CENTER);
        pickerRow.add(buttons,      BorderLayout.EAST);

        c.gridx   = 1;
        c.weightx = 1;
        panel.add(pickerRow, c);

        return row + 1;
    }

    // -------------------------------------------------------------------------
    // Focus
    // -------------------------------------------------------------------------

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return labelField;
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (labelField.getText().trim().isEmpty()) {
            return new ValidationInfo("Label is required", labelField);
        }

        String url = ampUrlField.getText().trim();
        if (url.isEmpty()) {
            return new ValidationInfo("AMP URL is required", ampUrlField);
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return new ValidationInfo("AMP URL must start with http:// or https://", ampUrlField);
        }

        if (ampInstanceField.getText().trim().isEmpty()) {
            return new ValidationInfo("AMP Instance Name is required", ampInstanceField);
        }

        if (ampUsernameField.getText().trim().isEmpty()) {
            return new ValidationInfo("AMP Username is required", ampUsernameField);
        }

        if (ampCredentialId == null) {
            return new ValidationInfo("AMP Password credential must be picked", ampPickButton);
        }

        if (rconHostField.getText().trim().isEmpty()) {
            return new ValidationInfo("RCON Host is required", rconHostField);
        }

        int port = (Integer) rconPortSpinner.getValue();
        if (port < 1 || port > 65535) {
            return new ValidationInfo("RCON Port must be between 1 and 65535", rconPortSpinner);
        }

        // rconCredentialId may legitimately be null for passwordless (local / LAN-trusted) servers.

        return null;
    }

    // -------------------------------------------------------------------------
    // OK action — build result
    // -------------------------------------------------------------------------

    @Override
    protected void doOKAction() {
        super.doOKAction();
    }

    private @NotNull ServerProfile buildResult() {
        String label           = labelField.getText().trim();
        String ampUrl          = ampUrlField.getText().trim();
        String ampInstanceName = ampInstanceField.getText().trim();
        String ampUsername     = ampUsernameField.getText().trim();
        String rconHost        = rconHostField.getText().trim();
        int    rconPort        = (Integer) rconPortSpinner.getValue();

        // doValidate() guarantees ampCredentialId is non-null before OK is allowed.
        // rconCredentialId may be null for passwordless (local / LAN-trusted) servers.
        UUID ampCred  = ampCredentialId;
        UUID rconCred = rconCredentialId;

        if (existing == null) {
            return ServerProfile.create(
                label, ampUrl, ampInstanceName, ampUsername,
                ampCred, rconHost, rconPort, rconCred);
        } else {
            return existing.withEdited(
                label, ampUrl, ampInstanceName, ampUsername,
                ampCred, rconHost, rconPort, rconCred);
        }
    }
}
