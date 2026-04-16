package com.termlab.core.actions;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Provides TermLab's preferred default shortcut for Distraction Free Mode
 * without overriding the platform's built-in action registration.
 */
public final class ToggleDistractionFreeModeShortcutAction extends AnAction {

    private static final String PLATFORM_ACTION_ID = "ToggleDistractionFreeMode";

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        AnAction delegate = ActionManager.getInstance().getAction(PLATFORM_ACTION_ID);
        if (delegate == null) {
            return;
        }
        ActionUtil.invokeAction(delegate, event.getDataContext(), ActionPlaces.MAIN_MENU, event.getInputEvent(), null);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(true);
        event.getPresentation().setVisible(false);
    }
}
