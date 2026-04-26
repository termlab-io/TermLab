package com.termlab.sysinfo.toolwindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.ui.CollectionComboBoxModel;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.termlab.ssh.model.HostStore;
import com.termlab.sysinfo.collect.SysInfoCollector;
import com.termlab.sysinfo.model.DiskInfo;
import com.termlab.sysinfo.model.DiskIoInfo;
import com.termlab.sysinfo.model.NetworkInfo;
import com.termlab.sysinfo.model.OsKind;
import com.termlab.sysinfo.model.ProcessInfo;
import com.termlab.sysinfo.model.SystemSnapshot;
import com.termlab.sysinfo.model.SystemTarget;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.RowFilter;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.HierarchyEvent;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public final class SysInfoToolWindow extends JPanel {

    private static final int REFRESH_MS = 2000;
    private static final int FORCE_KILL_COOLDOWN_MS = 3000;

    private final ToolWindow toolWindow;
    private final HostStore hostStore;
    private final SysInfoCollector collector;
    private final SysInfoViewModel viewModel = new SysInfoViewModel();
    private final JComboBox<SystemTarget> targetCombo = new JComboBox<>();
    private final JComboBox<ViewMode> modeCombo = new JComboBox<>(ViewMode.values());
    private final JComboBox<String> networkInterfaceCombo = new JComboBox<>();
    private final JBLabel statusLabel = new JBLabel("");
    private final JBLabel hostStatsLabel = new JBLabel("Host: --");
    private final JBLabel diskStatsLabel = new JBLabel("Disk: --");
    private final JBLabel cpuStatsLabel = new JBLabel("CPU: --");
    private final JBLabel bottomNetworkLabel = new JBLabel("Network: --");
    private final JBLabel memoryStatsLabel = new JBLabel("Physical Memory: --    Memory Used: --");
    private final MemoryGraph memoryGraph = new MemoryGraph();
    private final MemoryGraph cpuGraph = new MemoryGraph(
        new JBColor(new Color(64, 169, 230, 70), new Color(80, 190, 240, 80)),
        new JBColor(new Color(40, 135, 210), new Color(100, 210, 255))
    );
    private final MemoryGraph diskGraph = new MemoryGraph(
        new JBColor(new Color(244, 160, 60, 70), new Color(250, 175, 70, 80)),
        new JBColor(new Color(205, 115, 35), new Color(255, 180, 90))
    );
    private final ProcessTableModel processTableModel = new ProcessTableModel();
    private final JTextField processFilter = new JTextField(24);
    private final JTextArea processDetails = new JTextArea(4, 32);
    private final JButton copyDetailsButton = new JButton(AllIcons.Actions.Copy);
    private final JBTable processTable = new JBTable(processTableModel);
    private final TableRowSorter<ProcessTableModel> processSorter = new TableRowSorter<>(processTableModel);
    private final Timer refreshTimer;
    private final AtomicLong generation = new AtomicLong();
    private volatile Future<?> currentJob;
    private volatile boolean collecting;
    private volatile boolean collectionRunning;
    private volatile boolean signalingProcess;
    private ActionToolbar topToolbar;
    private Long pendingTermPid;
    private boolean forceKillArmed;
    private Timer forceKillCooldownTimer;
    private String selectedNetworkInterface = "Auto";

    public SysInfoToolWindow(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        super(new BorderLayout(0, 8));
        this.toolWindow = toolWindow;
        this.hostStore = ApplicationManager.getApplication().getService(HostStore.class);
        this.collector = new SysInfoCollector(hostStore);

        setBorder(JBUI.Borders.empty(8));
        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        installVisibilityResumeHooks(project);

        refreshTargets();
        hostStore.addChangeListener(this::refreshTargetsOnEdt);
        Disposer.register(toolWindow.getDisposable(), this::disposePanel);

        targetCombo.addActionListener(e -> {
            SystemTarget selected = selectedTarget();
            if (selected == null) return;
            generation.incrementAndGet();
            collector.reset(selected);
            viewModel.clearHistory();
            memoryGraph.setValues(viewModel.memoryHistory());
            cpuGraph.setValues(viewModel.cpuHistory());
            diskGraph.setValues(viewModel.diskIoHistory());
            clearStatus();
            if (collectionRunning) collectNow();
        });

        refreshTimer = new Timer(REFRESH_MS, e -> {
            if (collectionRunning && shouldCollectWhileVisible()) collectNow();
        });
    }

    private void disposePanel() {
        refreshTimer.stop();
        hostStore.removeChangeListener(this::refreshTargetsOnEdt);
        Future<?> job = currentJob;
        if (job != null) job.cancel(true);
    }

    private void installVisibilityResumeHooks(@NotNull Project project) {
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                resumeCollectionIfVisible();
            }
        });

        project.getMessageBus().connect(toolWindow.getDisposable()).subscribe(ToolWindowManagerListener.TOPIC, new ToolWindowManagerListener() {
            @Override
            public void toolWindowShown(@NotNull ToolWindow shownToolWindow) {
                if (shownToolWindow == toolWindow || SysInfoToolWindowFactory.ID.equals(shownToolWindow.getId())) {
                    resumeCollectionIfVisible();
                }
            }

            @Override
            public void stateChanged(@NotNull ToolWindowManager toolWindowManager,
                                     @NotNull ToolWindow shownToolWindow,
                                     @NotNull ToolWindowManagerListener.ToolWindowManagerEventType changeType) {
                if ((shownToolWindow == toolWindow || SysInfoToolWindowFactory.ID.equals(shownToolWindow.getId()))
                    && (changeType == ToolWindowManagerListener.ToolWindowManagerEventType.ShowToolWindow
                        || changeType == ToolWindowManagerListener.ToolWindowManagerEventType.ActivateToolWindow)) {
                    resumeCollectionIfVisible();
                }
            }
        });
    }

    private boolean shouldCollectWhileVisible() {
        return toolWindow.isVisible() || toolWindow.isActive() || isShowing();
    }

    private void resumeCollectionIfVisible() {
        if (!collectionRunning || !shouldCollectWhileVisible()) {
            return;
        }
        if (!refreshTimer.isRunning()) {
            refreshTimer.start();
        }
        collectNow();
    }

    private @NotNull JPanel buildTopPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        topToolbar = buildToolbar();
        controls.add(topToolbar.getComponent());
        controls.add(new JBLabel("Mode:"));
        modeCombo.addActionListener(e -> applyModePreset());
        controls.add(modeCombo);
        controls.add(new JBLabel("System:"));
        targetCombo.setPrototypeDisplayValue(SystemTarget.local());
        controls.add(targetCombo);
        controls.add(new JBLabel("Filter:"));
        controls.add(processFilter);
        controls.add(new JBLabel("Interface:"));
        networkInterfaceCombo.addActionListener(e -> {
            Object selected = networkInterfaceCombo.getSelectedItem();
            selectedNetworkInterface = selected == null ? "Auto" : selected.toString();
            SystemSnapshot snapshot = viewModel.snapshot();
            if (snapshot != null) {
                bottomNetworkLabel.setText("Network: " + networkText(snapshot.networks()));
            }
        });
        controls.add(networkInterfaceCombo);
        panel.add(controls, BorderLayout.WEST);
        return panel;
    }

    private @NotNull JPanel buildCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(buildProcessTablePanel(), BorderLayout.CENTER);
        panel.add(buildBottomStatsPanel(), BorderLayout.SOUTH);
        return panel;
    }

    private @NotNull JPanel buildProcessTablePanel() {
        processDetails.setEditable(false);
        processDetails.setLineWrap(true);
        processDetails.setWrapStyleWord(true);
        processDetails.setText("Select a process for details.");

        processTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        processSorter.setMaxSortKeys(1);
        processSorter.setSortKeys(List.of(new RowSorter.SortKey(2, SortOrder.DESCENDING)));
        processTable.setRowSorter(processSorter);
        processTable.getSelectionModel().addListSelectionListener(e -> updateProcessDetails());
        TableColumnModel columns = processTable.getColumnModel();
        columns.getColumn(0).setPreferredWidth(70);
        columns.getColumn(1).setPreferredWidth(100);
        columns.getColumn(2).setPreferredWidth(70);
        columns.getColumn(3).setPreferredWidth(70);
        columns.getColumn(4).setPreferredWidth(90);
        columns.getColumn(5).setPreferredWidth(90);
        columns.getColumn(6).setPreferredWidth(520);

        processFilter.putClientProperty("JTextField.placeholderText", "Filter processes");
        processFilter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateProcessFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateProcessFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateProcessFilter();
            }
        });

        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.add(new JBScrollPane(processTable), BorderLayout.CENTER);
        return tablePanel;
    }

    private @NotNull JPanel buildBottomStatsPanel() {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        panel.setBorder(JBUI.Borders.compound(
            JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 1, 0, 0, 0),
            JBUI.Borders.empty(8, 0, 0, 0)
        ));

        JPanel graphsPanel = new JPanel(new GridLayout(1, 3, 8, 0));
        graphsPanel.add(graphPanel("MEMORY PRESSURE", memoryGraph));
        graphsPanel.add(graphPanel("CPU LOAD", cpuGraph));
        graphsPanel.add(graphPanel("DISK I/O", diskGraph));
        panel.add(graphsPanel, BorderLayout.WEST);

        JPanel statsPanel = new JPanel(new GridLayout(5, 1, 0, 3));
        statsPanel.add(hostStatsLabel);
        statsPanel.add(cpuStatsLabel);
        statsPanel.add(memoryStatsLabel);
        statsPanel.add(diskStatsLabel);
        statsPanel.add(bottomNetworkLabel);
        panel.add(statsPanel, BorderLayout.CENTER);

        copyDetailsButton.setToolTipText("Copy process details");
        copyDetailsButton.addActionListener(e -> copyProcessDetails());

        JPanel detailsHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        detailsHeader.add(new JBLabel("Process details"));
        detailsHeader.add(copyDetailsButton);

        JBScrollPane detailsScroll = new JBScrollPane(
            processDetails,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        detailsScroll.setPreferredSize(new Dimension(JBUI.scale(420), JBUI.scale(88)));
        detailsScroll.setMinimumSize(new Dimension(JBUI.scale(220), JBUI.scale(64)));

        JPanel detailsPanel = new JPanel(new BorderLayout(0, 4));
        detailsPanel.add(detailsHeader, BorderLayout.NORTH);
        detailsPanel.add(detailsScroll, BorderLayout.CENTER);
        panel.add(detailsPanel, BorderLayout.EAST);

        panel.setPreferredSize(new Dimension(0, JBUI.scale(128)));
        return panel;
    }

    private void refreshTargetsOnEdt() {
        ApplicationManager.getApplication().invokeLater(this::refreshTargets);
    }

    private void refreshTargets() {
        SystemTarget previous = selectedTarget();
        List<SystemTarget> targets = viewModel.targetsFor(hostStore.getHosts());
        targetCombo.setModel(new CollectionComboBoxModel<>(targets));
        if (previous != null) {
            for (SystemTarget target : targets) {
                if (target.key().equals(previous.key())) {
                    targetCombo.setSelectedItem(target);
                    return;
                }
            }
        }
        if (!targets.isEmpty()) targetCombo.setSelectedIndex(0);
    }

    private SystemTarget selectedTarget() {
        Object item = targetCombo.getSelectedItem();
        return item instanceof SystemTarget target ? target : null;
    }

    private void collectNow() {
        if (!collectionRunning || collecting) return;
        SystemTarget target = selectedTarget();
        if (target == null) return;

        long jobGeneration = generation.get();
        collecting = true;

        currentJob = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                SystemSnapshot snapshot = collector.collect(target);
                ApplicationManager.getApplication().invokeLater(() -> {
                    collecting = false;
                    if (jobGeneration != generation.get()) return;
                    applySnapshot(snapshot);
                });
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    collecting = false;
                    if (jobGeneration != generation.get()) return;
                    viewModel.setStatus("SysInfo unavailable: " + e.getMessage());
                    statusLabel.setText(viewModel.status());
                });
            }
        });
    }

    private void applySnapshot(@NotNull SystemSnapshot snapshot) {
        viewModel.applySnapshot(snapshot);
        clearStatus();
        hostStatsLabel.setText("Host: " + hostText(snapshot));
        cpuStatsLabel.setText("CPU: " + percent(snapshot.cpuUsagePercent())
            + "    Load: " + blank(snapshot.loadAverage())
            + "    Uptime: " + blank(snapshot.uptime()));
        bottomNetworkLabel.setText("Network: " + networkText(snapshot.networks()));
        updateNetworkInterfaces(snapshot.networks());
        memoryStatsLabel.setText("Physical Memory: " + formatKb(snapshot.memoryTotalKb())
            + "    Memory Used: " + formatKb(snapshot.memoryUsedKb())
            + "    Free/Cache: " + formatKb(Math.max(0, snapshot.memoryTotalKb() - snapshot.memoryUsedKb())));
        diskStatsLabel.setText("Disk: " + diskText(snapshot.disks()) + "    I/O: " + diskIoText(snapshot.diskIo()));
        Long selectedPid = selectedPid();
        processTableModel.setProcesses(snapshot.processes());
        updateProcessFilter();
        restoreSelectedPid(selectedPid);
        updatePendingKillState(snapshot.processes());
        memoryGraph.setValues(viewModel.memoryHistory());
        cpuGraph.setValues(viewModel.cpuHistory());
        diskGraph.setValues(viewModel.diskIoHistory());
        if (snapshot.osKind() == OsKind.UNSUPPORTED) {
            viewModel.setStatus("Unsupported system: " + snapshot.uptime());
        }
        statusLabel.setText(viewModel.status());
    }

    private void toggleCollection() {
        if (collectionRunning) {
            stopCollection();
        } else {
            startCollection();
        }
    }

    private void startCollection() {
        collectionRunning = true;
        refreshToolbar();
        refreshTimer.start();
        collectNow();
    }

    private void stopCollection() {
        collectionRunning = false;
        collecting = false;
        generation.incrementAndGet();
        refreshTimer.stop();
        Future<?> job = currentJob;
        if (job != null) job.cancel(true);
        refreshToolbar();
        clearStatus();
    }

    private void clearStatus() {
        viewModel.setStatus("");
        statusLabel.setText("");
    }

    private void updateProcessFilter() {
        String text = processFilter.getText().trim();
        if (text.isEmpty()) {
            processSorter.setRowFilter(null);
        } else {
            processSorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text)));
        }
        updateProcessDetails();
    }

    private void updateProcessDetails() {
        int row = processTable.getSelectedRow();
        if (row < 0) {
            processDetails.setText("Select a process for details.");
            refreshToolbar();
            return;
        }
        int modelRow = processTable.convertRowIndexToModel(row);
        ProcessInfo process = processTableModel.processAt(modelRow);
        processDetails.setText(
            "PID " + process.pid()
                + "  User " + process.user()
                + "  CPU " + String.format("%.1f%%", process.cpuPercent())
                + "  Mem " + String.format("%.1f%%", process.memoryPercent())
                + "  RSS " + formatKb(process.rssKb())
                + "  VSZ " + formatKb(process.vszKb())
                + "\n" + process.command()
        );
        refreshToolbar();
    }

    private Long selectedPid() {
        int row = processTable.getSelectedRow();
        if (row < 0) return null;
        int modelRow = processTable.convertRowIndexToModel(row);
        return processTableModel.processAt(modelRow).pid();
    }

    private void restoreSelectedPid(Long pid) {
        if (pid == null) return;
        for (int modelRow = 0; modelRow < processTableModel.getRowCount(); modelRow++) {
            if (processTableModel.processAt(modelRow).pid() != pid) continue;
            int viewRow = processTable.convertRowIndexToView(modelRow);
            if (viewRow < 0) return;
            processTable.getSelectionModel().setSelectionInterval(viewRow, viewRow);
            processTable.scrollRectToVisible(processTable.getCellRect(viewRow, 0, true));
            updateProcessDetails();
            return;
        }
    }

    private void copyProcessDetails() {
        String text = processDetails.getText();
        if (text == null || text.isBlank() || text.equals("Select a process for details.")) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private void stopSelectedProcess() {
        Long pid = selectedPid();
        SystemTarget target = selectedTarget();
        if (pid == null || target == null) return;

        SysInfoCollector.ProcessSignal signal =
            forceKillArmed && pendingTermPid != null && pendingTermPid.equals(pid)
                ? SysInfoCollector.ProcessSignal.KILL
                : SysInfoCollector.ProcessSignal.TERM;
        if (signal == SysInfoCollector.ProcessSignal.KILL && !confirmForceKill(pid)) {
            return;
        }

        signalingProcess = true;
        refreshToolbar();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                collector.signalProcess(target, pid, signal);
                ApplicationManager.getApplication().invokeLater(() -> {
                    signalingProcess = false;
                    processSignalSent(pid, signal);
                });
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    signalingProcess = false;
                    viewModel.setStatus("Could not signal PID " + pid + ": " + e.getMessage());
                    statusLabel.setText(viewModel.status());
                    refreshToolbar();
                });
            }
        });
    }

    private void processSignalSent(long pid, @NotNull SysInfoCollector.ProcessSignal signal) {
        if (signal == SysInfoCollector.ProcessSignal.KILL) {
            clearPendingKill();
            collectNow();
            return;
        }

        pendingTermPid = pid;
        forceKillArmed = false;
        if (forceKillCooldownTimer != null) {
            forceKillCooldownTimer.stop();
        }
        forceKillCooldownTimer = new Timer(FORCE_KILL_COOLDOWN_MS, e -> {
            forceKillArmed = pendingTermPid != null && pendingTermPid.equals(selectedPid());
            refreshToolbar();
        });
        forceKillCooldownTimer.setRepeats(false);
        forceKillCooldownTimer.start();
        refreshToolbar();
        collectNow();
    }

    private boolean confirmForceKill(long pid) {
        int choice = Messages.showYesNoDialog(
            this,
            "Force kill PID " + pid + " with SIGKILL?",
            "Force Kill Process",
            Messages.getWarningIcon()
        );
        return choice == Messages.YES;
    }

    private void updatePendingKillState(@NotNull List<ProcessInfo> processes) {
        if (pendingTermPid == null) return;
        boolean stillRunning = processes.stream().anyMatch(process -> process.pid() == pendingTermPid);
        if (!stillRunning) {
            clearPendingKill();
        } else {
            refreshToolbar();
        }
    }

    private void clearPendingKill() {
        pendingTermPid = null;
        forceKillArmed = false;
        if (forceKillCooldownTimer != null) {
            forceKillCooldownTimer.stop();
            forceKillCooldownTimer = null;
        }
        refreshToolbar();
    }

    private void refreshToolbar() {
        if (topToolbar != null) {
            topToolbar.updateActionsImmediately();
        }
    }

    private @NotNull ActionToolbar buildToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new ToggleCollectionAction());
        group.add(new StopProcessAction());

        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("SysInfoToolbar", group, true);
        toolbar.setTargetComponent(this);
        return toolbar;
    }

    private void applyModePreset() {
        ViewMode mode = (ViewMode) modeCombo.getSelectedItem();
        if (mode == null) return;
        int column = switch (mode) {
            case CPU -> 2;
            case MEMORY -> 3;
            case DISK -> 4;
            case NETWORK -> 6;
        };
        processSorter.setSortKeys(List.of(new RowSorter.SortKey(column, SortOrder.DESCENDING)));
    }

    private final class ToggleCollectionAction extends AnAction {
        private ToggleCollectionAction() {
            super("Start Collection", "Start or stop system information collection", AllIcons.Actions.Execute);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setText(collectionRunning ? "Stop Collection" : "Start Collection");
            e.getPresentation().setDescription(collectionRunning ? "Stop collection" : "Start collection");
            e.getPresentation().setIcon(collectionRunning ? AllIcons.Actions.Suspend : AllIcons.Actions.Execute);
            e.getPresentation().setEnabled(true);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            toggleCollection();
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private final class StopProcessAction extends AnAction {
        private StopProcessAction() {
            super("Stop Process", "Send SIGTERM to the selected process", AllIcons.Actions.Cancel);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            Long pid = selectedPid();
            boolean selectedPending = pid != null && pendingTermPid != null && pendingTermPid.equals(pid);
            boolean enabled = pid != null && !signalingProcess && (!selectedPending || forceKillArmed);
            e.getPresentation().setText(selectedPending && forceKillArmed ? "Force Kill Process" : "Stop Process");
            e.getPresentation().setDescription(pid == null
                ? "Select a process to stop"
                : selectedPending && forceKillArmed
                    ? "Send SIGKILL to the selected process"
                    : selectedPending
                        ? "Waiting before force kill"
                        : "Send SIGTERM to the selected process");
            e.getPresentation().setIcon(AllIcons.Actions.Cancel);
            e.getPresentation().setEnabled(enabled);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            stopSelectedProcess();
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private static @NotNull JPanel graphPanel(@NotNull String title, @NotNull MemoryGraph graph) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        JBLabel label = new JBLabel(title);
        label.setHorizontalAlignment(JBLabel.CENTER);
        graph.setPreferredSize(new Dimension(JBUI.scale(180), JBUI.scale(48)));
        graph.setMinimumSize(new Dimension(JBUI.scale(140), JBUI.scale(40)));
        panel.add(label, BorderLayout.NORTH);
        panel.add(graph, BorderLayout.CENTER);
        return panel;
    }

    private static @NotNull String percent(Double value) {
        return value == null ? "--" : String.format("%.1f%%", value);
    }

    private static @NotNull String hostText(@NotNull SystemSnapshot snapshot) {
        String os = switch (snapshot.osKind()) {
            case LINUX -> "Linux";
            case MACOS -> "macOS";
            case UNSUPPORTED -> "Unsupported";
        };
        String kernel = snapshot.kernel().isBlank() ? "" : " " + snapshot.kernel();
        String arch = snapshot.architecture().isBlank() ? "" : " / " + snapshot.architecture();
        return snapshot.hostname() + " (" + os + kernel + arch + ", " + snapshot.sampleMillis() + " ms)";
    }

    private static @NotNull String blank(@NotNull String value) {
        return value.isBlank() ? "--" : value;
    }

    private static @NotNull String diskText(@NotNull List<DiskInfo> disks) {
        if (disks.isEmpty()) return "--";
        DiskInfo disk = disks.get(0);
        return formatKb(disk.usedKb()) + " / " + formatKb(disk.totalKb()) + " (" + disk.usedPercent() + "%)";
    }

    private static @NotNull String diskIoText(@NotNull List<DiskIoInfo> disks) {
        if (disks.isEmpty()) return "--";
        double read = disks.stream().mapToDouble(DiskIoInfo::readBytesPerSecond).sum();
        double write = disks.stream().mapToDouble(DiskIoInfo::writeBytesPerSecond).sum();
        if (read <= 0.0 && write <= 0.0) return "--";
        return "R " + formatBytesPerSecond(read) + " / W " + formatBytesPerSecond(write);
    }

    private void updateNetworkInterfaces(@NotNull List<NetworkInfo> networks) {
        String previous = selectedNetworkInterface;
        networkInterfaceCombo.removeAllItems();
        networkInterfaceCombo.addItem("Auto");
        for (NetworkInfo network : networks) {
            networkInterfaceCombo.addItem(network.name());
        }
        boolean found = "Auto".equals(previous);
        for (NetworkInfo network : networks) {
            if (network.name().equals(previous)) {
                found = true;
                break;
            }
        }
        selectedNetworkInterface = found ? previous : "Auto";
        networkInterfaceCombo.setSelectedItem(selectedNetworkInterface);
    }

    private @NotNull String networkText(@NotNull List<NetworkInfo> networks) {
        if (networks.isEmpty()) return "--";
        NetworkInfo busiest = "Auto".equals(selectedNetworkInterface)
            ? networks.stream()
                .max((a, b) -> Double.compare(a.rxBytesPerSecond() + a.txBytesPerSecond(), b.rxBytesPerSecond() + b.txBytesPerSecond()))
                .orElse(networks.get(0))
            : networks.stream()
                .filter(network -> network.name().equals(selectedNetworkInterface))
                .findFirst()
                .orElse(networks.get(0));
        if (busiest.rxBytesPerSecond() <= 0.0 && busiest.txBytesPerSecond() <= 0.0) {
            return busiest.name() + " --";
        }
        return busiest.name()
            + " RX " + formatBytesPerSecond(busiest.rxBytesPerSecond())
            + " / TX " + formatBytesPerSecond(busiest.txBytesPerSecond());
    }

    private static @NotNull String formatBytesPerSecond(double bytes) {
        if (bytes <= 0.0) return "0 B/s";
        double value = bytes;
        String[] units = {"B/s", "KB/s", "MB/s", "GB/s"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }

    private static @NotNull String formatKb(long kb) {
        if (kb <= 0) return "--";
        double value = kb;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }

    private enum ViewMode {
        CPU,
        MEMORY,
        DISK,
        NETWORK
    }
}
