package com.conch.core.actions;

import com.conch.core.miniwindow.MiniTerminalWindow;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

public final class OpenMiniWindowAction extends AnAction implements DumbAware {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        new MiniTerminalWindow().show();
    }
}
