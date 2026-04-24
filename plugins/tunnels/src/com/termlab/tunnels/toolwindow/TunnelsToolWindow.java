package com.termlab.tunnels.toolwindow;

import com.termlab.ssh.client.TermLabServerKeyVerifier;
import com.termlab.ssh.client.TermLabSshClient;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.client.SshResolvedCredential;
import com.termlab.ssh.credentials.HostCredentialBundle;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.PromptPasswordAuth;
import com.termlab.ssh.model.SshHost;
import com.termlab.tunnels.client.TunnelConnectionManager;
import com.termlab.tunnels.model.InternalHost;
import com.termlab.tunnels.model.SshConfigHost;
import com.termlab.tunnels.model.SshTunnel;
import com.termlab.tunnels.model.TunnelState;
import com.termlab.tunnels.model.TunnelStore;
import com.termlab.tunnels.model.TunnelType;
import com.termlab.tunnels.ui.TunnelEditDialog;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.apache.sshd.client.session.ClientSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The "Tunnels" right-sidebar tool window. A toolbar with
 * Add / Edit / Delete / Refresh + a {@link JBList} of saved SSH tunnels.
 *
 * <p>Interaction model:
 * <ul>
 *   <li><b>Double-click</b> a tunnel → toggle connect/disconnect</li>
 *   <li><b>Right-click</b> a tunnel → context menu: Connect, Disconnect,
 *       Edit, Duplicate, Delete</li>
 *   <li><b>+</b> toolbar button → {@link TunnelEditDialog} in add mode</li>
 *   <li><b>Edit</b> toolbar button → dialog prefilled with the selection</li>
 *   <li><b>Delete</b> toolbar button → confirm + remove</li>
 *   <li><b>⟳</b> toolbar button → reload from disk</li>
 * </ul>
 */
public final class TunnelsToolWindow extends JPanel {

    private static final Logger LOG = Logger.getInstance(TunnelsToolWindow.class);

    private final Project project;
    private final TunnelStore store;
    private final TunnelConnectionManager connectionManager;
    private final Runnable storeListener;
    private final DefaultListModel<SshTunnel> listModel = new DefaultListModel<>();
    private final JBList<SshTunnel> list = new JBList<>(listModel);

