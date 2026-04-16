package com.termlab.vault.ui;

import com.termlab.vault.lock.LockManager;
import com.termlab.vault.lock.VaultState;
import com.termlab.vault.lock.VaultStateListener;
import com.termlab.vault.persistence.VaultFile;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.MouseEvent;

/**
 * Status bar widget that shows the vault's lock state and opens the vault
 * UI on click.
 *
 * <ul>
 *   <li>🔒 (locked): click → {@link UnlockDialog} or {@link CreateVaultDialog}</li>
 *   <li>🔓 (unlocked): click → {@link VaultDialog}</li>
 * </ul>
 *
 * Subscribes to {@link LockManager}'s {@link VaultStateListener} so the icon
 * updates instantly when another action unlocks or locks the vault.
 */
public final class VaultLockStatusBarWidget implements StatusBarWidget, StatusBarWidget.IconPresentation {

    public static final String ID = "com.termlab.vault.lockWidget";

    private final Project project;
    private final LockManager lockManager;
    private final VaultStateListener listener;
    private @Nullable StatusBar statusBar;

    VaultLockStatusBarWidget(@NotNull Project project) {
        this.project = project;
        this.lockManager = ApplicationManager.getApplication().getService(LockManager.class);
        this.listener = state -> {
            if (statusBar != null) statusBar.updateWidget(ID);
        };
        lockManager.addListener(listener);
    }

    @Override
    public @NotNull String ID() {
        return ID;
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
    }

    @Override
    public void dispose() {
        lockManager.removeListener(listener);
    }

    @Override
    public @Nullable Icon getIcon() {
        return lockManager.isLocked()
            ? AllIcons.Ide.Readonly    // closed padlock
            : AllIcons.Ide.Readwrite;  // open padlock
    }

    @Override
    public @Nullable String getTooltipText() {
        VaultState state = lockManager.getState();
        return switch (state) {
            case LOCKED -> "Credential Vault — locked. Click to unlock.";
            case UNLOCKING -> "Credential Vault — unlocking…";
            case UNLOCKED -> "Credential Vault — unlocked. Click to manage.";
            case SEALING -> "Credential Vault — locking…";
        };
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return event -> handleClick();
    }

    private void handleClick() {
        if (lockManager.isLocked()) {
            if (!VaultFile.exists(lockManager.getVaultPath())) {
                new CreateVaultDialog(project).showAndGet();
                return;
            }
            if (new UnlockDialog(project, lockManager).showAndGet()) {
                new VaultDialog(project, lockManager).show();
            }
            return;
        }
        new VaultDialog(project, lockManager).show();
    }
}
