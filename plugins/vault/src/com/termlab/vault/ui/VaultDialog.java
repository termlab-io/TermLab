package com.termlab.vault.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.termlab.vault.keygen.SshKeyGenerator;
import com.termlab.vault.lock.LockManager;
import com.termlab.vault.model.AuthMethod;
import com.termlab.vault.model.Vault;
import com.termlab.vault.model.VaultAccount;
import com.termlab.vault.model.VaultKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class VaultDialog extends DialogWrapper {

    private final Project project;
    private final LockManager lockManager;
    private final DefaultListModel<VaultEntry> model = new DefaultListModel<>();
    private final JBList<VaultEntry> list = new JBList<>(model);
    private final SearchTextField search = new SearchTextField();
    private final JComboBox<TypeFilter> typeFilter = new JComboBox<>(TypeFilter.values());
    private ActionToolbar toolbar;

    public VaultDialog(@Nullable Project project, @NotNull LockManager lockManager) {
        super(project, true);
        this.project = project;
        this.lockManager = lockManager;
        setTitle("Credential Vault");
        setModal(false);
        setOKButtonText("Okay");
        init();
        reloadEntries(null);
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(JBUI.Borders.empty(8));
        root.setPreferredSize(new Dimension(760, 500));

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(search, BorderLayout.CENTER);
        toolbar = buildToolbar(root);
        JPanel right = new JPanel(new BorderLayout(8, 0));
        right.add(typeFilter, BorderLayout.WEST);
        right.add(toolbar.getComponent(), BorderLayout.EAST);
        top.add(right, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        list.setCellRenderer(new VaultEntryCellRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) editSelected();
            }
        });
        list.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) refreshToolbar();
        });
        root.add(new JBScrollPane(list), BorderLayout.CENTER);

        search.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                reloadEntries(selectedId());
            }
        });
        typeFilter.addActionListener(e -> reloadEntries(selectedId()));

        return root;
    }

    private void reloadEntries(UUID selectedId) {
        Vault vault = lockManager.getVault();
        model.clear();
        if (vault == null) {
            refreshToolbar();
            return;
        }

        String query = search.getText().trim().toLowerCase();
        TypeFilter filter = selectedFilter();
        List<VaultEntry> entries = new ArrayList<>();
        for (VaultAccount account : vault.accounts) {
            VaultEntry entry = new AccountEntry(account);
            if (matches(entry, query, filter)) entries.add(entry);
        }
        for (VaultKey key : vault.keys) {
            VaultEntry entry = new KeyEntry(key);
            if (matches(entry, query, filter)) entries.add(entry);
        }
        entries.sort(Comparator.comparing(VaultEntry::label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(VaultEntry::typeLabel, String.CASE_INSENSITIVE_ORDER));
        entries.forEach(model::addElement);
        restoreSelection(selectedId);
        if (list.getSelectedIndex() < 0 && !entries.isEmpty()) list.setSelectedIndex(0);
        refreshToolbar();
    }

    private boolean matches(@NotNull VaultEntry entry, @NotNull String query, @NotNull TypeFilter filter) {
        return (filter == TypeFilter.ALL || filter.matches(entry))
            && (query.isEmpty() || entry.label().toLowerCase().contains(query));
    }

    private void restoreSelection(UUID selectedId) {
        if (selectedId == null) return;
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).id().equals(selectedId)) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                return;
            }
        }
    }

    private UUID selectedId() {
        VaultEntry selected = selectedEntry();
        return selected == null ? null : selected.id();
    }

    private VaultEntry selectedEntry() {
        return list.getSelectedValue();
    }

    private @NotNull TypeFilter selectedFilter() {
        Object selected = typeFilter.getSelectedItem();
        return selected instanceof TypeFilter filter ? filter : TypeFilter.ALL;
    }

    private void showAddCredentialMenu(@NotNull Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem login = new JMenuItem("Login");
        JMenuItem apiKey = new JMenuItem("API Key");
        JMenuItem secureNote = new JMenuItem("Secure Note");
        JMenuItem sshKey = new JMenuItem("SSH Key");
        login.addActionListener(e -> addAccount(CredentialType.LOGIN));
        apiKey.addActionListener(e -> addAccount(CredentialType.API_KEY));
        secureNote.addActionListener(e -> addAccount(CredentialType.SECURE_NOTE));
        sshKey.addActionListener(e -> generateSshKey());
        menu.add(login);
        menu.add(apiKey);
        menu.add(secureNote);
        menu.addSeparator();
        menu.add(sshKey);
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void addAccount(@NotNull CredentialType type) {
        Vault vault = lockManager.getVault();
        if (vault == null) return;
        VaultAccount created = AccountEditDialog.show(project, null, type);
        if (created == null) return;
        vault.accounts.add(created);
        persist();
        reloadEntries(created.id());
    }

    private void generateSshKey() {
        KeyGenDialog dlg = new KeyGenDialog(project, lockManager);
        if (dlg.showAndGet()) reloadEntries(null);
    }

    private void editSelected() {
        VaultEntry selected = selectedEntry();
        if (selected instanceof AccountEntry accountEntry) {
            editAccount(accountEntry.account());
        } else if (selected instanceof KeyEntry keyEntry) {
            renameKey(keyEntry.key());
        }
    }

    private void editAccount(@NotNull VaultAccount selected) {
        Vault vault = lockManager.getVault();
        if (vault == null) return;
        VaultAccount updated = AccountEditDialog.show(project, selected);
        if (updated == null) return;
        vault.accounts.removeIf(a -> a.id().equals(selected.id()));
        vault.accounts.add(updated);
        persist();
        reloadEntries(updated.id());
    }

    private void renameKey(@NotNull VaultKey selected) {
        Vault vault = lockManager.getVault();
        if (vault == null) return;
        VaultKey updated = KeyEditDialog.show(project, selected);
        if (updated == null) return;
        vault.keys.removeIf(k -> k.id().equals(selected.id()));
        vault.keys.add(updated);
        persist();
        reloadEntries(updated.id());
    }

    private void deleteSelected() {
        VaultEntry selected = selectedEntry();
        if (selected instanceof AccountEntry accountEntry) {
            deleteAccount(accountEntry.account());
        } else if (selected instanceof KeyEntry keyEntry) {
            deleteKey(keyEntry.key());
        }
    }

    private void deleteAccount(@NotNull VaultAccount selected) {
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
        reloadEntries(null);
    }

    private void deleteKey(@NotNull VaultKey selected) {
        int answer = Messages.showYesNoDialog(
            getContentPanel(),
            "Delete key \"" + selected.name() + "\"?\n\n"
                + "This also removes the key files on disk:\n"
                + "  " + selected.privatePath() + "\n"
                + "  " + selected.publicPath(),
            "Delete SSH Key",
            Messages.getWarningIcon());
        if (answer != Messages.YES) return;

        Vault vault = lockManager.getVault();
        if (vault == null) return;
        try {
            new SshKeyGenerator().delete(selected);
        } catch (IOException e) {
            Messages.showWarningDialog(getContentPanel(),
                "Removed from vault, but could not delete key files from disk: " + e.getMessage(),
                "Disk Delete Failed");
        }
        vault.keys.removeIf(k -> k.id().equals(selected.id()));
        persist();
        reloadEntries(null);
    }

    private void copySelectedPublicKey() {
        VaultEntry selected = selectedEntry();
        if (!(selected instanceof KeyEntry keyEntry)) return;
        try {
            String contents = Files.readString(Paths.get(keyEntry.key().publicPath())).trim();
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(contents), null);
        } catch (IOException e) {
            Messages.showErrorDialog(getContentPanel(),
                "Could not read public key file: " + e.getMessage(),
                "Copy Failed");
        }
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

    private @NotNull ActionToolbar buildToolbar(@NotNull JComponent target) {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new AddAction());
        group.add(new EditAction());
        group.add(new DeleteAction());
        group.add(new CopyPublicKeyAction());
        group.addSeparator();
        group.add(new LockAction());
        ActionToolbar created = ActionManager.getInstance().createActionToolbar("VaultToolbar", group, true);
        created.setTargetComponent(target);
        return created;
    }

    private void refreshToolbar() {
        if (toolbar != null) toolbar.updateActionsImmediately();
    }

    private final class AddAction extends AnAction {
        private AddAction() {
            super("Add", "Add credential", AllIcons.General.Add);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            showAddCredentialMenu(toolbar.getComponent());
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private final class EditAction extends AnAction {
        private EditAction() {
            super("Edit", "Edit selected credential", AllIcons.Actions.Edit);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(selectedEntry() != null);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            editSelected();
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private final class DeleteAction extends AnAction {
        private DeleteAction() {
            super("Delete", "Delete selected credential", AllIcons.General.Remove);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(selectedEntry() != null);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            deleteSelected();
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private final class CopyPublicKeyAction extends AnAction {
        private CopyPublicKeyAction() {
            super("Copy Public Key", "Copy selected SSH public key", AllIcons.Actions.Copy);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(selectedEntry() instanceof KeyEntry);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            copySelectedPublicKey();
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private final class LockAction extends AnAction {
        private LockAction() {
            super("Lock Vault", "Lock credential vault", AllIcons.Ide.Readonly);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            lockAndClose();
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private sealed interface VaultEntry permits AccountEntry, KeyEntry {
        @NotNull UUID id();
        @NotNull String label();
        @NotNull String typeLabel();
        @NotNull TypeFilter filterType();
        @NotNull String subtitle();
    }

    private record AccountEntry(@NotNull VaultAccount account) implements VaultEntry {
        @Override
        public @NotNull UUID id() {
            return account.id();
        }

        @Override
        public @NotNull String label() {
            return account.displayName();
        }

        @Override
        public @NotNull String typeLabel() {
            return switch (account.auth()) {
                case AuthMethod.Password ignored -> "Login";
                case AuthMethod.Key ignored -> "Login";
                case AuthMethod.KeyAndPassword ignored -> "Login";
                case AuthMethod.ApiToken ignored -> "API Key";
                case AuthMethod.SecureNote ignored -> "Secure Note";
            };
        }

        @Override
        public @NotNull TypeFilter filterType() {
            return switch (account.auth()) {
                case AuthMethod.Password ignored -> TypeFilter.LOGIN;
                case AuthMethod.Key ignored -> TypeFilter.LOGIN;
                case AuthMethod.KeyAndPassword ignored -> TypeFilter.LOGIN;
                case AuthMethod.ApiToken ignored -> TypeFilter.API_KEY;
                case AuthMethod.SecureNote ignored -> TypeFilter.SECURE_NOTE;
            };
        }

        @Override
        public @NotNull String subtitle() {
            return switch (account.auth()) {
                case AuthMethod.Password ignored -> blank(account.username()) + " · password";
                case AuthMethod.Key key -> blank(account.username()) + " · key: " + shortPath(key.keyPath());
                case AuthMethod.KeyAndPassword keyAndPassword ->
                    blank(account.username()) + " · key+password: " + shortPath(keyAndPassword.keyPath());
                case AuthMethod.ApiToken ignored -> blank(account.username());
                case AuthMethod.SecureNote ignored -> "Encrypted note";
            };
        }
    }

    private record KeyEntry(@NotNull VaultKey key) implements VaultEntry {
        @Override
        public @NotNull UUID id() {
            return key.id();
        }

        @Override
        public @NotNull String label() {
            return key.name();
        }

        @Override
        public @NotNull String typeLabel() {
            return "SSH Key";
        }

        @Override
        public @NotNull TypeFilter filterType() {
            return TypeFilter.SSH_KEY;
        }

        @Override
        public @NotNull String subtitle() {
            return key.algorithm() + " · " + key.fingerprint();
        }
    }

    private enum TypeFilter {
        ALL("All Types"),
        LOGIN("Login"),
        API_KEY("API Key"),
        SECURE_NOTE("Secure Note"),
        SSH_KEY("SSH Key");

        private final String label;

        TypeFilter(String label) {
            this.label = label;
        }

        boolean matches(@NotNull VaultEntry entry) {
            return entry.filterType() == this;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class VaultEntryCellRenderer extends JPanel implements ListCellRenderer<VaultEntry> {
        private final JLabel name = new JLabel();
        private final JLabel type = new JLabel();
        private final JLabel subtitle = new JLabel();

        private VaultEntryCellRenderer() {
            super(new BorderLayout(8, 0));
            setBorder(JBUI.Borders.empty(5, 8));
            JPanel lines = new JPanel(new GridLayout(2, 1));
            lines.setOpaque(false);
            lines.add(name);
            lines.add(subtitle);
            add(lines, BorderLayout.CENTER);
            type.setHorizontalAlignment(JLabel.RIGHT);
            add(type, BorderLayout.EAST);
            subtitle.setFont(subtitle.getFont().deriveFont(subtitle.getFont().getSize() - 1f));
            type.setFont(type.getFont().deriveFont(type.getFont().getSize() - 1f));
        }

        @Override
        public Component getListCellRendererComponent(
            JList<? extends VaultEntry> list,
            VaultEntry value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
        ) {
            if (value == null) {
                name.setText("");
                subtitle.setText("");
                type.setText("");
                return this;
            }
            name.setText(value.label());
            subtitle.setText(value.subtitle());
            type.setText(value.typeLabel());

            Color bg = isSelected ? list.getSelectionBackground() : list.getBackground();
            Color fg = isSelected ? list.getSelectionForeground() : list.getForeground();
            Color muted = UIManager.getColor("Label.disabledForeground");
            setBackground(bg);
            name.setForeground(fg);
            subtitle.setForeground(isSelected ? fg : muted);
            type.setForeground(isSelected ? fg : muted);
            return this;
        }
    }

    private static @NotNull String shortPath(@NotNull String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    private static @NotNull String blank(@Nullable String value) {
        return value == null || value.isBlank() ? "--" : value;
    }
}
