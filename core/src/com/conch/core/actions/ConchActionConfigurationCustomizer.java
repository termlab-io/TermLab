package com.conch.core.actions;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer;
import org.jetbrains.annotations.NotNull;

/**
 * Compile-time product action pruning for Conch.
 * Strips file-oriented actions and tab menus so Conch stays terminal-only.
 */
public final class ConchActionConfigurationCustomizer implements ActionConfigurationCustomizer {

    private static final String[] UNWANTED_MAIN_MENU_IDS = {
        "GoToMenu",       // shown as Navigate
        "CodeMenu",
        "RefactoringMenu",
        "BuildMenu",
        "RunMenu",
        "AnalyzeMenu",
    };

    private static final String[] FILE_ORIENTED_ACTION_IDS = {
        "OpenFile",
        "WelcomeScreen.OpenDirectoryProject",
        "LightEditOpenFileInProject",
        "GotoFile",
        "RecentLocations",
        "RecentFilesFallback",
        "RecentChangedFilesFallback",
        "SwitcherFallback",
        "SwitcherForwardFallback",
        "SwitcherBackwardFallback",
        "SwitcherRecentEditedChangedToggleCheckBoxFallback",
        "SwitcherIterateItemsFallback",
        "DeleteRecentFilesFallback",
        "ConfigureEditorTabs",
        "NewFile",
        "NewDir",
        "NewScratchFile",
        "NewScratchBuffer",
        "Scratch.ChangeLanguage",
        "Scratch.ShowFilesPopup",
        "Scratch.ExportToScratch",
        "ProjectViewEditSource",
        "SelectInProjectView",
        "EditSource",
        "EditSourceNotInEditor",
        "EditSourceInNewWindow",
        "OpenElementInNewWindow",
        "OpenInRightSplit",
        "FileChooser.NewFile",
    };

    @Override
    @SuppressWarnings("removal")
    public void customize(@NotNull ActionManager actionManager) {
        replaceWithHiddenGroup(actionManager, "EditorTabsEntryPoint");
        replaceWithHiddenGroup(actionManager, "EditorTabPopupMenu");
        clearGroup(actionManager, "FileOpenGroup");
        for (String menuId : UNWANTED_MAIN_MENU_IDS) {
            replaceWithHiddenGroup(actionManager, menuId);
        }

        for (String actionId : FILE_ORIENTED_ACTION_IDS) {
            unregisterIfPresent(actionManager, actionId);
        }
    }

    private static void replaceWithHiddenGroup(@NotNull ActionManager actionManager, @NotNull String actionId) {
        AnAction existing = actionManager.getAction(actionId);
        if (existing instanceof ActionGroup) {
            actionManager.replaceAction(actionId, new HiddenActionGroup());
        }
    }

    private static void clearGroup(@NotNull ActionManager actionManager, @NotNull String groupId) {
        AnAction action = actionManager.getAction(groupId);
        if (action instanceof DefaultActionGroup group) {
            group.removeAll();
        }
    }

    private static void unregisterIfPresent(@NotNull ActionManager actionManager, @NotNull String actionId) {
        if (actionManager.getAction(actionId) != null) {
            actionManager.unregisterAction(actionId);
        }
    }

    private static final class HiddenActionGroup extends DefaultActionGroup {
        private HiddenActionGroup() {
            super("", true);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(false);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }
}
