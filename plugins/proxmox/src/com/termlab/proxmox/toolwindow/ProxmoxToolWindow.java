package com.termlab.proxmox.toolwindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.CollectionComboBoxModel;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.termlab.proxmox.api.JavaPveHttpTransport;
import com.termlab.proxmox.api.PveApiException;
import com.termlab.proxmox.api.PveCertificateException;
import com.termlab.proxmox.api.PveCertificateMismatchException;
import com.termlab.proxmox.api.PveClient;
import com.termlab.proxmox.api.PveHttpTransport;
import com.termlab.proxmox.api.PveUnknownCertificateException;
import com.termlab.proxmox.credentials.PveApiToken;
import com.termlab.proxmox.credentials.PveCredentialResolver;
import com.termlab.proxmox.model.PveAction;
import com.termlab.proxmox.model.PveCluster;
import com.termlab.proxmox.model.PveClusterStore;
import com.termlab.proxmox.model.PveGuest;
import com.termlab.proxmox.model.PveGuestDetails;
import com.termlab.proxmox.model.PveTask;
import com.termlab.proxmox.ui.PveClusterDialog;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public final class ProxmoxToolWindow extends JPanel {
    private static final Logger LOG = Logger.getInstance(ProxmoxToolWindow.class);

    private final Project project;
    private final ToolWindow toolWindow;
    private final PveClusterStore clusterStore;
    private final PveCredentialResolver credentialResolver;
    private final PveHttpTransport transport;
    private final ProxmoxViewModel viewModel = new ProxmoxViewModel();
    private final JComboBox<PveCluster> clusterCombo = new JComboBox<>();
    private final JComboBox<AutoRefreshMode> autoRefreshCombo = new JComboBox<>(AutoRefreshMode.values());
    private final JTextField filterField = new JTextField(22);
    private final JBLabel statusLabel = new JBLabel("");
    private final JBLabel summaryLabel = new JBLabel("No cluster selected.");
    private final JTextArea detailsArea = new JTextArea(6, 36);
    private final PveGuestTableModel tableModel = new PveGuestTableModel();
    private final JBTable table = new JBTable(tableModel);
    private final TableRowSorter<PveGuestTableModel> sorter = new TableRowSorter<>(tableModel);
    private final Timer autoRefreshTimer;
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean loading;
    private volatile boolean actionRunning;
    private volatile Future<?> currentJob;
    private ActionToolbar toolbar;
    private String lastTaskText = "";

    public ProxmoxToolWindow(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        this(project, toolWindow,
            ApplicationManager.getApplication().getService(PveClusterStore.class),
            new PveCredentialResolver(),
            new JavaPveHttpTransport());
    }

    ProxmoxToolWindow(
        @NotNull Project project,
        @NotNull ToolWindow toolWindow,
        @NotNull PveClusterStore clusterStore,
        @NotNull PveCredentialResolver credentialResolver,
        @NotNull PveHttpTransport transport
    ) {
        super(new BorderLayout(0, 8));
        this.project = project;
        this.toolWindow = toolWindow;
        this.clusterStore = clusterStore;
        this.credentialResolver = credentialResolver;
        this.transport = transport;

        setBorder(JBUI.Borders.empty(8));
        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        refreshClusters();
        clusterStore.addChangeListener(this::refreshClustersOnEdt);
        clusterCombo.addActionListener(e -> {
            PveCluster selected = selectedCluster();
            LOG.info("TermLab Proxmox: selected cluster changed to "
                + (selected == null ? "<none>" : "'" + selected.label() + "' at " + selected.endpoint()));
            generation.incrementAndGet();
            tableModel.setGuests(List.of());
            detailsArea.setText("Select a VM or container for details.");
            updateSummary(List.of(), false);
            refreshToolbar();
        });

        autoRefreshTimer = new Timer(5000, e -> {
            if (toolWindow.isVisible() && selectedCluster() != null) refreshGuests(false);
        });
        autoRefreshCombo.addActionListener(e -> updateAutoRefreshTimer());
    }

    @Override
    public void removeNotify() {
        clusterStore.removeChangeListener(this::refreshClustersOnEdt);
        autoRefreshTimer.stop();
        Future<?> job = currentJob;
        if (job != null) job.cancel(true);
        super.removeNotify();
    }

    private @NotNull JPanel buildTopPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        toolbar = buildToolbar();
        controls.add(toolbar.getComponent());
        controls.add(new JBLabel("Cluster:"));
        clusterCombo.setPrototypeDisplayValue(new PveCluster(java.util.UUID.randomUUID(), "Production Cluster", "https://proxmox:8006", java.util.UUID.randomUUID(), null));
        controls.add(clusterCombo);
        controls.add(autoRefreshCombo);
        controls.add(new JBLabel("Filter:"));
        filterField.putClientProperty("JTextField.placeholderText", "Name, VMID, node, status");
        controls.add(filterField);
        panel.add(controls, BorderLayout.WEST);

        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { updateFilter(); }
            @Override
            public void removeUpdate(DocumentEvent e) { updateFilter(); }
            @Override
            public void changedUpdate(DocumentEvent e) { updateFilter(); }
        });
        return panel;
    }

    private @NotNull JPanel buildCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        table.setAutoResizeMode(javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN);
        sorter.setMaxSortKeys(1);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.ASCENDING)));
        table.setRowSorter(sorter);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadSelectedDetails();
        });
        TableColumnModel columns = table.getColumnModel();
        columns.getColumn(0).setPreferredWidth(64);
        columns.getColumn(1).setPreferredWidth(220);
        columns.getColumn(2).setPreferredWidth(72);
        columns.getColumn(3).setPreferredWidth(140);
        columns.getColumn(4).setPreferredWidth(90);
        columns.getColumn(5).setPreferredWidth(70);
        columns.getColumn(6).setPreferredWidth(160);
        columns.getColumn(7).setPreferredWidth(160);
        columns.getColumn(8).setPreferredWidth(110);

        JPanel tablePanel = new JPanel(new BorderLayout(0, 6));
        tablePanel.add(summaryLabel, BorderLayout.NORTH);
        tablePanel.add(new JBScrollPane(table), BorderLayout.CENTER);
        panel.add(tablePanel, BorderLayout.CENTER);

        detailsArea.setEditable(false);
        detailsArea.setLineWrap(false);
        detailsArea.setText("Select a VM or container for details.");
        JBScrollPane detailsScroll = new JBScrollPane(detailsArea);
        detailsScroll.setPreferredSize(new Dimension(JBUI.scale(360), 0));
        panel.add(detailsScroll, BorderLayout.EAST);
        return panel;
    }

    private void refreshClustersOnEdt() {
        ApplicationManager.getApplication().invokeLater(this::refreshClusters);
    }

    private void refreshClusters() {
        PveCluster previous = selectedCluster();
        List<PveCluster> clusters = clusterStore.getClusters();
        LOG.info("TermLab Proxmox: loaded " + clusters.size() + " configured clusters into selector");
        clusterCombo.setModel(new CollectionComboBoxModel<>(clusters));
        if (previous != null) {
            for (PveCluster cluster : clusters) {
                if (cluster.id().equals(previous.id())) {
                    clusterCombo.setSelectedItem(cluster);
                    return;
                }
            }
        }
        if (!clusters.isEmpty()) clusterCombo.setSelectedIndex(0);
        refreshToolbar();
    }

    private PveCluster selectedCluster() {
        Object item = clusterCombo.getSelectedItem();
        return item instanceof PveCluster cluster ? cluster : null;
    }

    private PveGuest selectedGuest() {
        int row = table.getSelectedRow();
        if (row < 0) return null;
        return tableModel.guestAt(table.convertRowIndexToModel(row));
    }

    private void refreshGuests(boolean userInitiated) {
        PveCluster cluster = selectedCluster();
        if (cluster == null) {
            LOG.info("TermLab Proxmox: refresh requested with no selected cluster");
            return;
        }
        if (loading) {
            LOG.info("TermLab Proxmox: refresh skipped for '" + cluster.label() + "' because another refresh is running");
            return;
        }
        LOG.info("TermLab Proxmox: refresh requested for cluster '" + cluster.label()
            + "' endpoint=" + cluster.endpoint()
            + " credentialId=" + cluster.credentialId()
            + " userInitiated=" + userInitiated);
        PveApiToken token = resolveToken(cluster);
        if (token == null) {
            LOG.warn("TermLab Proxmox: no usable API token for cluster '" + cluster.label() + "'");
            setStatus("Unlock the vault and select an API token credential for this cluster.");
            return;
        }

        long jobGeneration = generation.incrementAndGet();
        String selectedKey = selectedGuest() == null ? null : selectedGuest().key();
        LOG.info("TermLab Proxmox: starting refresh generation=" + jobGeneration
            + " selectedGuest=" + selectedKey);
        loading = true;
        refreshToolbar();
        if (userInitiated) setStatus("Refreshing " + cluster.label() + "...");

        currentJob = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try (token) {
                List<PveGuest> guests = newClient(cluster, token).listGuests();
                ApplicationManager.getApplication().invokeLater(() -> {
                    loading = false;
                    if (jobGeneration != generation.get()) {
                        LOG.info("TermLab Proxmox: discarding stale refresh generation=" + jobGeneration
                            + " currentGeneration=" + generation.get());
                        return;
                    }
                    LOG.info("TermLab Proxmox: applying " + guests.size()
                        + " guests from refresh generation=" + jobGeneration);
                    applyGuests(guests, selectedKey);
                    setStatus(lastTaskText);
                    refreshToolbar();
                });
            } catch (PveUnknownCertificateException e) {
                LOG.warn("TermLab Proxmox: unknown certificate for cluster '" + cluster.label()
                    + "' fingerprint=" + e.fingerprint());
                ApplicationManager.getApplication().invokeLater(() -> handleUnknownCertificate(cluster, e.fingerprint()));
            } catch (PveCertificateMismatchException e) {
                LOG.warn("TermLab Proxmox: certificate mismatch for cluster '" + cluster.label()
                    + "' fingerprint=" + e.fingerprint());
                ApplicationManager.getApplication().invokeLater(() -> {
                    loading = false;
                    updateSummary(tableModel.guests(), true);
                    setStatus("Certificate mismatch for " + cluster.label() + ". Presented SHA-256: " + e.fingerprint());
                    refreshToolbar();
                });
            } catch (Exception e) {
                LOG.warn("TermLab Proxmox: refresh failed for cluster '" + cluster.label() + "'", e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    loading = false;
                    updateSummary(tableModel.guests(), true);
                    setStatus("Refresh failed; showing stale data. " + e.getMessage());
                    refreshToolbar();
                });
            }
        });
    }

    private PveApiToken resolveToken(@NotNull PveCluster cluster) {
        LOG.info("TermLab Proxmox: resolving API token for cluster '" + cluster.label()
            + "' credentialId=" + cluster.credentialId());
        PveApiToken token = credentialResolver.resolve(cluster.credentialId());
        if (token != null) return token;
        LOG.info("TermLab Proxmox: initial credential resolve failed; requesting provider availability");
        credentialResolver.ensureAnyProviderAvailable();
        return credentialResolver.resolve(cluster.credentialId());
    }

    private @NotNull PveClient newClient(@NotNull PveCluster cluster, @NotNull PveApiToken token) {
        return new PveClient(cluster, token, transport);
    }

    private void applyGuests(@NotNull List<PveGuest> guests, String selectedKey) {
        LOG.info("TermLab Proxmox: table update with guests=" + guests.size()
            + " selectedKey=" + selectedKey
            + " filter='" + filterField.getText().trim() + "'");
        tableModel.setGuests(guests);
        updateFilter();
        restoreSelection(selectedKey);
        updateSummary(guests, false);
    }

    private void restoreSelection(String key) {
        if (key == null) return;
        for (int modelRow = 0; modelRow < tableModel.getRowCount(); modelRow++) {
            if (!Objects.equals(key, tableModel.guestAt(modelRow).key())) continue;
            int viewRow = table.convertRowIndexToView(modelRow);
            if (viewRow >= 0) table.getSelectionModel().setSelectionInterval(viewRow, viewRow);
            return;
        }
    }

    private void updateSummary(@NotNull List<PveGuest> guests, boolean stale) {
        if (guests.isEmpty()) {
            summaryLabel.setText(selectedCluster() == null ? "No Proxmox cluster configured." : "No VM/LXC data loaded.");
            return;
        }
        long running = guests.stream().filter(PveGuest::isRunning).count();
        long stopped = guests.stream().filter(PveGuest::isStopped).count();
        double cpu = guests.stream().mapToDouble(PveGuest::cpuPercent).sum();
        long mem = guests.stream().mapToLong(PveGuest::memoryBytes).sum();
        long maxMem = guests.stream().mapToLong(PveGuest::maxMemoryBytes).sum();
        summaryLabel.setText((stale ? "Stale: " : "")
            + guests.size() + " guests    Running: " + running
            + "    Stopped: " + stopped
            + "    CPU: " + String.format("%.1f%%", cpu)
            + "    Memory: " + PveGuestTableModel.formatBytes(mem) + " / " + PveGuestTableModel.formatBytes(maxMem));
    }

    private void updateFilter() {
        String text = filterField.getText().trim();
        sorter.setRowFilter(text.isEmpty() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text)));
        LOG.info("TermLab Proxmox: table filter updated text='" + text
            + "' modelRows=" + tableModel.getRowCount()
            + " visibleRows=" + table.getRowCount());
    }

    private void loadSelectedDetails() {
        PveGuest guest = selectedGuest();
        refreshToolbar();
        if (guest == null) {
            detailsArea.setText("Select a VM or container for details.");
            return;
        }
        LOG.info("TermLab Proxmox: selected guest " + guest.key() + "; loading details");
        detailsArea.setText(viewModel.guestSummary(guest) + "\nLoading config...");
        PveCluster cluster = selectedCluster();
        PveApiToken token = cluster == null ? null : resolveToken(cluster);
        if (cluster == null || token == null) {
            LOG.warn("TermLab Proxmox: cannot load details because cluster/token is unavailable");
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try (token) {
                PveGuestDetails details = newClient(cluster, token).details(guest);
                ApplicationManager.getApplication().invokeLater(() -> showDetails(details));
            } catch (Exception e) {
                LOG.warn("TermLab Proxmox: failed to load details for " + guest.key(), e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    detailsArea.setText(guestDetailsText(guest) + "\n\nConfig unavailable: " + e.getMessage());
                });
            }
        });
    }

    private void showDetails(@NotNull PveGuestDetails details) {
        StringBuilder text = new StringBuilder(guestDetailsText(details.guest()));
        if (!lastTaskText.isBlank()) text.append("\nLast action: ").append(lastTaskText);
        text.append("\n\nConfig\n");
        details.config().entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey())
            .forEach(entry -> text.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n'));
        detailsArea.setText(text.toString());
        detailsArea.setCaretPosition(0);
    }

    private static @NotNull String guestDetailsText(@NotNull PveGuest guest) {
        return guest.type().displayName() + " " + guest.vmid() + "  " + guest.name()
            + "\nNode: " + guest.node()
            + "\nStatus: " + guest.status().displayName()
            + "\nCPU: " + String.format("%.1f%%", guest.cpuPercent()) + " of " + guest.maxCpu() + " cores"
            + "\nMemory: " + PveGuestTableModel.formatBytes(guest.memoryBytes()) + " / " + PveGuestTableModel.formatBytes(guest.maxMemoryBytes())
            + "\nDisk: " + PveGuestTableModel.formatBytes(guest.diskBytes()) + " / " + PveGuestTableModel.formatBytes(guest.maxDiskBytes())
            + "\nUptime: " + PveGuestTableModel.formatDuration(guest.uptimeSeconds());
    }

    private void runAction(@NotNull PveAction action) {
        PveCluster cluster = selectedCluster();
        PveGuest guest = selectedGuest();
        if (cluster == null || guest == null || !viewModel.canRun(action, guest) || actionRunning) return;
        if (action != PveAction.START && !confirmAction(action, guest)) return;
        PveApiToken token = resolveToken(cluster);
        if (token == null) {
            setStatus("Unlock the vault and select an API token credential for this cluster.");
            return;
        }

        actionRunning = true;
        refreshToolbar();
        setStatus(action.displayName() + " requested for " + guest.name() + "...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try (token) {
                PveClient client = newClient(cluster, token);
                String upid = client.action(guest, action);
                lastTaskText = action.displayName() + " task pending: " + upid;
                PveTask task = pollTask(client, guest.node(), upid);
                lastTaskText = action.displayName() + " " + (task.successful() ? "completed" : "finished: " + task.exitStatus());
                ApplicationManager.getApplication().invokeLater(() -> {
                    actionRunning = false;
                    setStatus(lastTaskText);
                    refreshGuests(false);
                    refreshToolbar();
                });
            } catch (Exception e) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    actionRunning = false;
                    setStatus(action.displayName() + " failed: " + e.getMessage());
                    refreshToolbar();
                });
            }
        });
    }

    private @NotNull PveTask pollTask(@NotNull PveClient client, @NotNull String node, @NotNull String upid)
        throws IOException, InterruptedException, PveApiException, PveCertificateException {
        PveTask last = new PveTask(upid, node, "running", null);
        for (int i = 0; i < 30; i++) {
            Thread.sleep(1000);
            last = client.taskStatus(node, upid);
            if (last.complete()) return last;
        }
        return last;
    }

    private boolean confirmAction(@NotNull PveAction action, @NotNull PveGuest guest) {
        int choice = Messages.showYesNoDialog(
            this,
            action.displayName() + " " + guest.name() + " (" + guest.type().displayName() + " " + guest.vmid() + ")?",
            action.displayName() + " Guest",
            Messages.getWarningIcon()
        );
        return choice == Messages.YES;
    }

    private void handleUnknownCertificate(@NotNull PveCluster cluster, @NotNull String fingerprint) {
        loading = false;
        LOG.info("TermLab Proxmox: prompting to trust certificate for cluster '" + cluster.label()
            + "' fingerprint=" + fingerprint);
        int choice = Messages.showYesNoDialog(
            this,
            "Trust the certificate for " + cluster.label() + "?\n\nSHA-256: " + fingerprint,
            "Trust Proxmox Certificate",
            AllIcons.General.WarningDialog
        );
        if (choice != Messages.YES) {
            LOG.info("TermLab Proxmox: user declined certificate trust for cluster '" + cluster.label() + "'");
            setStatus("Certificate was not trusted for " + cluster.label() + ".");
            refreshToolbar();
            return;
        }
        try {
            clusterStore.updateCluster(cluster.withTrustedCertificateSha256(fingerprint));
            clusterStore.save();
            LOG.info("TermLab Proxmox: saved trusted certificate fingerprint for cluster '" + cluster.label() + "'");
            setStatus("Trusted certificate for " + cluster.label() + ".");
            refreshGuests(true);
        } catch (IOException e) {
            setStatus("Could not save trusted certificate: " + e.getMessage());
        }
        refreshToolbar();
    }

    private void addCluster() {
        credentialResolver.ensureAnyProviderAvailable();
        PveCluster cluster = PveClusterDialog.show(project, null);
        if (cluster == null) return;
        try {
            clusterStore.addCluster(cluster);
            clusterStore.save();
            clusterCombo.setSelectedItem(cluster);
            setStatus("Added " + cluster.label() + ".");
        } catch (IOException e) {
            setStatus("Could not save cluster: " + e.getMessage());
        }
    }

    private void editCluster() {
        PveCluster selected = selectedCluster();
        if (selected == null) return;
        credentialResolver.ensureAnyProviderAvailable();
        PveCluster updated = PveClusterDialog.show(project, selected);
        if (updated == null) return;
        try {
            clusterStore.updateCluster(updated);
            clusterStore.save();
            clusterCombo.setSelectedItem(updated);
            setStatus("Saved " + updated.label() + ".");
        } catch (IOException e) {
            setStatus("Could not save cluster: " + e.getMessage());
        }
    }

    private void removeCluster() {
        PveCluster selected = selectedCluster();
        if (selected == null) return;
        int choice = Messages.showYesNoDialog(
            this,
            "Remove " + selected.label() + " from TermLab?",
            "Remove Proxmox Cluster",
            Messages.getQuestionIcon()
        );
        if (choice != Messages.YES) return;
        try {
            clusterStore.removeCluster(selected.id());
            clusterStore.save();
            setStatus("Removed " + selected.label() + ".");
        } catch (IOException e) {
            setStatus("Could not save cluster list: " + e.getMessage());
        }
    }

    private void updateAutoRefreshTimer() {
        AutoRefreshMode mode = (AutoRefreshMode) autoRefreshCombo.getSelectedItem();
        if (mode == null || mode.millis() <= 0) {
            autoRefreshTimer.stop();
            return;
        }
        autoRefreshTimer.setDelay(mode.millis());
        autoRefreshTimer.setInitialDelay(mode.millis());
        if (!autoRefreshTimer.isRunning()) autoRefreshTimer.start();
    }

    private void setStatus(@NotNull String status) {
        statusLabel.setText(status);
    }

    private void refreshToolbar() {
        if (toolbar != null) toolbar.updateActionsImmediately();
    }

    private @NotNull ActionToolbar buildToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new AddClusterAction());
        group.add(new EditClusterAction());
        group.add(new RemoveClusterAction());
        group.addSeparator();
        group.add(new RefreshAction());
        group.addSeparator();
        group.add(new GuestAction(PveAction.START, AllIcons.Actions.Execute));
        group.add(new GuestAction(PveAction.SHUTDOWN, AllIcons.Actions.Suspend));
        group.add(new GuestAction(PveAction.STOP, AllIcons.Actions.Cancel));
        group.add(new GuestAction(PveAction.REBOOT, AllIcons.Actions.Refresh));
        ActionToolbar created = ActionManager.getInstance().createActionToolbar("ProxmoxToolbar", group, true);
        created.setTargetComponent(this);
        return created;
    }

    private final class AddClusterAction extends AnAction {
        private AddClusterAction() { super("Add Cluster", "Add a Proxmox cluster", AllIcons.General.Add); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { addCluster(); }
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    }

    private final class EditClusterAction extends AnAction {
        private EditClusterAction() { super("Edit Cluster", "Edit the selected Proxmox cluster", AllIcons.Actions.Edit); }
        @Override public void update(@NotNull AnActionEvent e) { e.getPresentation().setEnabled(selectedCluster() != null); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { editCluster(); }
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    }

    private final class RemoveClusterAction extends AnAction {
        private RemoveClusterAction() { super("Remove Cluster", "Remove the selected Proxmox cluster", AllIcons.General.Remove); }
        @Override public void update(@NotNull AnActionEvent e) { e.getPresentation().setEnabled(selectedCluster() != null); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { removeCluster(); }
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    }

    private final class RefreshAction extends AnAction {
        private RefreshAction() { super("Refresh", "Refresh Proxmox inventory", AllIcons.Actions.Refresh); }
        @Override public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(selectedCluster() != null && !loading);
        }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { refreshGuests(true); }
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    }

    private final class GuestAction extends AnAction {
        private final PveAction action;

        private GuestAction(@NotNull PveAction action, javax.swing.Icon icon) {
            super(action.displayName(), action.displayName() + " selected guest", icon);
            this.action = action;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(!actionRunning && !loading && viewModel.canRun(action, selectedGuest()));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            runAction(action);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }
}
