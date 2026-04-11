package com.conch.vault.actions;

import com.conch.vault.lock.LockManager;
import com.conch.vault.persistence.VaultFile;
import com.conch.vault.ui.CreateVaultDialog;
import com.conch.vault.ui.UnlockDialog;
import com.conch.vault.ui.VaultDialog;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Action invoked by the {@code Cmd+Shift+V} keybinding (or "Open Vault" from
 * the palette). Routes the user through the right dialog based on current
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
