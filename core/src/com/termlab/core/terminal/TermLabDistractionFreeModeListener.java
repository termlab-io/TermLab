package com.termlab.core.terminal;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.AnActionResult;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.NotNull;

/**
 * Re-applies TermLab's tab policy after the platform toggles distraction free
 * mode, which otherwise forces editor tabs completely off.
 */
public final class TermLabDistractionFreeModeListener implements AnActionListener {

    @Override
    public void afterActionPerformed(@NotNull AnAction action,
                                     @NotNull AnActionEvent event,
                                     @NotNull AnActionResult result) {
        if (!"ToggleDistractionFreeMode".equals(event.getActionManager().getId(action))) {
            return;
        }
        Project project = event.getProject();
        if (project != null) {
            TermLabTabBarManager.applyPreferredTabSettings(project);
            return;
        }

        for (Project openProject : ProjectManager.getInstance().getOpenProjects()) {
            TermLabTabBarManager.applyPreferredTabSettings(openProject);
        }
    }
}
