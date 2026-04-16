package com.termlab.sftp.toolwindow;

import com.termlab.core.filepicker.ui.FileNameCellRenderer;
import com.termlab.core.filepicker.ui.FileTableModel;
import com.termlab.core.filepicker.ui.ModifiedCellRenderer;
import com.termlab.core.filepicker.ui.SizeCellRenderer;
import com.termlab.sftp.client.SshSftpSession;
import com.termlab.sftp.model.RemoteFileEntry;
import com.termlab.sftp.ops.RemoteFileOps;
import com.termlab.sftp.persistence.TermLabSftpConfig;
import com.termlab.sftp.session.SftpSessionManager;
import com.termlab.sftp.spi.RemoteFileOpener;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.SshHost;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.table.JBTable;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import org.apache.sshd.sftp.client.SftpClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.event.ListSelectionListener;
import javax.swing.table.TableRowSorter;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Remote-filesystem side of the dual-pane SFTP browser. Holds an
 * optional {@link SshSftpSession} for the active connection and
 * drives all SFTP IO through the application executor.
 *
 * <p>Phase 1 is read-only: the pane browses the remote host's
 * filesystem but doesn't transfer, delete, rename, or chmod anything.
 */
public final class RemoteFilePane extends JPanel {

    private final Project project;
    private final FileTableModel model = new FileTableModel();
    private final JBTable table = new JBTable(model);
    private final JComboBox<SshHost> hostPicker = new JComboBox<>();
    private final JTextField pathField = new JTextField();
    private final JLabel statusLabel = new JLabel("Not connected");
    private final JButton connectButton = new JButton("Connect");
    private final JButton disconnectButton = new JButton("Disconnect");

    private @Nullable SshSftpSession activeSession;
    private @Nullable SshHost currentHost;
    private @Nullable String currentRemotePath;
    private final CopyOnWriteArrayList<Runnable> connectionStateListeners = new CopyOnWriteArrayList<>();

    public RemoteFilePane(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        add(buildNorth(), BorderLayout.NORTH);

        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(FileTableModel.COL_NAME)
            .setCellRenderer(new FileNameCellRenderer());
        table.getColumnModel().getColumn(FileTableModel.COL_SIZE)
            .setCellRenderer(new SizeCellRenderer());
        table.getColumnModel().getColumn(FileTableModel.COL_MODIFIED)
            .setCellRenderer(new ModifiedCellRenderer());
        TableRowSorter<FileTableModel> sorter = new TableRowSorter<>(model);
        sorter.setSortsOnUpdates(true);
        table.setRowSorter(sorter);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    onRowActivated(table.getSelectedRow());
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        statusLabel.setBorder(JBUI.Borders.empty(4, 6));
        south.add(statusLabel, BorderLayout.WEST);
        add(south, BorderLayout.SOUTH);

        refreshHostPicker();
        updateButtons();
    }

    private @NotNull JComponent buildNorth() {
        JPanel north = new JPanel(new BorderLayout());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        top.add(new JLabel("Host:"));
        hostPicker.setRenderer(new HostPickerRenderer());
        top.add(hostPicker);
        connectButton.addActionListener(e -> {
            SshHost selected = (SshHost) hostPicker.getSelectedItem();
            if (selected != null) connect(selected);
        });
        disconnectButton.addActionListener(e -> disconnect());
        top.add(connectButton);
        top.add(disconnectButton);
        north.add(top, BorderLayout.NORTH);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(buildToolbar().getComponent(), BorderLayout.WEST);
        pathField.setEditable(true);
        pathField.setBorder(JBUI.Borders.empty(2, 6));
        pathField.setToolTipText("Type an absolute remote path and press Enter to navigate");
        pathField.addActionListener(e -> onPathFieldSubmit());
        bottom.add(pathField, BorderLayout.CENTER);
        north.add(bottom, BorderLayout.CENTER);

        return north;
    }

    private void onPathFieldSubmit() {
        if (activeSession == null) return;
        String text = pathField.getText() == null ? "" : pathField.getText().trim();
        if (text.isEmpty()) {
            if (currentRemotePath != null) pathField.setText(currentRemotePath);
            return;
        }
        if (!text.startsWith("/")) {
            Messages.showErrorDialog(RemoteFilePane.this,
                "Remote paths must be absolute (start with /).",
                "SFTP Directory Error");
            return;
        }
        navigateRemote(text);
    }

