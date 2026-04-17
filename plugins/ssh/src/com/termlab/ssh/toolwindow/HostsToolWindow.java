package com.termlab.ssh.toolwindow;

import com.termlab.ssh.actions.ConnectToHostAction;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.SshHost;
import com.termlab.ssh.ui.HostEditDialog;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;

/**
 * The "Hosts" left-sidebar tool window. A toolbar with
 * Add / Edit / Delete / Refresh + a {@link JBList} of saved SSH hosts.
 *
 * <p>Interaction model:
 * <ul>
 *   <li><b>Double-click</b> a host → {@link ConnectToHostAction#run}
 *       (opens a new editor tab connected to that host)</li>
 *   <li><b>Right-click</b> a host → context menu: Connect, Edit,
 *       Duplicate, Delete</li>
 *   <li><b>+</b> toolbar button → {@link HostEditDialog} in add mode</li>
 *   <li><b>Edit</b> toolbar button → dialog prefilled with the selection</li>
 *   <li><b>Delete</b> toolbar button → confirm + remove</li>
 *   <li><b>⟳</b> toolbar button → {@link HostStore#reload()} from disk,
 *       in case the user edited {@code ssh-hosts.json} by hand</li>
 * </ul>
 *
 * <p>All mutations run on the EDT and go through the shared application
 * service instance of {@link HostStore}. Every mutation calls
 * {@code store.save()} so the JSON on disk stays authoritative.
 *
 * <p>Vault lock state does not affect this view — hosts are plaintext
 * metadata, only the credentials they reference live inside the vault.
 * A locked vault will show up when the user actually tries to
 * <em>connect</em>, at which point the session provider runs the vault
 * picker / unlock flow.
 */
public final class HostsToolWindow extends JPanel {

    private final Project project;
    private final HostStore store;
    private final DefaultListModel<SshHost> listModel = new DefaultListModel<>();
    private final JBList<SshHost> list = new JBList<>(listModel);

    public HostsToolWindow(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.store = ApplicationManager.getApplication().getService(HostStore.class);

        list.setCellRenderer(new HostCellRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    SshHost selected = list.getSelectedValue();
                    if (selected != null) ConnectToHostAction.run(project, selected);
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

    // -- data sync ------------------------------------------------------------

    private void refreshFromStore() {
        SshHost previousSelection = list.getSelectedValue();
        listModel.clear();
        for (SshHost h : store.getHosts()) listModel.addElement(h);
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
        // Placeholder for future enable/disable of context-sensitive
        // actions. The toolbar itself is context-insensitive for now —
        // the action implementations check for a selection at invoke
        // time.
    }

    // -- toolbar --------------------------------------------------------------

    private @NotNull ActionToolbar buildToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new AddHostAction());
        group.add(new EditHostAction());
        group.add(new DeleteHostAction());
        group.addSeparator();
        group.add(new RefreshAction());

        ActionToolbar toolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionToolbar("HostsToolbar", group, true);
        toolbar.setTargetComponent(this);
        return toolbar;
    }

    // -- context menu ---------------------------------------------------------

    private void showContextMenu(Component comp, int x, int y) {
        SshHost selected = list.getSelectedValue();
        if (selected == null) return;

        JPopupMenu menu = new JPopupMenu();
        JMenuItem connect = new JMenuItem("Connect");
        connect.addActionListener(e -> ConnectToHostAction.run(project, selected));
        menu.add(connect);

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

    // -- mutation helpers -----------------------------------------------------

    private void addHost() {
        SshHost created = HostEditDialog.show(project, null);
        if (created == null) return;
        store.addHost(created);
        saveAndRefresh();
    }

    private void editSelected() {
        SshHost selected = list.getSelectedValue();
        if (selected == null) return;
        SshHost edited = HostEditDialog.show(project, selected);
        if (edited == null) return;
        store.updateHost(edited);
        saveAndRefresh();
    }

    private void duplicateSelected() {
        SshHost selected = list.getSelectedValue();
        if (selected == null) return;
        SshHost copy = SshHost.create(
            selected.label() + " (copy)",
            selected.host(),
            selected.port(),
            selected.username(),
            selected.auth(),
            selected.proxyCommand(),
            selected.proxyJump()
        );
        store.addHost(copy);
        saveAndRefresh();
    }

    private void deleteSelected() {
        SshHost selected = list.getSelectedValue();
        if (selected == null) return;
        int choice = Messages.showYesNoDialog(
            project,
            "Delete host \"" + selected.label() + "\"?",
            "Delete SSH Host",
            Messages.getQuestionIcon());
        if (choice != Messages.YES) return;
        store.removeHost(selected.id());
        saveAndRefresh();
    }

    private void reloadFromDisk() {
        try {
            store.reload();
        } catch (IOException e) {
            Messages.showErrorDialog(project,
                "Could not reload hosts from disk:\n" + e.getMessage(),
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
                "Could not save hosts to disk:\n" + e.getMessage(),
                "Save Failed");
        }
        refreshFromStore();
    }

    // -- actions --------------------------------------------------------------

    private final class AddHostAction extends AnAction {
        AddHostAction() { super("Add Host", "Add a new SSH host", AllIcons.General.Add); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { addHost(); }
    }

    private final class EditHostAction extends AnAction {
        EditHostAction() { super("Edit Host", "Edit the selected host", AllIcons.Actions.Edit); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { editSelected(); }
        @Override public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(list.getSelectedValue() != null);
        }
        @Override public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
            return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
        }
    }

    private final class DeleteHostAction extends AnAction {
        DeleteHostAction() { super("Delete Host", "Delete the selected host", AllIcons.General.Remove); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { deleteSelected(); }
        @Override public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(list.getSelectedValue() != null);
        }
        @Override public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
            return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
        }
    }

    private final class RefreshAction extends AnAction {
        RefreshAction() { super("Refresh", "Reload hosts from disk", AllIcons.Actions.Refresh); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { reloadFromDisk(); }
    }

    // Access for tests / the factory.
    @Nullable HostStore getStoreForTesting() { return store; }
}
