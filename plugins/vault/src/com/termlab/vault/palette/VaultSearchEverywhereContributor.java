package com.termlab.vault.palette;

import com.termlab.vault.lock.LockManager;
import com.termlab.vault.model.Vault;
import com.termlab.vault.model.VaultAccount;
import com.termlab.vault.model.VaultKey;
import com.termlab.vault.ui.AccountEditDialog;
import com.termlab.vault.ui.KeyEditDialog;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.Component;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Exposes vault {@link VaultAccount}s and {@link VaultKey}s in the
 * TermLab command palette (Cmd+Shift+P → Vault tab). Selecting an
 * account opens {@link AccountEditDialog}; selecting a key opens
 * {@link KeyEditDialog}. The menu-style actions the dead
 * {@code VaultPaletteContributor} carried ("Lock Vault",
 * "Generate SSH Key…") are now real {@code AnAction}s and surface
 * through the Actions tab instead.
 *
 * <p>The tab holds both {@link VaultAccount} and {@link VaultKey}
 * values, so the generic type is {@link Object} and the renderer /
 * selection handler dispatch with {@code instanceof}.
 *
 * <p>When the vault is locked, {@link #fetchElements} returns
 * immediately without consuming anything — the tab is empty and the
 * user unlocks via {@code F8} or the "Open Vault" entry in
 * {@code Tools -> Credential Vault}.
 */
public final class VaultSearchEverywhereContributor implements SearchEverywhereContributor<Object> {

    private static final Logger LOG = Logger.getInstance(VaultSearchEverywhereContributor.class);

    private final Project project;

    public VaultSearchEverywhereContributor(@NotNull Project project) {
        this.project = project;
    }

    @Override public @NotNull String getSearchProviderId() { return "TermLabVault"; }
    @Override public @NotNull String getGroupName() { return "Vault"; }
    @Override public int getSortWeight() { return 60; }
    @Override public boolean showInFindResults() { return false; }
    @Override public boolean isShownInSeparateTab() { return true; }
    @Override public boolean isEmptyPatternSupported() { return true; }

    @Override
    public void fetchElements(@NotNull String pattern,
                               @NotNull ProgressIndicator progressIndicator,
                               @NotNull Processor<? super Object> consumer) {
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        if (lm == null) return;
        Vault vault = lm.getVault();
        if (vault == null) return;  // locked or not yet created

        String q = pattern.toLowerCase();

        List<VaultAccount> accountHits = vault.accounts.stream()
            .filter(a -> q.isEmpty() || matchesAccount(a, q))
            .sorted(Comparator.comparing(VaultAccount::displayName, String.CASE_INSENSITIVE_ORDER))
            .toList();
        for (VaultAccount a : accountHits) {
            if (progressIndicator.isCanceled()) return;
            if (!consumer.process(a)) return;
        }

        List<VaultKey> keyHits = vault.keys.stream()
            .filter(k -> q.isEmpty() || matchesKey(k, q))
            .sorted(Comparator.comparing(VaultKey::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
        for (VaultKey k : keyHits) {
            if (progressIndicator.isCanceled()) return;
            if (!consumer.process(k)) return;
        }
    }

    private static boolean matchesAccount(@NotNull VaultAccount a, @NotNull String q) {
        return a.displayName().toLowerCase().contains(q)
            || a.username().toLowerCase().contains(q);
    }

    private static boolean matchesKey(@NotNull VaultKey k, @NotNull String q) {
        return k.name().toLowerCase().contains(q)
            || k.algorithm().toLowerCase().contains(q);
    }

    @Override
    public boolean processSelectedItem(@NotNull Object selected, int modifiers, @NotNull String searchText) {
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        if (lm == null) return false;
        Vault vault = lm.getVault();
        if (vault == null) return false;

        if (selected instanceof VaultAccount account) {
            VaultAccount updated = AccountEditDialog.show(project, account);
            if (updated == null) return true;  // user cancelled — still close the palette
            vault.accounts.removeIf(a -> a.id().equals(account.id()));
            vault.accounts.add(updated);
            trySave(lm);
            return true;
        }
        if (selected instanceof VaultKey key) {
            VaultKey updated = KeyEditDialog.show(project, key);
            if (updated == null) return true;
            vault.keys.removeIf(k -> k.id().equals(key.id()));
            vault.keys.add(updated);
            trySave(lm);
            return true;
        }
        return false;
    }

    private static void trySave(@NotNull LockManager lm) {
        try {
            lm.save();
        } catch (Exception e) {
            // Palette callbacks can't reliably surface modal errors; log
            // and move on. Same rationale the dead VaultPaletteContributor
            // used, now called out explicitly.
            LOG.warn("TermLab vault: failed to save after palette edit", e);
        }
    }

    @Override
    public @NotNull ListCellRenderer<? super Object> getElementsRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focus
            ) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof VaultAccount a) {
                    setText(a.displayName() + "  \u2014  " + a.username());
                } else if (value instanceof VaultKey k) {
                    setText(k.name() + "  \u2014  " + k.algorithm() + "  \u00b7  " + k.fingerprint());
                }
                return this;
            }
        };
    }

    @Override
    public @Nullable Object getDataForItem(@NotNull Object element, @NotNull String dataId) {
        return Objects.equals(dataId, "com.termlab.vault.entry") ? element : null;
    }

    public static final class Factory implements SearchEverywhereContributorFactory<Object> {
        @Override
        public @NotNull SearchEverywhereContributor<Object> createContributor(@NotNull AnActionEvent initEvent) {
            Project project = Objects.requireNonNull(
                initEvent.getProject(),
                "Project required for VaultSearchEverywhereContributor");
            return new VaultSearchEverywhereContributor(project);
        }
    }
}
