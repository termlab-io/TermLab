package com.termlab.vault.actions;

import com.termlab.vault.lock.LockManager;
import com.termlab.vault.persistence.VaultFile;
import com.termlab.vault.ui.CreateVaultDialog;
import com.termlab.vault.ui.UnlockDialog;
import com.termlab.vault.ui.VaultDialog;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Action invoked by the {@code F8} keybinding (or "Open Vault" from
 * the Tools menu / palette). Routes the user through the right dialog based on current
 * vault state:
 *
 * <ul>
 *   <li>Vault file missing → {@link CreateVaultDialog}</li>
 *   <li>Vault locked      → {@link UnlockDialog}, then {@link VaultDialog} on success</li>
 *   <li>Vault unlocked    → {@link VaultDialog}</li>
 * </ul>
 */
public final class OpenVaultAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        if (lm == null) return;

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
}