    public TunnelsToolWindow(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.store = ApplicationManager.getApplication().getService(TunnelStore.class);
        this.connectionManager =
            ApplicationManager.getApplication().getService(TunnelConnectionManager.class);
        this.storeListener = () -> ApplicationManager.getApplication().invokeLater(this::refreshFromStore);

        list.setCellRenderer(new TunnelCellRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    SshTunnel selected = list.getSelectedValue();
                    if (selected != null) toggleConnectDisconnect(selected);
                }
            }
        });
        list.addListSelectionListener(this::selectionChanged);
        list.addMouseListener(new PopupHandler() {
            @Override
            public void invokePopup(Component comp, int x, int y) {
                int index = list.locationToIndex(new Point(x, y));
                if (index >= 0) list.setSelectedIndex(index);
                showContextMenu(comp, x, y);
            }
        });

        add(buildToolbar().getComponent(), BorderLayout.NORTH);
        add(new JBScrollPane(list), BorderLayout.CENTER);

        refreshFromStore();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (store != null) {
            store.removeChangeListener(storeListener);
            store.addChangeListener(storeListener);
        }
    }

    @Override
    public void removeNotify() {
        if (store != null) {
            store.removeChangeListener(storeListener);
        }
        super.removeNotify();
    }

    // -- data sync ------------------------------------------------------------

    private void refreshFromStore() {
        if (store == null) {
            listModel.clear();
            return;
        }
        SshTunnel previousSelection = list.getSelectedValue();
        listModel.clear();
        for (SshTunnel t : store.getTunnels()) listModel.addElement(t);
        if (previousSelection != null) {
            for (int i = 0; i < listModel.size(); i++) {
                if (listModel.get(i).id().equals(previousSelection.id())) {
                    list.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    private void selectionChanged(@NotNull ListSelectionEvent e) {
        // Reserved for future enable/disable of context-sensitive actions.
    }

    // -- toolbar --------------------------------------------------------------

    private @NotNull ActionToolbar buildToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new AddTunnelAction());
        group.add(new EditTunnelAction());
        group.add(new DeleteTunnelAction());
        group.addSeparator();
        group.add(new RefreshAction());

        ActionToolbar toolbar = ActionManager.getInstance()
            .createActionToolbar("TunnelsToolbar", group, true);
        toolbar.setTargetComponent(this);
        return toolbar;
    }

    // -- context menu ---------------------------------------------------------

    private void showContextMenu(Component comp, int x, int y) {
        SshTunnel selected = list.getSelectedValue();
        if (selected == null) return;

        TunnelState state = connectionManager.getState(selected.id());

        JPopupMenu menu = new JPopupMenu();

        JMenuItem connect = new JMenuItem("Connect");
        connect.setEnabled(state != TunnelState.ACTIVE && state != TunnelState.CONNECTING);
        connect.addActionListener(e -> connectTunnel(selected));
        menu.add(connect);

        JMenuItem disconnect = new JMenuItem("Disconnect");
        disconnect.setEnabled(state == TunnelState.ACTIVE || state == TunnelState.CONNECTING
            || state == TunnelState.ERROR);
        disconnect.addActionListener(e -> disconnectTunnel(selected));
        menu.add(disconnect);

        menu.addSeparator();

        JMenuItem viewDetails = new JMenuItem("View Details…");
        viewDetails.addActionListener(e -> viewDetails(selected));
        menu.add(viewDetails);

        JMenuItem edit = new JMenuItem("Edit…");
        edit.addActionListener(e -> editSelected());
        menu.add(edit);

        JMenuItem duplicate = new JMenuItem("Duplicate");
        duplicate.addActionListener(e -> duplicateSelected());
        menu.add(duplicate);

        menu.addSeparator();

        JMenuItem delete = new JMenuItem("Delete");
        delete.addActionListener(e -> deleteSelected());
        menu.add(delete);

        menu.show(comp, x, y);
    }

    // -- connect / disconnect -------------------------------------------------

    private void toggleConnectDisconnect(@NotNull SshTunnel tunnel) {
        TunnelState state = connectionManager.getState(tunnel.id());
        if (state == TunnelState.ACTIVE) {
            disconnectTunnel(tunnel);
        } else {
            connectTunnel(tunnel);
        }
    }

    /**
     * Connect flow for a tunnel:
     * <ol>
     *   <li>Resolve the {@link SshHost} from {@link InternalHost} or build
     *       a synthetic one for {@link SshConfigHost}.</li>
     *   <li>Resolve credentials by dispatching on the host's auth type.</li>
     *   <li>Run connect in a {@link Task.Modal}.</li>
     *   <li>On success, hand the session to the connection manager.</li>
     *   <li>On {@code AUTH_FAILED}, retry once.</li>
     *   <li>On {@code HOST_KEY_REJECTED}, show MITM dialog.</li>
     * </ol>
     */
    private void connectTunnel(@NotNull SshTunnel tunnel) {
        SshHost host = resolveHost(tunnel);
        if (host == null) return;

        HostCredentialBundle.AuthSource authSource = HostCredentialBundle.authSourceFor(host);
        SshResolvedCredential initialCredential = authSource.fetch(host);
        if (initialCredential == null) return;

        // Resolve bastion credentials upfront for proxy-jump tunnels so
        // MINA can auth the bastion with its own configured credential
        // rather than falling back to ~/.ssh/id_* client-level keys.
        // Mirrors SshSessionProvider.resolveBastionForTarget.
        TermLabSshClient.BastionAuth bastionAuth = null;
        if (host.proxyJump() != null && !host.proxyJump().isBlank()) {
            bastionAuth = HostCredentialBundle.resolveBastionFor(host);
        }

        TermLabSshClient client = getClient();
        int attemptsLeft = 2;
        SshResolvedCredential current = initialCredential;

        try {
            while (attemptsLeft > 0) {
                attemptsLeft--;
                ConnectOutcome outcome = runConnectModal(client, tunnel, host, current, bastionAuth);

                if (outcome.session != null) {
                    current.close();
                    // Hand the authenticated session to the connection manager
                    try {
                        connectionManager.connect(tunnel, outcome.session);
                    } catch (IOException e) {
                        Messages.showErrorDialog(project,
                            "Failed to start port forwarding:\n" + e.getMessage(),
                            "Tunnel Error");
                    }
                    refreshFromStore();
                    return;
                }

                current.close();

                if (outcome.cancelled) return;
                if (outcome.failure == null) return;

                SshConnectException.Kind kind = outcome.failure.kind();

                if (kind == SshConnectException.Kind.AUTH_FAILED && attemptsLeft > 0) {
                    SshResolvedCredential retry = authSource.fetch(host);
                    if (retry == null) return;
                    current = retry;
                    continue;
                }

                if (kind == SshConnectException.Kind.HOST_KEY_REJECTED) {
                    Messages.showErrorDialog(project,
                        "Host key mismatch for " + host.host() + ":" + host.port() + ".\n\n"
                            + "The remote host presented a different key than the one TermLab "
                            + "has on file. This may mean someone is intercepting your "
                            + "connection (man-in-the-middle attack).\n\n"
                            + "If the key legitimately changed, remove the entry from "
                            + "~/.config/termlab/known_hosts manually and try again.",
                        "Host Key Rejected");
                    return;
                }

                Messages.showErrorDialog(project,
                    "SSH tunnel connection failed:\n" + outcome.failure.getMessage(),
                    "Tunnel Connection Failed");
                return;
            }
        } finally {
            if (bastionAuth != null) {
                bastionAuth.credential().close();
            }
        }
    }

    /**
     * Resolve the SSH host for a tunnel. For {@link InternalHost}, looks up
     * in {@link HostStore}. For {@link SshConfigHost}, builds a synthetic
     * {@link SshHost} that lets MINA resolve connection details from
     * {@code ~/.ssh/config} at connect time.
     */
    private @Nullable SshHost resolveHost(@NotNull SshTunnel tunnel) {
        return switch (tunnel.host()) {
            case InternalHost ih -> {
                HostStore hostStore =
                    ApplicationManager.getApplication().getService(HostStore.class);
                if (hostStore == null) {
                    Messages.showErrorDialog(project,
                        "SSH host store is unavailable.", "Tunnel Error");
                    yield null;
                }
                SshHost found = hostStore.findById(ih.hostId());
                if (found == null) {
                    Messages.showErrorDialog(project,
                        "The host this tunnel references no longer exists.\n"
                            + "Edit the tunnel to select a different host.",
                        "Host Not Found");
                    yield null;
                }
                yield found;
            }
            case SshConfigHost sh -> SshHost.create(
                sh.alias(),
                sh.alias(),
                SshHost.DEFAULT_PORT,
                System.getProperty("user.name"),
                new PromptPasswordAuth()
            );
        };
    }

    private @NotNull ConnectOutcome runConnectModal(
        @NotNull TermLabSshClient client,
        @NotNull SshTunnel tunnel,
        @NotNull SshHost host,
        @NotNull SshResolvedCredential credential,
        @Nullable TermLabSshClient.BastionAuth bastionAuth
    ) {
        ConnectOutcome outcome = new ConnectOutcome();
        String title = "Connecting to " + host.label()
            + " (" + host.host() + ":" + host.port() + ")…";

        ProgressManager.getInstance().run(new Task.Modal(null, title, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);

                AtomicReference<ClientSession> sessionRef = new AtomicReference<>();
                AtomicReference<SshConnectException> failureRef = new AtomicReference<>();
                Future<?> job = AppExecutorUtil.getAppExecutorService().submit(() -> {
                    try {
                        sessionRef.set(client.connectSession(host, credential, bastionAuth,
                            new TermLabServerKeyVerifier()));
                    } catch (SshConnectException e) {
                        failureRef.set(e);
                    } catch (Exception e) {
                        failureRef.set(new SshConnectException(
                            SshConnectException.Kind.UNKNOWN,
                            "Unexpected failure: " + e.getMessage(), e));
                    }
                });

                try {
                    while (true) {
                        if (indicator.isCanceled()) {
                            job.cancel(true);
                            LOG.warn("TermLab tunnel: connect cancelled by user tunnel="
                                + tunnel.label() + " -> shutting down SSH client");
                            client.shutdown();
                            outcome.cancelled = true;
                            ClientSession s = sessionRef.getAndSet(null);
                            if (s != null) {
                                try { s.close(true); } catch (Exception ignored) {}
                            }
                            return;
                        }
                        try {
                            job.get(100, TimeUnit.MILLISECONDS);
                            break;
                        } catch (TimeoutException ignored) {
                            // keep polling cancellation
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    job.cancel(true);
                    client.shutdown();
                    outcome.cancelled = true;
                    return;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    failureRef.compareAndSet(null, new SshConnectException(
                        SshConnectException.Kind.UNKNOWN,
                        "Unexpected failure: " + cause.getMessage(), cause));
                }

                outcome.session = sessionRef.get();
                outcome.failure = failureRef.get();
            }
        });

        return outcome;
    }

    private void disconnectTunnel(@NotNull SshTunnel tunnel) {
        connectionManager.disconnect(tunnel.id());
        refreshFromStore();
    }

    private void viewDetails(@NotNull SshTunnel tunnel) {
        TunnelState state = connectionManager.getState(tunnel.id());
        String direction = tunnel.type() == TunnelType.LOCAL ? "Local (-L)" : "Remote (-R)";
        String hostRef = switch (tunnel.host()) {
            case InternalHost ih -> {
                HostStore hs = ApplicationManager.getApplication().getService(HostStore.class);
                SshHost found = hs != null ? hs.findById(ih.hostId()) : null;
                yield found != null
                    ? found.label() + " (" + found.host() + ":" + found.port() + ")"
                    : "Unknown host (" + ih.hostId().toString().substring(0, 8) + "…)";
            }
            case SshConfigHost sh -> sh.alias() + " (~/.ssh/config)";
        };

        String details = "Label:         " + tunnel.label() + "\n"
            + "Type:          " + direction + "\n"
            + "Host:          " + hostRef + "\n"
            + "Bind address:  " + tunnel.bindAddress() + ":" + tunnel.bindPort() + "\n"
            + "Target:        " + tunnel.targetHost() + ":" + tunnel.targetPort() + "\n"
            + "Status:        " + state;

        var conn = connectionManager.getConnection(tunnel.id());
        if (conn != null && conn.errorMessage() != null) {
            details += "\nError:         " + conn.errorMessage();
        }
        if (conn != null && conn.boundAddress() != null) {
            details += "\nBound to:      " + conn.boundAddress();
        }

        Messages.showInfoMessage(project, details, "Tunnel Details — " + tunnel.label());
    }

    // -- CRUD helpers ---------------------------------------------------------

    private void addTunnel() {
        SshTunnel created = TunnelEditDialog.show(project, null);
        if (created == null) return;
        store.addTunnel(created);
        saveAndRefresh();
    }

    private void editSelected() {
        SshTunnel selected = list.getSelectedValue();
        if (selected == null) return;
        SshTunnel edited = TunnelEditDialog.show(project, selected);
        if (edited == null) return;
        store.updateTunnel(edited);
        saveAndRefresh();
    }

    private void duplicateSelected() {
        SshTunnel selected = list.getSelectedValue();
        if (selected == null) return;
        SshTunnel copy = SshTunnel.create(
            selected.label() + " (copy)",
            selected.type(),
            selected.host(),
            selected.bindPort(),
            selected.bindAddress(),
            selected.targetHost(),
            selected.targetPort()
        );
        store.addTunnel(copy);
        saveAndRefresh();
    }

    private void deleteSelected() {
        SshTunnel selected = list.getSelectedValue();
        if (selected == null) return;
        int choice = Messages.showYesNoDialog(
            project,
            "Delete tunnel \"" + selected.label() + "\"?",
            "Delete SSH Tunnel",
            Messages.getQuestionIcon());
        if (choice != Messages.YES) return;
        store.removeTunnel(selected.id());
        TunnelState state = connectionManager.getState(selected.id());
        if (state == TunnelState.ACTIVE || state == TunnelState.CONNECTING
            || state == TunnelState.ERROR) {
            connectionManager.disconnect(selected.id());
        }
        saveAndRefresh();
    }

    private void reloadFromDisk() {
        try {
            store.reload();
        } catch (IOException e) {
            Messages.showErrorDialog(project,
                "Could not reload tunnels from disk:\n" + e.getMessage(),
                "Reload Failed");
            return;
        }
        refreshFromStore();
    }

    private void saveAndRefresh() {
        try {
            store.save();
        } catch (IOException e) {
            Messages.showErrorDialog(project,
                "Could not save tunnels to disk:\n" + e.getMessage(),
                "Save Failed");
        }
        refreshFromStore();
    }

    // -- actions --------------------------------------------------------------

    private final class AddTunnelAction extends AnAction {
        AddTunnelAction() { super("Add Tunnel", "Add a new SSH tunnel", AllIcons.General.Add); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { addTunnel(); }
    }

    private final class EditTunnelAction extends AnAction {
        EditTunnelAction() {
            super("Edit Tunnel", "Edit the selected tunnel", AllIcons.Actions.Edit);
        }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { editSelected(); }
        @Override public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(list.getSelectedValue() != null);
        }
        @Override public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
            return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
        }
    }

    private final class DeleteTunnelAction extends AnAction {
        DeleteTunnelAction() {
            super("Delete Tunnel", "Delete the selected tunnel", AllIcons.General.Remove);
        }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { deleteSelected(); }
        @Override public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(list.getSelectedValue() != null);
        }
        @Override public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
            return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
        }
    }

    private final class RefreshAction extends AnAction {
        RefreshAction() {
            super("Refresh", "Reload tunnels from disk", AllIcons.Actions.Refresh);
        }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { reloadFromDisk(); }
    }

    // -- helper types ---------------------------------------------------------

    private @NotNull TermLabSshClient getClient() {
        if (ApplicationManager.getApplication() != null) {
            TermLabSshClient service =
                ApplicationManager.getApplication().getService(TermLabSshClient.class);
            if (service != null) return service;
        }
        return new TermLabSshClient();
    }

    /** Mutable holder the Task.Modal populates with either a session or a failure. */
    private static final class ConnectOutcome {
        @Nullable ClientSession session;
        @Nullable SshConnectException failure;
        boolean cancelled;
    }
}
