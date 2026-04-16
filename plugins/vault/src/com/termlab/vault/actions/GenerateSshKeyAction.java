package com.termlab.vault.actions;

import com.termlab.vault.lock.LockManager;
import com.termlab.vault.ui.KeyGenDialog;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Opens {@link KeyGenDialog} so the user can generate a new SSH key
 * pair into the unlocked vault. Discoverable through the command
 * palette's Actions tab ("Generate SSH Key…").
 *
 * <p>Disabled when the vault is locked — key generation needs an
 * unlocked vault to store the resulting {@code VaultKey}.
 */
public final class GenerateSshKeyAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        if (lm == null || lm.isLocked()) return;
        Project project = e.getProject();
        new KeyGenDialog(project, lm).show();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        e.getPresentation().setEnabled(lm != null && !lm.isLocked());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
