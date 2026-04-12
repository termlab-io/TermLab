package com.conch.tunnels.ui;

import com.conch.ssh.model.HostStore;
import com.conch.ssh.model.SshHost;
import com.conch.tunnels.client.SshConfigParser;
import com.conch.tunnels.model.InternalHost;
import com.conch.tunnels.model.SshConfigHost;
import com.conch.tunnels.model.SshTunnel;
import com.conch.tunnels.model.TunnelHost;
import com.conch.tunnels.model.TunnelType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Modal add/edit dialog for a single {@link SshTunnel}. Mirrors
 * {@code HostEditDialog}'s layout and validation pattern.
 *
 * <p>Hosts in the combo are sourced from two places:
 * <ul>
 *   <li>{@link HostStore#getHosts()} — saved SSH hosts (shown as
 *       {@code label (host:port)}).</li>
 *   <li>{@link SshConfigParser#parseHostAliases()} — {@code ~/.ssh/config}
 *       aliases (shown as {@code alias (.ssh/config)}).</li>
 * </ul>
 */
public final class TunnelEditDialog extends DialogWrapper {

    private final @Nullable SshTunnel existing;

    private final JTextField labelField = new JTextField(24);

    private final JRadioButton localRadio  = new JRadioButton("Local (-L)", true);
    private final JRadioButton remoteRadio = new JRadioButton("Remote (-R)");

    private final JComboBox<HostEntry> hostCombo = new JComboBox<>();

    private final JTextField bindAddressField = new JTextField(SshTunnel.DEFAULT_BIND_ADDRESS, 16);
    private final JSpinner   bindPortSpinner  = new JSpinner(
        new SpinnerNumberModel(8080, 1, 65535, 1));

    private final JTextField targetHostField = new JTextField(24);
    private final JSpinner   targetPortSpinner = new JSpinner(
        new SpinnerNumberModel(80, 1, 65535, 1));

    private SshTunnel result;

    public TunnelEditDialog(@Nullable Project project, @Nullable SshTunnel existing) {
        super(project, true);
        this.existing = existing;
        setTitle(existing == null ? "Add SSH Tunnel" : "Edit SSH Tunnel");
        setOKButtonText(existing == null ? "Add" : "Save");
        populateHostCombo();
        init();
        populateFromExisting();
    }

    public static @Nullable SshTunnel show(@Nullable Project project, @Nullable SshTunnel existing) {
        TunnelEditDialog dlg = new TunnelEditDialog(project, existing);
        return dlg.showAndGet() ? dlg.result : null;
    }

    // -- population -----------------------------------------------------------

    private void populateHostCombo() {
        hostCombo.removeAllItems();
        hostCombo.addItem(HostEntry.NONE);

        if (ApplicationManager.getApplication() == null) return;

        HostStore store = ApplicationManager.getApplication().getService(HostStore.class);
        if (store != null) {
            List<HostEntry> internalEntries = new ArrayList<>();
            for (SshHost host : store.getHosts()) {
                internalEntries.add(HostEntry.fromSshHost(host));
            }
            internalEntries.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
            for (HostEntry e : internalEntries) hostCombo.addItem(e);
        }

        List<String> aliases = SshConfigParser.parseHostAliases();
        for (String alias : aliases) {
            hostCombo.addItem(HostEntry.fromSshConfig(alias));
        }
    }

    private void populateFromExisting() {
        if (existing == null) {
            localRadio.setSelected(true);
            hostCombo.setSelectedItem(HostEntry.NONE);
            return;
        }

        labelField.setText(existing.label());

        if (existing.type() == TunnelType.REMOTE) {
            remoteRadio.setSelected(true);
        } else {
            localRadio.setSelected(true);
        }

        bindAddressField.setText(existing.bindAddress());
        bindPortSpinner.setValue(existing.bindPort());
        targetHostField.setText(existing.targetHost());
        targetPortSpinner.setValue(existing.targetPort());

        // Select the matching host entry in the combo
        TunnelHost savedHost = existing.host();
        for (int i = 0; i < hostCombo.getItemCount(); i++) {
            HostEntry entry = hostCombo.getItemAt(i);
            if (entry != null && entry.matches(savedHost)) {
                hostCombo.setSelectedIndex(i);
                return;
            }
        }
        // Not found — add a synthetic entry
        if (savedHost instanceof InternalHost ih) {
            HostEntry missing = HostEntry.missing(ih);
            hostCombo.addItem(missing);
            hostCombo.setSelectedItem(missing);
        } else if (savedHost instanceof SshConfigHost sh) {
            HostEntry entry = HostEntry.fromSshConfig(sh.alias());
            hostCombo.addItem(entry);
            hostCombo.setSelectedItem(entry);
        }
    }

    // -- panel ----------------------------------------------------------------

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(500, 340));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        // Label
        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        panel.add(new JLabel("Label:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(labelField, c);

        // Type radio group
        ButtonGroup typeGroup = new ButtonGroup();
        typeGroup.add(localRadio);
        typeGroup.add(remoteRadio);

        c.gridx = 0; c.gridy++; c.gridwidth = 2; c.weightx = 1;
        panel.add(new JLabel("Type:"), c);
        c.gridy++;
        panel.add(localRadio, c);
        c.gridy++;
        panel.add(remoteRadio, c);
        c.gridwidth = 1;

        // Host
        c.gridx = 0; c.gridy++; c.weightx = 0;
        panel.add(new JLabel("Host:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(hostCombo, c);

        // Bind address
        c.gridx = 0; c.gridy++; c.weightx = 0;
        panel.add(new JLabel("Bind address:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(bindAddressField, c);

        // Bind port
        c.gridx = 0; c.gridy++; c.weightx = 0;
        panel.add(new JLabel("Bind port:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(bindPortSpinner, c);

        // Target host
        c.gridx = 0; c.gridy++; c.weightx = 0;
        panel.add(new JLabel("Target host:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(targetHostField, c);

        // Target port
        c.gridx = 0; c.gridy++; c.weightx = 0;
        panel.add(new JLabel("Target port:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(targetPortSpinner, c);

        return panel;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return labelField;
    }

    // -- validation -----------------------------------------------------------

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (labelField.getText().trim().isEmpty()) {
            return new ValidationInfo("Label is required", labelField);
        }
        HostEntry selectedHost = (HostEntry) hostCombo.getSelectedItem();
        if (selectedHost == null || selectedHost.tunnelHost() == null) {
            return new ValidationInfo("Select a host", hostCombo);
        }
        if (bindAddressField.getText().trim().isEmpty()) {
            return new ValidationInfo("Bind address is required", bindAddressField);
        }
        int bindPort = (Integer) bindPortSpinner.getValue();
        if (bindPort < 1 || bindPort > 65535) {
            return new ValidationInfo("Bind port must be between 1 and 65535", bindPortSpinner);
        }
        if (targetHostField.getText().trim().isEmpty()) {
            return new ValidationInfo("Target host is required", targetHostField);
        }
        int targetPort = (Integer) targetPortSpinner.getValue();
        if (targetPort < 1 || targetPort > 65535) {
            return new ValidationInfo("Target port must be between 1 and 65535", targetPortSpinner);
        }
        return null;
    }

    // -- OK action ------------------------------------------------------------

    @Override
    protected void doOKAction() {
        String label = labelField.getText().trim();
        TunnelType type = remoteRadio.isSelected() ? TunnelType.REMOTE : TunnelType.LOCAL;
        HostEntry hostEntry = (HostEntry) hostCombo.getSelectedItem();
        TunnelHost host = hostEntry == null ? null : hostEntry.tunnelHost();
        String bindAddress = bindAddressField.getText().trim();
        int bindPort = (Integer) bindPortSpinner.getValue();
        String targetHost = targetHostField.getText().trim();
        int targetPort = (Integer) targetPortSpinner.getValue();

        if (host == null) return; // guard; doValidate should have caught this

        if (existing == null) {
            result = SshTunnel.create(label, type, host, bindPort, bindAddress, targetHost, targetPort);
        } else {
            result = existing.withEdited(label, type, host, bindPort, bindAddress, targetHost, targetPort);
        }
        super.doOKAction();
    }

    // -- HostEntry inner class ------------------------------------------------

    /**
     * Wraps a {@link TunnelHost} for display in the host combo box.
     */
    static final class HostEntry {
        static final HostEntry NONE = new HostEntry(null, "<select host>");

        private final @Nullable TunnelHost tunnelHost;
        private final @NotNull String label;

        HostEntry(@Nullable TunnelHost tunnelHost, @NotNull String label) {
            this.tunnelHost = tunnelHost;
            this.label = label;
        }

        static @NotNull HostEntry fromSshHost(@NotNull SshHost host) {
            String label = host.label() + " (" + host.host() + ":" + host.port() + ")";
            return new HostEntry(new InternalHost(host.id()), label);
        }

        static @NotNull HostEntry fromSshConfig(@NotNull String alias) {
            return new HostEntry(new SshConfigHost(alias), alias + " (.ssh/config)");
        }

        static @NotNull HostEntry missing(@NotNull InternalHost host) {
            return new HostEntry(host, "<missing host " + host.hostId() + ">");
        }

        @Nullable TunnelHost tunnelHost() { return tunnelHost; }
        @NotNull String label() { return label; }

        boolean matches(@Nullable TunnelHost other) {
            if (tunnelHost == null || other == null) return false;
            if (tunnelHost instanceof InternalHost ih && other instanceof InternalHost oih) {
                return ih.hostId().equals(oih.hostId());
            }
            if (tunnelHost instanceof SshConfigHost sh && other instanceof SshConfigHost osh) {
                return sh.alias().equals(osh.alias());
            }
            return false;
        }

        @Override
        public String toString() { return label; }
    }
}
