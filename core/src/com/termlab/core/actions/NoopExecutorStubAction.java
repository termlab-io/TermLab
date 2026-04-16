package com.termlab.core.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

/**
 * No-op stub registered with {@code id="Debug"} in the TermLab core plugin.xml
 * to satisfy lookups from platform code that assumes every IntelliJ product
 * ships a debugger.
 *
 * <p>Background: {@code RunToolbarTopLevelExecutorActionGroup.getChildren()}
 * (in {@code RedesignedRunWidget.kt}) unconditionally calls:
 * <pre>
 *   arrayOf(
 *     actionManager.getAction("Run"),
 *     actionManager.getAction("Debug")
 *   )
 * </pre>
 * The {@code "Debug"} action is registered programmatically by
 * {@code DefaultDebugExecutor} as part of the xdebugger-impl plugin.xml.
 * TermLab's stripped module set (via {@code essential.minimal}) excludes the
 * xdebugger modules, so {@code DefaultDebugExecutor} never loads and the
 * {@code getAction} lookup returns null, causing an NPE at startup that
 * slowed down the run toolbar initialization.
 *
 * <p>Registering this stub ensures the toolbar gets a non-null action to
 * put in its array. The action is always hidden and never executes
 * anything — TermLab has no real debugger.
 */
public final class NoopExecutorStubAction extends AnAction {

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Hide and disable — TermLab has no debugger, so don't pretend otherwise.
        e.getPresentation().setEnabledAndVisible(false);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // Intentionally empty.
    }
}
