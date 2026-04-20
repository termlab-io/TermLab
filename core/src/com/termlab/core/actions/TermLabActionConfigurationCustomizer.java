package com.termlab.core.actions;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer;
import org.jetbrains.annotations.NotNull;

/**
 * Compile-time product action pruning for TermLab.
 * Strips file-oriented actions and tab menus so TermLab stays terminal-only.
 */
public final class TermLabActionConfigurationCustomizer implements ActionConfigurationCustomizer {

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

    /**
     * Hierarchy and run-configuration actions. TermLab is a terminal
     * workstation — no source code hierarchies, no run configurations.
     * These would show up in the Actions tab of the command palette if
     * left registered.
     */
    private static final String[] IDE_CENTRIC_ACTION_IDS = {
        // Hierarchy
        "TypeHierarchy",
        "MethodHierarchy",
        "CallHierarchy",
        "TypeHierarchy.Class",
        "TypeHierarchy.Subtypes",
        "TypeHierarchy.Supertypes",
        "TypeHierarchyBase.BaseOnThisType",
        "MethodHierarchy.BaseOnThisMethod",
        "CallHierarchy.BaseOnThisMethod",
        // Run/Debug configuration
        "ChooseRunConfiguration",
        "editRunConfigurations",
        "CreateRunConfiguration",
        "SaveTemporaryRunConfiguration",
        "DeleteRunConfiguration",
        "ShowLiveRunConfigurations",
        "AllRunConfigurationsToggle",
        // Run Anything
        "RunAnything",
        // Run toolbar (new UI)
        "NewUiRunWidget",
        "RunToolbarMainActionGroup",
        "MoreRunToolbarActions",
        // Activate tool window actions (even if the window itself is
        // stripped, the action can appear in the palette)
        "ActivateRunToolWindow",
        "ActivateDebugToolWindow",
        "ActivateHierarchyToolWindow",
        "ActivateServicesToolWindow",
        "ActivateBookmarksToolWindow",
        // Debugger actions. These action ids are still registered from
        // platform action XML even though TermLab excludes the debugger
        // implementation modules, so command-palette updates can probe
        // missing debugger extension points unless we strip them here.
        "Resume",
        "Pause",
        "StepOver",
        "StepInto",
        "ForceStepInto",
        "SmartStepInto",
        "StepOut",
        "RunToCursor",
        "ForceRunToCursor",
        "EvaluateExpression",
        "QuickEvaluateExpression",
        "ShowExecutionPoint",
        "ToggleLineBreakpoint",
        "ViewBreakpoints",
        // Bookmarks
        "AddAnotherBookmark",
        "EditBookmark",
        "ToggleBookmark",
        "ToggleBookmarkWithMnemonic",
        "DeleteMnemonicFromBookmark",
        "BookmarkOpenTabs",
        "ShowBookmarks",
        "ShowTypeBookmarks",
        "GotoNextBookmark",
        "GotoNextBookmarkInEditor",
        "GotoPreviousBookmark",
        "GotoPreviousBookmarkInEditor",
        "BookmarksView.DefaultGroup",
        "BookmarksView.Rename",
        "BookmarksView.Delete",
        "BookmarksView.DeleteType",
        "BookmarksView.ChooseType",
        "BookmarksView.MoveUp",
        "BookmarksView.MoveDown",
        "BookmarksView.SortGroupBookmarks",
        "OpenBookmarkGroup",
        "BookmarksView.Create",
        "BookmarksView.ShowPreview",
        "BookmarksView.GroupLineBookmarks",
        "BookmarksView.RewriteBookmarkType",
        "BookmarksView.AskBeforeDeletingLists",
        "BookmarksView.OpenInPreviewTab",
        "BookmarksView.AutoscrollToSource",
        "BookmarksView.AutoscrollFromSource",
    };

    /**
     * Action groups that should be hidden entirely. These wrap related
     * IDE-centric actions into menus/popups that have no place in TermLab.
     */
    private static final String[] IDE_CENTRIC_GROUP_IDS = {
        "HierarchyGroup",
        "TypeHierarchyPopupMenu",
        "MethodHierarchyPopupMenu",
        "CallHierarchyPopupMenu",
        "RunContextGroup",
        "RunConfigurationsActionGroup",
        "Bookmarks",
        "Bookmarks.Goto",
        "Bookmarks.Toggle",
        "Bookmarks.ToolWindow.PopupMenu",
        "Bookmarks.ToolWindow.TitleActions",
        "Bookmarks.ToolWindow.GearActions",
        "popup@BookmarkContextMenu",
        "popup@ExpandableBookmarkContextMenu",
        "BookmarkOpenTabsGroup",
        "EditBookmarksGroup",
    };

