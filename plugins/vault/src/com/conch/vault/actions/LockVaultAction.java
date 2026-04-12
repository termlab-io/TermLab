package com.conch.vault.actions;

import com.conch.vault.lock.LockManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

/**
 * Locks the Conch credential vault — seals the in-memory decrypted
 * state and drops cached credentials. Discoverable through the
 * command palette's Actions tab ("Lock Vault"). No keyboard shortcut
 * by default; users who want one can bind it via IntelliJ's keymap
 * settings.
 *
 * <p>Disabled when no vault is unlocked to begin with.
 */
public final class LockVaultAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        if (lm == null || lm.isLocked()) return;
        lm.lock();
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
