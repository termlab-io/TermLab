package com.conch.ssh.actions;

import com.conch.ssh.model.HostStore;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.toolwindow.HostCellRenderer;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupChooserBuilder;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.List;

/**
 * "New SSH Session…" — the Cmd+K / Ctrl+K entry point.
 *
 * <p>Shows a filterable popup list of every host in {@link HostStore},
 * rendered by {@link HostCellRenderer}. Picking a host dispatches
 * {@link ConnectToHostAction#run(Project, SshHost)}.
 *
 * <p>The same code path is reused by the
 * {@code HostsPaletteContributor} for Cmd+Shift+P; the only difference
 * is the entry point.
 *
 * <p>When no hosts are saved, shows an info dialog pointing the user at
 * the Hosts tool window so they can add one.
 */
public final class NewSshSessionAction extends AnAction {

    public NewSshSessionAction() {
        super("New SSH Session…", "Open a host picker and start a new SSH session", null);
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
            .setTitle("New SSH Session")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setItemChosenCallback(() -> {
                SshHost picked = list.getSelectedValue();
                if (picked != null) ConnectToHostAction.run(project, picked);
            });

        JBPopup popup = builder.createPopup();

        // Wire the search box: filter the list model on each keystroke.
        // Enter submits the current selection; Escape dismisses the popup.
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

    private static boolean matches(@NotNull SshHost host, @NotNull String query) {
        return host.label().toLowerCase().contains(query)
            || host.host().toLowerCase().contains(query)
            || host.username().toLowerCase().contains(query);
    }
}
