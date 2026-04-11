package com.conch.vault.palette;

import com.conch.sdk.CommandPaletteContributor;
import com.conch.sdk.PaletteItem;
import com.conch.vault.lock.LockManager;
import com.conch.vault.model.Vault;
import com.conch.vault.model.VaultAccount;
import com.conch.vault.model.VaultKey;
import com.conch.vault.persistence.VaultFile;
import com.conch.vault.ui.AccountEditDialog;
import com.conch.vault.ui.CreateVaultDialog;
import com.conch.vault.ui.KeyEditDialog;
import com.conch.vault.ui.KeyGenDialog;
import com.conch.vault.ui.UnlockDialog;
import com.conch.vault.ui.VaultDialog;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Command palette contributor for the vault. Registered under the
 * {@code com.conch.core.commandPaletteContributor} extension point.
 *
 * <p>The palette shows, in order:
 * <ul>
 *   <li>Every account in the unlocked vault — selecting one opens the edit
 *       dialog for it. (Hidden when the vault is locked; the user must
 *       unlock first via the status-bar icon or Cmd+Shift+V.)</li>
 *   <li>"Generate SSH Key…" — opens {@link KeyGenDialog}. Only when unlocked.</li>
 *   <li>"Open Vault" — routes to the right dialog based on current state
 *       (create / unlock / manage).</li>
 *   <li>"Lock Vault" — only when unlocked.</li>
 * </ul>
 *
 * <p>Enumeration is the vault plugin's own concern, not the SDK's — the
 * {@code CredentialProvider} interface intentionally has no {@code listAll}
 * method. This contributor reads directly from {@link LockManager}.
 */
public final class VaultPaletteContributor implements CommandPaletteContributor {

    @Override
    public @NotNull String getTabName() {
        return "Vault";
    }

    @Override
    public int getTabWeight() {
        return 60;
    }

    @Override
    public @NotNull List<PaletteItem> search(@NotNull String query) {
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        if (lm == null) return List.of();

        List<PaletteItem> items = new ArrayList<>();
        String q = query.toLowerCase();

        // Account and key entries (only when unlocked).
        Vault vault = lm.getVault();
        if (vault != null) {
            vault.accounts.stream()
                .filter(a -> q.isEmpty()
                    || a.displayName().toLowerCase().contains(q)
                    || a.username().toLowerCase().contains(q))
                .sorted(Comparator.comparing(VaultAccount::displayName, String.CASE_INSENSITIVE_ORDER))
                .forEach(a -> items.add(accountItem(a, lm)));

            vault.keys.stream()
                .filter(k -> q.isEmpty()
                    || k.name().toLowerCase().contains(q)
                    || k.algorithm().toLowerCase().contains(q))
                .sorted(Comparator.comparing(VaultKey::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(k -> items.add(keyItem(k, lm)));
        }

        // Actions. Always include Open Vault so the user can reach it from
        // the palette even when locked; include unlock-dependent actions
        // only when the vault is actually unlocked.
        boolean unlocked = !lm.isLocked();

        if (unlocked && matches(q, "generate ssh key")) {
            items.add(new PaletteItem(
                "vault.action.generate-ssh-key",
                "Generate SSH Key…",
                "Create a new Ed25519, ECDSA, or RSA key pair",
                null,
                () -> runOnEdt(() -> new KeyGenDialog(defaultProject(), lm).show())));
        }

        if (matches(q, "open vault")) {
            items.add(new PaletteItem(
                "vault.action.open",
                "Open Vault",
                unlocked ? "Manage accounts" : "Unlock to manage accounts",
                null,
                () -> runOnEdt(() -> openVault(lm))));
        }

        if (unlocked && matches(q, "lock vault")) {
            items.add(new PaletteItem(
                "vault.action.lock",
                "Lock Vault",
                "Seal the vault and zero in-memory credentials",
                null,
                lm::lock));
        }

        return items;
    }

    private PaletteItem accountItem(VaultAccount account, LockManager lm) {
        return new PaletteItem(
            "vault.account." + account.id(),
            account.displayName(),
            account.username(),
            null,
            () -> runOnEdt(() -> {
                Vault vault = lm.getVault();
                if (vault == null) return;
                VaultAccount updated = AccountEditDialog.show(defaultProject(), account);
                if (updated == null) return;
                vault.accounts.removeIf(a -> a.id().equals(account.id()));
                vault.accounts.add(updated);
                try {
                    lm.save();
                } catch (Exception ignored) {
                    // Palette handlers can't surface modal dialogs reliably;
                    // if save fails, the edit will be lost on lock.
                }
            }));
    }

    private PaletteItem keyItem(VaultKey key, LockManager lm) {
        return new PaletteItem(
            "vault.key." + key.id(),
            key.name(),
            key.algorithm() + "  ·  " + key.fingerprint(),
            null,
            () -> runOnEdt(() -> {
                Vault vault = lm.getVault();
                if (vault == null) return;
                VaultKey updated = KeyEditDialog.show(defaultProject(), key);
                if (updated == null) return;
                vault.keys.removeIf(k -> k.id().equals(key.id()));
                vault.keys.add(updated);
                try {
                    lm.save();
                } catch (Exception ignored) {
                    // See accountItem.
                }
            }));
    }

    private void openVault(LockManager lm) {
        Project project = defaultProject();
        if (!VaultFile.exists(lm.getVaultPath())) {
            if (new CreateVaultDialog(project).showAndGet()) {
                new VaultDialog(project, lm).show();
            }
            return;
        }
        if (lm.isLocked()) {
            if (new UnlockDialog(project, lm).showAndGet()) {
                new VaultDialog(project, lm).show();
            }
            return;
        }
        new VaultDialog(project, lm).show();
    }

    private static boolean matches(String query, String label) {
        return query.isEmpty() || label.toLowerCase().contains(query);
    }

    private static Project defaultProject() {
        return ProjectManager.getInstance().getDefaultProject();
    }

    private static void runOnEdt(@NotNull Runnable action) {
        ApplicationManager.getApplication().invokeLater(action);
    }
}
