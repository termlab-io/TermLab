package com.conch.vault.ui;

import com.conch.vault.lock.LockManager;
import com.conch.vault.model.Vault;
import com.conch.vault.model.VaultAccount;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Stripped-down account picker shown by
 * {@link com.conch.vault.credentials.VaultCredentialProvider#promptForCredential()}.
 *
 * <p>Unlike {@link VaultDialog}, this dialog doesn't offer add/edit/delete —
 * it's a single-purpose "pick one" surface with a search box and an OK/Cancel
 * pair. If the vault is locked when {@link #show(Project, LockManager)} is
 * called, an {@link UnlockDialog} runs first; cancelling that returns null.
 */
public final class AccountPickerDialog extends DialogWrapper {

    private final LockManager lockManager;
    private final DefaultListModel<VaultAccount> model = new DefaultListModel<>();
    private final JBList<VaultAccount> list = new JBList<>(model);
    private final SearchTextField search = new SearchTextField();

    private AccountPickerDialog(@Nullable Project project, @NotNull LockManager lockManager) {
        super(project, true);
        this.lockManager = lockManager;
        setTitle("Choose Credential");
        setOKButtonText("Use");
        init();
        reload();
    }

    /**
     * Prompt the user to pick a credential from the vault. If the vault is
     * locked, transparently runs {@link UnlockDialog} first.
     *
     * @return the selected account, or {@code null} if the user cancelled
     *         either the unlock or the picker.
     */
    public static @Nullable VaultAccount show(@Nullable Project project, @NotNull LockManager lockManager) {
        if (lockManager.isLocked()) {
            UnlockDialog unlock = new UnlockDialog(project, lockManager);
            if (!unlock.showAndGet()) return null;
        }
        AccountPickerDialog dlg = new AccountPickerDialog(project, lockManager);
        if (!dlg.showAndGet()) return null;
        return dlg.list.getSelectedValue();
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(440, 320));
        panel.add(search, BorderLayout.NORTH);
        panel.add(new JBScrollPane(list), BorderLayout.CENTER);

        list.setCellRenderer(new VaultAccountCellRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) {
                    doOKAction();
                }
            }
        });

        search.addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent e) { reload(); }
        });

        return panel;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return search;
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
}