    /**
     * Actions whose implementation classes live in modules TermLab strips
     * (e.g. test framework modules). Their {@code <action>} declarations
     * are loaded via PlatformActions.xml / ExecutionActions.xml, but the
     * referenced classes aren't on the classpath. Left alone, the Action
     * Search contributor hits PluginException/ClassNotFoundException the
     * first time it tries to lazy-load them for the palette. Unregistering
     * at customize time stops Action Search from ever seeing them.
     *
     * <p>These get unregistered with the same mechanism as the file-oriented
     * ones but listed separately so it's obvious why they're here.
     */
    private static final String[] MISSING_CLASS_ACTION_IDS = {
        // From PlatformActions.xml / ExecutionActions.xml — testframework
        // impls that aren't in our module set.
        "openAssertEqualsDiff",
        "ExportTestResults",
    };

    @Override
    @SuppressWarnings("removal")
    public void customize(@NotNull ActionManager actionManager) {
        replaceWithHiddenGroup(actionManager, "EditorTabsEntryPoint");

        // Keep EditorTabPopupMenu functional — just replace its contents with
        // TermLab-only items. Clear the inherited file-oriented children, then
        // explicitly re-add our own. We do this programmatically rather than
        // via plugin.xml <add-to-group> because clearGroup() runs after
        // add-to-group processing, which would otherwise wipe our own entries
        // along with the platform ones.
        clearGroup(actionManager, "EditorTabPopupMenu");
        addToGroupIfPresent(actionManager, "EditorTabPopupMenu", "TermLab.RenameTerminalTab");

        clearGroup(actionManager, "FileOpenGroup");
        for (String menuId : UNWANTED_MAIN_MENU_IDS) {
            replaceWithHiddenGroup(actionManager, menuId);
        }

        for (String actionId : FILE_ORIENTED_ACTION_IDS) {
            unregisterIfPresent(actionManager, actionId);
        }
        for (String actionId : IDE_CENTRIC_ACTION_IDS) {
            unregisterIfPresent(actionManager, actionId);
        }
        unregisterBookmarkMnemonicActions(actionManager, "ToggleBookmark");
        unregisterBookmarkMnemonicActions(actionManager, "GotoBookmark");
        for (String groupId : IDE_CENTRIC_GROUP_IDS) {
            replaceWithHiddenGroup(actionManager, groupId);
        }
        for (String actionId : MISSING_CLASS_ACTION_IDS) {
            unregisterIfPresent(actionManager, actionId);
        }
    }

    private static void addToGroupIfPresent(@NotNull ActionManager actionManager,
                                             @NotNull String groupId,
                                             @NotNull String actionId) {
        AnAction group = actionManager.getAction(groupId);
        if (!(group instanceof DefaultActionGroup dag)) return;
        AnAction action = actionManager.getAction(actionId);
        if (action != null) {
            // IMPORTANT: use the overload that takes ActionManager explicitly.
            // The no-arg DefaultActionGroup.add() calls ActionManager.getInstance()
            // internally, which re-enters service lookup — and we're INSIDE
            // ActionManagerImpl's constructor here, so that re-entry trips a
            // CycleInitializationException. Passing the already-available
            // actionManager instance bypasses the re-entry.
            dag.add(action, actionManager);
        }
    }

    private static void replaceWithHiddenGroup(@NotNull ActionManager actionManager, @NotNull String actionId) {
        AnAction existing = actionManager.getAction(actionId);
        if (existing instanceof ActionGroup) {
            actionManager.replaceAction(actionId, new HiddenActionGroup());
        }
    }

    private static void clearGroup(@NotNull ActionManager actionManager, @NotNull String groupId) {
        // Use getAction (not getActionOrStub) so the group's stub resolves to
        // a real DefaultActionGroup instance — otherwise the instanceof check
        // below fails and the clear is a no-op. Safe for groups because their
        // implementation classes are platform types (DefaultActionGroup and
        // friends), never anything from a stripped module.
        AnAction action = actionManager.getAction(groupId);
        if (action instanceof DefaultActionGroup group) {
            group.removeAll();
        }
    }

    private static void unregisterIfPresent(@NotNull ActionManager actionManager, @NotNull String actionId) {
        // IMPORTANT: use getActionOrStub, NOT getAction. getAction forces the
        // stub to instantiate its implementation class, which crashes with
        // PluginException / ClassNotFoundException for actions whose classes
        // live in modules TermLab strips (e.g. testframework). getActionOrStub
        // returns the stub without loading the class, so we can unregister
        // freely.
        if (actionManager.getActionOrStub(actionId) != null) {
            actionManager.unregisterAction(actionId);
        }
    }

    private static void unregisterBookmarkMnemonicActions(@NotNull ActionManager actionManager,
                                                          @NotNull String prefix) {
        for (char c = '0'; c <= '9'; c++) {
            unregisterIfPresent(actionManager, prefix + c);
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            unregisterIfPresent(actionManager, prefix + c);
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
