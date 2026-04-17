package com.termlab.vault.ui;

import com.termlab.vault.keygen.SshKeyGenerator;
import com.termlab.vault.lock.LockManager;
import com.termlab.vault.model.Vault;
import com.termlab.vault.model.VaultAccount;
import com.termlab.vault.model.VaultKey;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Top-level vault management dialog.
 *
 * <p>Two tabs:
 * <ul>
 *   <li><b>Accounts</b> — username + password/key credentials, one per host
 *       or service. Add / Edit / Delete buttons, double-click to edit.</li>
 *   <li><b>Keys</b> — named SSH keys generated or tracked by the vault.
 *       Generate / Rename / Delete / Copy Public Key buttons,
 *       double-click to rename.</li>
 * </ul>
 *
 * <p>Edits persist immediately through {@link LockManager#save()} on each
 * action — there is no separate Save step. The footer simply dismisses the
 * dialog, while each tab exposes a lock button in its action row.
 */
public final class VaultDialog extends DialogWrapper {

    private final Project project;
    private final LockManager lockManager;

    // Accounts tab state.
    private final DefaultListModel<VaultAccount> accountModel = new DefaultListModel<>();
    private final JBList<VaultAccount> accountList = new JBList<>(accountModel);
    private final SearchTextField accountSearch = new SearchTextField();

    // Keys tab state.
    private final DefaultListModel<VaultKey> keyModel = new DefaultListModel<>();
    private final JBList<VaultKey> keyList = new JBList<>(keyModel);
    private final SearchTextField keySearch = new SearchTextField();

    public VaultDialog(@Nullable Project project, @NotNull LockManager lockManager) {
        super(project, true);
        this.project = project;
        this.lockManager = lockManager;
        setTitle("Credential Vault");
        setModal(false);
        setOKButtonText("Okay");
        init();
        reloadAccounts();
        reloadKeys();
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("Accounts", buildAccountsTab());
        tabs.addTab("Keys", buildKeysTab());

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(JBUI.Borders.empty(8));
        root.setPreferredSize(new Dimension(640, 460));
        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    // -- Accounts tab ---------------------------------------------------------

    private JComponent buildAccountsTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(JBUI.Borders.empty(8));

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(accountSearch, BorderLayout.CENTER);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton addBtn = new JButton("+ Add");
        JButton editBtn = new JButton("Edit");
        JButton deleteBtn = new JButton("Delete");
        JButton lockBtn = createLockButton();
        toolbar.add(addBtn);
        toolbar.add(editBtn);
        toolbar.add(deleteBtn);
        toolbar.add(lockBtn);
        top.add(toolbar, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);

        accountList.setCellRenderer(new VaultAccountCellRenderer());
        accountList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        accountList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) editSelectedAccount();
            }
        });
        panel.add(new JBScrollPane(accountList), BorderLayout.CENTER);

        addBtn.addActionListener(e -> addAccount());
        editBtn.addActionListener(e -> editSelectedAccount());
        deleteBtn.addActionListener(e -> deleteSelectedAccount());

        accountSearch.addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent e) { reloadAccounts(); }
        });

        return panel;
    }

    private void reloadAccounts() {
        Vault vault = lockManager.getVault();
        if (vault == null) {
            accountModel.clear();
            return;
        }
        String query = accountSearch.getText().toLowerCase();
        List<VaultAccount> filtered = vault.accounts.stream()
            .filter(a -> query.isEmpty()
                || a.displayName().toLowerCase().contains(query)
                || a.username().toLowerCase().contains(query))
            .sorted(Comparator.comparing(VaultAccount::displayName, String.CASE_INSENSITIVE_ORDER))
            .collect(Collectors.toList());
        accountModel.clear();
        filtered.forEach(accountModel::addElement);
        if (!filtered.isEmpty()) accountList.setSelectedIndex(0);
    }

    private void addAccount() {
        Vault vault = lockManager.getVault();
        if (vault == null) return;
        VaultAccount created = AccountEditDialog.show(project, null);
        if (created == null) return;
        vault.accounts.add(created);
        persist();
        reloadAccounts();
    }

    private void editSelectedAccount() {
        VaultAccount selected = accountList.getSelectedValue();
        if (selected == null) return;
        Vault vault = lockManager.getVault();
        if (vault == null) return;
        VaultAccount updated = AccountEditDialog.show(project, selected);
        if (updated == null) return;
        vault.accounts.removeIf(a -> a.id().equals(selected.id()));
        vault.accounts.add(updated);
        persist();
        reloadAccounts();
    }

    private void deleteSelectedAccount() {
        VaultAccount selected = accountList.getSelectedValue();
        if (selected == null) return;
        int answer = Messages.showYesNoDialog(
            getContentPanel(),
            "Delete account \"" + selected.displayName() + "\"?",
            "Delete Account",
            Messages.getWarningIcon());
        if (answer != Messages.YES) return;

        Vault vault = lockManager.getVault();
        if (vault == null) return;
        vault.accounts.removeIf(a -> a.id().equals(selected.id()));
        persist();
        reloadAccounts();
    }

    // -- Keys tab -------------------------------------------------------------

    private JComponent buildKeysTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(JBUI.Borders.empty(8));

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(keySearch, BorderLayout.CENTER);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton genBtn = new JButton("Generate…");
        JButton renameBtn = new JButton("Rename");
        JButton copyBtn = new JButton("Copy Public Key");
        JButton deleteBtn = new JButton("Delete");
        JButton lockBtn = createLockButton();
        toolbar.add(genBtn);
        toolbar.add(renameBtn);
        toolbar.add(copyBtn);
        toolbar.add(deleteBtn);
        toolbar.add(lockBtn);
        top.add(toolbar, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);

        keyList.setCellRenderer(new VaultKeyCellRenderer());
        keyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        keyList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) renameSelectedKey();
            }
        });
        panel.add(new JBScrollPane(keyList), BorderLayout.CENTER);

        genBtn.addActionListener(e -> {
            KeyGenDialog dlg = new KeyGenDialog(project, lockManager);
            if (dlg.showAndGet()) reloadKeys();
        });
        renameBtn.addActionListener(e -> renameSelectedKey());
        copyBtn.addActionListener(e -> copySelectedPublicKey());
        deleteBtn.addActionListener(e -> deleteSelectedKey());

        keySearch.addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent e) { reloadKeys(); }
        });

        return panel;
    }

    private void reloadKeys() {
        Vault vault = lockManager.getVault();
        if (vault == null) {
            keyModel.clear();
            return;
        }
        String query = keySearch.getText().toLowerCase();
        List<VaultKey> filtered = vault.keys.stream()
            .filter(k -> query.isEmpty()
                || k.name().toLowerCase().contains(query)
                || k.algorithm().toLowerCase().contains(query)
                || k.fingerprint().toLowerCase().contains(query))
            .sorted(Comparator.comparing(VaultKey::name, String.CASE_INSENSITIVE_ORDER))
            .collect(Collectors.toList());
        keyModel.clear();
        filtered.forEach(keyModel::addElement);
        if (!filtered.isEmpty()) keyList.setSelectedIndex(0);
    }

    private void renameSelectedKey() {
        VaultKey selected = keyList.getSelectedValue();
        if (selected == null) return;
        Vault vault = lockManager.getVault();
        if (vault == null) return;
        VaultKey updated = KeyEditDialog.show(project, selected);
        if (updated == null) return;
        vault.keys.removeIf(k -> k.id().equals(selected.id()));
        vault.keys.add(updated);
        persist();
        reloadKeys();
    }

    private void copySelectedPublicKey() {
        VaultKey selected = keyList.getSelectedValue();
        if (selected == null) return;
        try {
            String contents = Files.readString(Paths.get(selected.publicPath())).trim();
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(contents), null);
        } catch (IOException e) {
            Messages.showErrorDialog(getContentPanel(),
                "Could not read public key file: " + e.getMessage(),
                "Copy Failed");
        }
    }

    private void deleteSelectedKey() {
        VaultKey selected = keyList.getSelectedValue();
        if (selected == null) return;
        int answer = Messages.showYesNoDialog(
            getContentPanel(),
            "Delete key \"" + selected.name() + "\"?\n\n"
                + "This also removes the key files on disk:\n"
                + "  " + selected.privatePath() + "\n"
                + "  " + selected.publicPath(),
            "Delete Key",
            Messages.getWarningIcon());
        if (answer != Messages.YES) return;

        Vault vault = lockManager.getVault();
        if (vault == null) return;
        try {
            new SshKeyGenerator().delete(selected);
        } catch (IOException e) {
            Messages.showWarningDialog(getContentPanel(),
                "Removed from vault, but could not delete key files from disk: "
                    + e.getMessage(),
                "Disk Delete Failed");
        }
        vault.keys.removeIf(k -> k.id().equals(selected.id()));
        persist();
        reloadKeys();
    }

    // -- shared ---------------------------------------------------------------

    private JButton createLockButton() {
        JButton lockBtn = new JButton(AllIcons.Ide.Readonly);
        lockBtn.setToolTipText("Lock vault");
        lockBtn.addActionListener(e -> lockAndClose());
        return lockBtn;
    }

    private void lockAndClose() {
        lockManager.lock();
        close(OK_EXIT_CODE);
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
