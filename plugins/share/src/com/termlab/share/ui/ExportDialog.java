package com.termlab.share.ui;

import com.termlab.share.codec.ShareBundleCodec;
import com.termlab.share.planner.ConversionWarning;
import com.termlab.share.planner.ExportPlan;
import com.termlab.share.planner.ExportPlanner;
import com.termlab.share.planner.ExportRequest;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.SshHost;
import com.termlab.tunnels.model.SshTunnel;
import com.termlab.tunnels.model.TunnelStore;
import com.termlab.vault.lock.LockManager;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ExportDialog extends DialogWrapper {

    public enum EntryPoint { HOSTS, TUNNELS }

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final @Nullable Project project;
    private final EntryPoint entryPoint;

    private final List<SshHost> allHosts;
    private final List<SshTunnel> allTunnels;

    private final JBList<SshHost> hostList = new JBList<>();
    private final JBList<SshTunnel> tunnelList = new JBList<>();
    private final JCheckBox includeCredentialsBox = new JCheckBox("Include saved credentials");
    private final JPasswordField passwordField = new JPasswordField(24);
    private final JPasswordField confirmField = new JPasswordField(24);

    public ExportDialog(@Nullable Project project, @NotNull EntryPoint entryPoint) {
        super(project, true);
        this.project = project;
        this.entryPoint = entryPoint;

        HostStore hostStore = ApplicationManager.getApplication().getService(HostStore.class);
        TunnelStore tunnelStore = ApplicationManager.getApplication().getService(TunnelStore.class);
        this.allHosts = hostStore != null ? hostStore.getHosts() : List.of();
        this.allTunnels = tunnelStore != null ? tunnelStore.getTunnels() : List.of();

        setTitle("Export TermLab Bundle");
        setOKButtonText("Export...");
        init();
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        DefaultListModel<SshHost> hostModel = new DefaultListModel<>();
        for (SshHost host : allHosts) {
            hostModel.addElement(host);
        }
        hostList.setModel(hostModel);
        hostList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        hostList.setCellRenderer((list, value, index, isSelected, cellHasFocus) ->
            new JLabel(value.label() + "  (" + value.host() + ":" + value.port() + ")"));

        DefaultListModel<SshTunnel> tunnelModel = new DefaultListModel<>();
        for (SshTunnel tunnel : allTunnels) {
            tunnelModel.addElement(tunnel);
        }
        tunnelList.setModel(tunnelModel);
        tunnelList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tunnelList.setCellRenderer((list, value, index, isSelected, cellHasFocus) ->
            new JLabel(value.label()));

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(10));
        panel.setPreferredSize(new Dimension(700, 520));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.weighty = 1;

        c.gridx = 0;
        c.gridy = 0;
        panel.add(wrap("SSH Hosts", new JScrollPane(hostList)), c);

        c.gridx = 1;
        panel.add(wrap("Tunnels", new JScrollPane(tunnelList)), c);

        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 2;
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(includeCredentialsBox, c);

        c.gridy++;
        panel.add(new JLabel("Bundle password:"), c);

        c.gridy++;
        panel.add(passwordField, c);

        c.gridy++;
        panel.add(new JLabel("Confirm password:"), c);

        c.gridy++;
        panel.add(confirmField, c);

        if (entryPoint == EntryPoint.HOSTS && hostModel.size() > 0) {
            hostList.setSelectionInterval(0, hostModel.size() - 1);
        } else if (entryPoint == EntryPoint.TUNNELS && tunnelModel.size() > 0) {
            tunnelList.setSelectionInterval(0, tunnelModel.size() - 1);
        }

        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        char[] password = passwordField.getPassword();
        char[] confirm = confirmField.getPassword();
        try {
            if (password.length < MIN_PASSWORD_LENGTH) {
                return new ValidationInfo("Password must be at least " + MIN_PASSWORD_LENGTH + " characters", passwordField);
            }
            if (!java.util.Arrays.equals(password, confirm)) {
                return new ValidationInfo("Passwords do not match", confirmField);
            }
            if (hostList.getSelectedIndices().length == 0 && tunnelList.getSelectedIndices().length == 0) {
                return new ValidationInfo("Select at least one host or tunnel");
            }
            return null;
        } finally {
            PasswordUtil.zero(password);
            PasswordUtil.zero(confirm);
        }
    }

    @Override
    protected void doOKAction() {
        char[] pwChars = passwordField.getPassword();
        byte[] password = PasswordUtil.toUtf8(pwChars);
        PasswordUtil.zero(pwChars);

        try {
            boolean includeCredentials = includeCredentialsBox.isSelected();
            LockManager lockManager = ApplicationManager.getApplication().getService(LockManager.class);
            com.termlab.vault.model.Vault vault = null;
            if (includeCredentials) {
                if (lockManager == null || lockManager.getVault() == null) {
                    Messages.showErrorDialog(getContentPanel(),
                        "Unlock the vault before exporting with credentials.",
                        "Vault Locked");
                    return;
                }
                vault = lockManager.getVault();
            }

            ExportRequest req = new ExportRequest(
                selectedHostIds(),
                selectedTunnelIds(),
                includeCredentials,
                allHosts,
                allTunnels,
                vault,
                Path.of(System.getProperty("user.home"), ".ssh", "config"),
                safeHostname(),
                ApplicationInfo.getInstance().getFullVersion()
            );
            ExportPlan plan = ExportPlanner.plan(req);

            if (!plan.warnings().isEmpty()) {
                StringBuilder body = new StringBuilder("The following conversions/warnings were detected:\n\n");
                for (ConversionWarning warning : plan.warnings()) {
                    body.append("- ").append(warning.subject()).append(": ").append(warning.message()).append('\n');
                }
                int choice = Messages.showOkCancelDialog(
                    getContentPanel(),
                    body.toString(),
                    "Export Preview",
                    "Export",
                    "Cancel",
                    null
                );
                if (choice != Messages.OK) {
                    return;
                }
            }

            FileSaverDescriptor descriptor = new FileSaverDescriptor(
                "Save TermLab Bundle",
                "Choose where to save the encrypted bundle",
                "termlabshare"
            );
            String defaultName = "termlab-share-" + LocalDate.now() + ".termlabshare";
            VirtualFileWrapper wrapper = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save((VirtualFile) null, defaultName);
            if (wrapper == null) {
                return;
            }

            byte[] encoded = ShareBundleCodec.encode(plan.bundle(), password);
            Files.write(wrapper.getFile().toPath(), encoded);
            super.doOKAction();
        } catch (Exception e) {
            Messages.showErrorDialog(getContentPanel(), "Export failed: " + e.getMessage(), "Export Failed");
        } finally {
            PasswordUtil.zero(password);
        }
    }

    private JPanel wrap(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel(title), BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private Set<UUID> selectedHostIds() {
        Set<UUID> ids = new LinkedHashSet<>();
        for (SshHost host : hostList.getSelectedValuesList()) {
            ids.add(host.id());
        }
        return ids;
    }

    private Set<UUID> selectedTunnelIds() {
        Set<UUID> ids = new LinkedHashSet<>();
        for (SshTunnel tunnel : tunnelList.getSelectedValuesList()) {
            ids.add(tunnel.id());
        }
        return ids;
    }

    private static String safeHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "unknown";
        }
    }
}
