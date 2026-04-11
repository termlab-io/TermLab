package com.conch.vault.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for {@link VaultLockStatusBarWidget}. Registered under the
 * {@code com.intellij.statusBarWidgetFactory} extension point in
 * {@code plugin.xml}. The ID here must match
 * {@link VaultLockStatusBarWidget#ID}.
 */
public final class VaultLockStatusBarWidgetFactory implements StatusBarWidgetFactory {

    @Override
    public @NotNull String getId() {
        return VaultLockStatusBarWidget.ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Conch Vault Lock";
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new VaultLockStatusBarWidget(project);
    }
}
