package com.termlab.vault.actions;

import com.termlab.vault.lock.LockManager;
import com.termlab.vault.persistence.VaultFile;
import com.termlab.vault.ui.UnlockDialog;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Toggle-style vault lock action. When unlocked it locks the vault;
 * when locked it prompts to unlock.
 */
public final class LockVaultAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        if (lm == null) return;

        if (lm.isLocked()) {
            if (!VaultFile.exists(lm.getVaultPath())) {
                return;
            }
            new UnlockDialog(project, lm).showAndGet();
            return;
        }

        lm.lock();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        boolean hasVault = lm != null && VaultFile.exists(lm.getVaultPath());
        boolean locked = lm == null || lm.isLocked();
        e.getPresentation().setText(locked ? "Unlock Vault…" : "Lock Vault");
        e.getPresentation().setDescription(locked
            ? "Unlock the TermLab credential vault"
            : "Lock the TermLab credential vault");
        e.getPresentation().setEnabled(lm != null && (!locked || hasVault));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
