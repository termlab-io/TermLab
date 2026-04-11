package com.conch.vault.ui;

import com.conch.vault.lock.LockManager;
import com.conch.vault.model.Vault;
import com.conch.vault.model.VaultAccount;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Main vault management dialog: search box, account list, Add/Edit/Delete
 * buttons, and a Lock button. Persists every change through
 * {@link LockManager#save()}.
 */
public final class VaultDialog extends DialogWrapper {

    private final Project project;
    private final LockManager lockManager;

    private final DefaultListModel<VaultAccount> model = new DefaultListModel<>();
    private final JBList<VaultAccount> list = new JBList<>(model);
    private final SearchTextField search = new SearchTextField();

    public VaultDialog(@Nullable Project project, @NotNull LockManager lockManager) {
        super(project, true);
        this.project = project;
        this.lockManager = lockManager;
        setTitle("Conch Vault");
        setModal(false);
        init();
        reload();
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(520, 420));

        // Top: search + toolbar.
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(search, BorderLayout.CENTER);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton addBtn = new JButton("+ Add");
        JButton editBtn = new JButton("Edit");
        JButton deleteBtn = new JButton("Delete");
        JButton lockBtn = new JButton("Lock");
        toolbar.add(addBtn);
        toolbar.add(editBtn);
        toolbar.add(deleteBtn);
        toolbar.add(lockBtn);
        top.add(toolbar, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);

        // Center: account list.
        list.setCellRenderer(new VaultAccountCellRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) editSelected();
            }
        });
        panel.add(new JBScrollPane(list), BorderLayout.CENTER);

        addBtn.addActionListener(e -> addAccount());
        editBtn.addActionListener(e -> editSelected());
        deleteBtn.addActionListener(e -> deleteSelected());
        lockBtn.addActionListener(e -> {
            lockManager.lock();
            close(OK_EXIT_CODE);
        });

        search.addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent e) { reload(); }
        });

        return panel;
    }

    @Override
    protected @NotNull Action[] createActions() {
        // No OK/Cancel — this is a management surface; changes persist
        // immediately via LockManager.save() on each edit action.
        return new Action[]{ getCancelAction() };
    }

    private void reload() {
        Vault vault = lockManager.getVault();
        if (vault == null) {
            model.clear();
            return;
        }
        String query = search.getText().toLowerCase();
        List<VaultAccount> filtered = vault.accounts.stream()
            .filter(a -> query.isEmpty()
                || a.displayName().toLowerCase().contains(query)
                || a.username().toLowerCase().contains(query))
            .sorted(Comparator.comparing(VaultAccount::displayName, String.CASE_INSENSITIVE_ORDER))
            .collect(Collectors.toList());
        model.clear();
        filtered.forEach(model::addElement);
        if (!filtered.isEmpty()) list.setSelectedIndex(0);
    }

    private void addAccount() {
        Vault vault = lockManager.getVault();
        if (vault == null) return;
        VaultAccount created = AccountEditDialog.show(project, null);
        if (created == null) return;
        vault.accounts.add(created);
        persist();
        reload();
    }

    private void editSelected() {
        VaultAccount selected = list.getSelectedValue();
        if (selected == null) return;
        Vault vault = lockManager.getVault();
        if (vault == null) return;

        VaultAccount updated = AccountEditDialog.show(project, selected);
        if (updated == null) return;
        vault.accounts.removeIf(a -> a.id().equals(selected.id()));
        vault.accounts.add(updated);
        persist();
        reload();
    }

    private void deleteSelected() {
        VaultAccount selected = list.getSelectedValue();
        if (selected == null) return;
        int answer = Messages.showYesNoDialog(
            getContentPanel(),
            "Delete credential \"" + selected.displayName() + "\"?",
            "Delete Credential",
            Messages.getWarningIcon());
        if (answer != Messages.YES) return;

        Vault vault = lockManager.getVault();
        if (vault == null) return;
        vault.accounts.removeIf(a -> a.id().equals(selected.id()));
        persist();
        reload();
    }

    private void persist() {
        try {
            lockManager.save();
        } catch (IOException e) {
            Messages.showErrorDialog(getContentPanel(),
                "Could not write vault file: " + e.getMessage(),
                "Vault Save Failed");
        }
    }
}