    private @NotNull ActionToolbar buildToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new AnAction("Up", "Go to parent directory", AllIcons.Actions.MoveUp) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (currentRemotePath == null) return;
                String parent = parentOf(currentRemotePath);
                if (parent != null) navigateRemote(parent);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        group.add(new AnAction("Refresh", "Refresh directory", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (currentRemotePath != null) navigateRemote(currentRemotePath);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        ActionToolbar toolbar = ActionManager.getInstance()
            .createActionToolbar("TermLabSftpRemote", group, true);
        toolbar.setTargetComponent(this);
        return toolbar;
    }

    private void refreshHostPicker() {
        hostPicker.removeAllItems();
        HostStore store = ApplicationManager.getApplication().getService(HostStore.class);
        if (store == null) return;
        SshHost lastHost = null;
        String lastId = TermLabSftpConfig.getInstance().getLastRemoteHostId();
        for (SshHost host : store.getHosts()) {
            hostPicker.addItem(host);
            if (lastId != null && host.id().toString().equals(lastId)) {
                lastHost = host;
            }
        }
        if (lastHost != null) {
            hostPicker.setSelectedItem(lastHost);
        }
    }

    private void onRowActivated(int viewRow) {
        if (viewRow < 0 || activeSession == null || currentRemotePath == null) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        var entry = model.getEntryAt(modelRow);
        if (!(entry instanceof RemoteFileEntry remote)) return;
        if (remote.isDirectory()) {
            navigateRemote(joinPath(currentRemotePath, remote.name()));
            return;
        }
        SshHost host = currentHost;
        SshSftpSession session = activeSession;
        if (host == null) return;
        String absolute = joinPath(currentRemotePath, remote.name());
        var openers = RemoteFileOpener.EP_NAME.getExtensionList();
        if (openers.isEmpty()) return;
        openers.get(0).open(project, host, session, absolute, remote);
    }

    private void connect(@NotNull SshHost host) {
        statusLabel.setText("Resolving credentials for " + host.label() + "...");
        setUiEnabled(false);

        ProgressManager.getInstance().run(new Task.Modal(
            project,
            "Opening SFTP to " + host.label() + "...",
            /* canBeCancelled = */ true
        ) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    SshSftpSession session = SftpSessionManager.getInstance().acquire(host, RemoteFilePane.this);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        activeSession = session;
                        currentHost = host;
                        TermLabSftpConfig.getInstance().setLastRemoteHostId(host.id().toString());
                        statusLabel.setText("Connected to " + host.host() + ":" + host.port());
                        setUiEnabled(true);
                        updateButtons();
                        fireConnectionStateChanged();
                        navigateRemote(initialRemotePath(session));
                    });
                } catch (SshConnectException e) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showErrorDialog(RemoteFilePane.this,
                            "SFTP connection failed:\n" + e.getMessage(),
                            "SFTP Error");
                        statusLabel.setText("Not connected");
                        setUiEnabled(true);
                        updateButtons();
                    });
                }
            }
        });
    }

    private @NotNull String initialRemotePath(@NotNull SshSftpSession session) {
        try {
            String canonical = session.client().canonicalPath(".");
            if (canonical != null && !canonical.isBlank()) return canonical;
        } catch (IOException ignored) {
        }
        return "/";
    }

    private void navigateRemote(@NotNull String path) {
        SshSftpSession session = activeSession;
        if (session == null) return;
        AppExecutorUtil.getAppExecutorService().submit(() -> {
            List<RemoteFileEntry> entries = new ArrayList<>();
            IOException error = null;
            try {
                for (SftpClient.DirEntry dirEntry : session.client().readDir(path)) {
                    String name = dirEntry.getFilename();
                    if (".".equals(name) || "..".equals(name)) continue;
                    entries.add(RemoteFileEntry.of(dirEntry));
                }
            } catch (IOException e) {
                error = e;
            }
            List<RemoteFileEntry> snapshot = entries;
            IOException finalError = error;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (finalError != null) {
                    Messages.showErrorDialog(RemoteFilePane.this,
                        "Could not list directory:\n" + path + "\n\n" + finalError.getMessage(),
                        "SFTP Directory Error");
                    return;
                }
                currentRemotePath = path;
                pathField.setText(path);
                model.setEntries(snapshot);
                fireConnectionStateChanged();
            });
        });
    }

    private void disconnect() {
        SshSftpSession session = activeSession;
        if (session == null || currentHost == null) return;
        UUID hostId = currentHost.id();
        SftpSessionManager.getInstance().release(hostId, this);
        activeSession = null;
        currentHost = null;
        currentRemotePath = null;
        pathField.setText("");
        model.setEntries(List.of());
        statusLabel.setText("Disconnected");
        updateButtons();
        fireConnectionStateChanged();
    }

    /**
     * Triggered externally (e.g. Search Everywhere) to select a
     * specific host and kick off the connect flow.
     */
    public void connectTo(@NotNull UUID hostId) {
        for (int i = 0; i < hostPicker.getItemCount(); i++) {
            SshHost host = hostPicker.getItemAt(i);
            if (host.id().equals(hostId)) {
                hostPicker.setSelectedIndex(i);
                connect(host);
                return;
            }
        }
    }

    private void setUiEnabled(boolean enabled) {
        hostPicker.setEnabled(enabled);
        connectButton.setEnabled(enabled);
        disconnectButton.setEnabled(enabled);
    }

    private void updateButtons() {
        boolean connected = activeSession != null;
        connectButton.setEnabled(!connected && hostPicker.getItemCount() > 0);
        disconnectButton.setEnabled(connected);
        hostPicker.setEnabled(!connected);
    }

    private static @Nullable String parentOf(@NotNull String path) {
        if ("/".equals(path) || path.isEmpty()) return null;
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        if (slash <= 0) return "/";
        return trimmed.substring(0, slash);
    }

    private static @NotNull String joinPath(@NotNull String base, @NotNull String child) {
        if (base.endsWith("/")) return base + child;
        return base + "/" + child;
    }

    @SuppressWarnings("unused")
    public @NotNull Project getProject() {
        return project;
    }

    /** Currently-active SFTP session, or {@code null} when disconnected. */
    public @Nullable SshSftpSession activeSession() {
        return activeSession;
    }

    /** Remote directory currently displayed, or {@code null} when disconnected. */
    public @Nullable String currentRemotePath() {
        return currentRemotePath;
    }

    /** Currently-connected host, or {@code null} when disconnected. */
    public @org.jetbrains.annotations.Nullable com.termlab.ssh.model.SshHost currentHost() {
        return currentHost;
    }

    /**
     * Absolute remote paths the user has selected, ready to pass
     * back into {@link org.apache.sshd.sftp.client.SftpClient}.
     */
    public @NotNull List<String> selectedRemotePaths() {
        if (currentRemotePath == null) return List.of();
        int[] viewRows = table.getSelectedRows();
        List<String> paths = new ArrayList<>(viewRows.length);
        for (int viewRow : viewRows) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            var entry = model.getEntryAt(modelRow);
            if (entry instanceof RemoteFileEntry remote) {
                paths.add(joinPath(currentRemotePath, remote.name()));
            }
        }
        return paths;
    }

    public void addSelectionListener(@NotNull ListSelectionListener listener) {
        table.getSelectionModel().addListSelectionListener(listener);
    }

    /**
     * Register a callback that fires whenever the pane's connection
     * state (active session or current remote path) changes, so
     * outside UI can flip its enablement.
     */
    public void addConnectionStateListener(@NotNull Runnable listener) {
        connectionStateListeners.add(listener);
    }

    private void fireConnectionStateChanged() {
        for (Runnable listener : connectionStateListeners) {
            listener.run();
        }
    }

    /** Reload the currently displayed remote directory if connected. */
    public void refresh() {
        if (currentRemotePath != null) navigateRemote(currentRemotePath);
    }

    // -- context menu + remote ops ------------------------------------------

    private void maybeShowPopup(@NotNull MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        if (activeSession == null) return;
        int viewRow = table.rowAtPoint(e.getPoint());
        if (viewRow >= 0 && !table.isRowSelected(viewRow)) {
            table.setRowSelectionInterval(viewRow, viewRow);
        }
        buildContextMenu().show(e.getComponent(), e.getX(), e.getY());
    }

    private @NotNull JPopupMenu buildContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        List<String> selection = selectedRemotePaths();

        JMenuItem newFolder = new JMenuItem("New Folder…", AllIcons.Actions.NewFolder);
        newFolder.setEnabled(currentRemotePath != null);
        newFolder.addActionListener(e -> promptNewFolder());
        menu.add(newFolder);

        menu.addSeparator();

        JMenuItem rename = new JMenuItem("Rename…", AllIcons.Actions.Edit);
        rename.setEnabled(selection.size() == 1);
        rename.addActionListener(e -> {
            if (selection.size() == 1) promptRename(selection.get(0));
        });
        menu.add(rename);

        JMenuItem delete = new JMenuItem("Delete", AllIcons.Actions.GC);
        delete.setEnabled(!selection.isEmpty());
        delete.addActionListener(e -> confirmAndDelete(selection));
        menu.add(delete);

        menu.addSeparator();

        JMenuItem copyPath = new JMenuItem("Copy Path", AllIcons.Actions.Copy);
        copyPath.setEnabled(!selection.isEmpty());
        copyPath.addActionListener(e -> copyPathsToClipboard(selection));
        menu.add(copyPath);

        JMenuItem refreshItem = new JMenuItem("Refresh", AllIcons.Actions.Refresh);
        refreshItem.addActionListener(e -> refresh());
        menu.add(refreshItem);

        return menu;
    }

    private void promptNewFolder() {
        if (currentRemotePath == null || activeSession == null) return;
        String name = Messages.showInputDialog(this, "Folder name:", "New Folder", null);
        if (name == null || name.isBlank()) return;
        String parent = currentRemotePath;
        runOps("Create folder", session -> RemoteFileOps.mkdir(session.client(), parent, name.trim()));
    }

    private void promptRename(@NotNull String sourcePath) {
        int slash = sourcePath.lastIndexOf('/');
        String suggested = slash >= 0 && slash < sourcePath.length() - 1
            ? sourcePath.substring(slash + 1)
            : sourcePath;
        String newName = Messages.showInputDialog(this, "New name:", "Rename", null, suggested, null);
        if (newName == null || newName.isBlank() || newName.equals(suggested)) return;
        runOps("Rename", session -> RemoteFileOps.rename(session.client(), sourcePath, newName.trim()));
    }

    private void confirmAndDelete(@NotNull List<String> selection) {
        String message = selection.size() == 1
            ? "Delete " + baseName(selection.get(0)) + "?\nThis cannot be undone."
            : "Delete " + selection.size() + " items?\nThis cannot be undone.";
        int confirm = Messages.showYesNoDialog(this, message, "Delete", AllIcons.General.WarningDialog);
        if (confirm != Messages.YES) return;
        runOps("Delete", session -> {
            for (String path : selection) {
                RemoteFileOps.delete(session.client(), path);
            }
            return null;
        });
    }

    private void copyPathsToClipboard(@NotNull List<String> selection) {
        String joined = selection.stream().collect(Collectors.joining(System.lineSeparator()));
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(joined), null);
    }

    private static @NotNull String baseName(@NotNull String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1) return path;
        return path.substring(slash + 1);
    }

    /**
     * Run {@code op} against the current session on the application
     * executor, refresh on success, show an error dialog on failure.
     * Captures the session reference up front so a concurrent
     * disconnect doesn't null it out mid-op.
     */
    private void runOps(@NotNull String title, @NotNull RemoteOp op) {
        SshSftpSession session = activeSession;
        if (session == null) return;
        AppExecutorUtil.getAppExecutorService().submit(() -> {
            Throwable failure = null;
            try {
                op.run(session);
            } catch (Throwable t) {
                failure = t;
            }
            Throwable finalFailure = failure;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (finalFailure != null) {
                    Messages.showErrorDialog(RemoteFilePane.this,
                        title + " failed:\n" + finalFailure.getMessage(),
                        "SFTP File Operation");
                }
                refresh();
            });
        });
    }

    @FunctionalInterface
    private interface RemoteOp {
        Object run(@NotNull SshSftpSession session) throws Exception;
    }

    private static final class HostPickerRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
            JList<?> list, Object value, int index,
            boolean isSelected, boolean cellHasFocus
        ) {
            Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof SshHost host) {
                setText(host.label() + "  (" + host.username() + "@" + host.host() + ":" + host.port() + ")");
            } else if (value == null) {
                setText("<no hosts>");
            }
            return c;
        }
    }
}
