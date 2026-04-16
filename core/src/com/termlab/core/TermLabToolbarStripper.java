package com.termlab.core;

import com.termlab.core.actions.NoopExecutorStubAction;
import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Strips main-toolbar and top-level run-menu actions that have no
 * place in a terminal workstation.
 *
 * <p>TermLab's main frame is the terminal tool window — there is no
 * editor, no run/debug lifecycle, no navigation history, and
 * nothing that needs saving or refreshing. The classic-UI
 * {@code MainToolBar} and the {@code Run} / {@code Build} top-level
 * menus nonetheless drag along a full complement of IDE buttons
 * (Save All, Synchronize, Back / Forward, Run, Stop, Add
 * Configuration, etc.) because TermLab inherits them from the
 * platform's {@code PlatformActions.xml} and {@code ExecutionActions.xml}.
 *
 * <p>This listener unregisters those actions at {@code appFrameCreated},
 * which removes them from the toolbar, the menu bar, Search
 * Everywhere, and any keymap binding in a single step — same
 * pattern as {@link TermLabRefactoringStripper} takes for the
 * refactoring menu.
 *
 * <h2>{@code Run}, {@code Stop}, {@code Debug}</h2>
 *
 * A handful of platform code paths (notably
 * {@code RunToolbarTopLevelExecutorActionGroup} in the New UI's
 * run widget) unconditionally {@code ActionManager.getAction("Run")}
 * / {@code getAction("Stop")} / {@code getAction("Debug")} and NPE
 * on null. TermLab uses Classic UI so most of those paths don't
 * activate, but to be defensive we don't fully unregister those
 * three — instead we replace them with invisible {@link NoopExecutorStubAction}
 * instances, same approach the existing {@code id="Debug"}
 * declarative stub takes in {@code plugin.xml}. The stubs are
 * always hidden and never execute anything, so the user sees
 * nothing and Search Everywhere finds nothing, but lookups return
 * a non-null {@link AnAction}.
 *
 * <p>Action ids sourced from
 * {@code platform/platform-resources/src/idea/PlatformActions.xml}
 * (the {@code MainToolBar} group) and
 * {@code platform/platform-resources/src/idea/ExecutionActions.xml}
 * (the {@code ToolbarRunGroup} / {@code RunMenu} / {@code BuildMenu}
 * groups).
 */
public final class TermLabToolbarStripper implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(TermLabToolbarStripper.class);

    /**
     * Actions to unregister outright. Safe because no known platform
     * code looks these up by ID from classic-UI code paths.
     */
    private static final List<String> UNWANTED_ACTION_IDS = List.of(
        // Classic MainToolBar children.
        "SaveAll",
        "Synchronize",
        "Back",
        "Forward",

        // ToolbarRunGroup children + the group itself.
        "ToolbarRunGroup",
        "RunConfiguration",
        "RunnerActions",
        "Rerun",

        // Run-config dropdown + its pickers.
        "editRunConfigurations",
        "ChooseRunConfiguration",
        "ChooseDebugConfiguration",
        "ManageTargets",

        // Top-level Run / Build menus and the Debug top-level group.
        "RunMenu",
        "BuildMenu",
        "DebugMainMenu",
        "DebuggingActionsGroup",
        "BreakpointActionsGroup"
    );

    /**
     * Actions we replace with invisible stubs rather than fully
     * unregistering, because at least one platform code path looks
     * them up by ID.
     */
    private static final List<String> STUBBED_ACTION_IDS = List.of(
        "Run",
        "Stop"
        // "Debug" is already stubbed declaratively via plugin.xml.
    );

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        ActionManager actionManager = ActionManager.getInstance();

        int removed = 0;
        for (String id : UNWANTED_ACTION_IDS) {
            if (actionManager.getAction(id) == null) continue;
            actionManager.unregisterAction(id);
            removed++;
            LOG.info("TermLab: unregistered toolbar action '" + id + "'");
        }

        int stubbed = 0;
        for (String id : STUBBED_ACTION_IDS) {
            AnAction existing = actionManager.getAction(id);
            if (existing == null) continue;
            actionManager.unregisterAction(id);
            actionManager.registerAction(id, new NoopExecutorStubAction());
            stubbed++;
            LOG.info("TermLab: replaced '" + id + "' with NoopExecutorStubAction");
        }

        LOG.info("TermLab: stripped " + removed + " toolbar action(s), stubbed "
            + stubbed + " executor action(s)");
    }
}
