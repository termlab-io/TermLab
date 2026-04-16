package com.termlab.sftp.actions;

import com.termlab.sftp.toolwindow.RemoteFilePane;
import com.termlab.sftp.toolwindow.SftpToolWindow;
import com.termlab.sftp.toolwindow.SftpToolWindowFactory;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.SshHost;
import com.termlab.ssh.toolwindow.HostCellRenderer;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupChooserBuilder;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.List;
import java.util.UUID;

/**
 * "New SFTP Session…" — the Cmd+Shift+K / Ctrl+Shift+K entry point
 * that mirrors the SSH plugin's Cmd+K popup. Shows a filterable
 * list of every saved {@link SshHost} from {@link HostStore};
 * picking a host opens the SFTP tool window (if not already
 * visible) and kicks off
 * {@link RemoteFilePane#connectTo(UUID)} against that host.
 *
 * <p>Reuses {@link HostCellRenderer} so the list looks identical
 * to the SSH version — same host label / user@host:port format.
 */
public final class NewSftpSessionAction extends AnAction {

    public NewSftpSessionAction() {
        super("New SFTP Session…", "Open a host picker and start a new SFTP session", null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        HostStore store = ApplicationManager.getApplication().getService(HostStore.class);
        if (store == null) return;

        List<SshHost> hosts = store.getHosts();
        if (hosts.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No saved SSH hosts yet. Open the Hosts tool window to add one.",
                "No Hosts");
            return;
        }

        showPicker(project, hosts);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private void showPicker(@NotNull Project project, @NotNull List<SshHost> allHosts) {
        DefaultListModel<SshHost> model = new DefaultListModel<>();
        for (SshHost h : allHosts) model.addElement(h);

        JBList<SshHost> list = new JBList<>(model);
        list.setCellRenderer(new HostCellRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (model.size() > 0) list.setSelectedIndex(0);

        JBTextField search = new JBTextField();
        search.getEmptyText().setText("Search hosts…");

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(search, BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(480, 320));

        PopupChooserBuilder<SshHost> builder = JBPopupFactory.getInstance()
            .createListPopupBuilder(list)
            .setTitle("New SFTP Session")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setItemChosenCallback(() -> {
                SshHost picked = list.getSelectedValue();
                if (picked != null) openSftpToHost(project, picked);
            });

        JBPopup popup = builder.createPopup();

        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }

            private void applyFilter() {
                String query = search.getText().toLowerCase();
                model.clear();
                for (SshHost h : allHosts) {
                    if (query.isEmpty() || matches(h, query)) model.addElement(h);
                }
                if (model.size() > 0) list.setSelectedIndex(0);
            }
        });

        popup.showCenteredInCurrentWindow(project);
    }

    private static void openSftpToHost(@NotNull Project project, @NotNull SshHost host) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(SftpToolWindowFactory.ID);
        if (toolWindow == null) return;
        toolWindow.show(() -> {
            Content content = toolWindow.getContentManager().getSelectedContent();
            if (content != null && content.getComponent() instanceof SftpToolWindow panel) {
                panel.remotePane().connectTo(host.id());
            }
        });
    }

    private static boolean matches(@NotNull SshHost host, @NotNull String query) {
        return host.label().toLowerCase().contains(query)
            || host.host().toLowerCase().contains(query)
            || host.username().toLowerCase().contains(query);
    }
}
