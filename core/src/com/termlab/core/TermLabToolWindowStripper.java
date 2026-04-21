package com.termlab.core;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Project-level listener that strips IDE-oriented tool windows that
 * register <b>dynamically</b> — after the app frame is created, usually
 * from service constructors that fire on project open. Handles the
 * same class of unwanted tool windows as
 * {@link TermLabToolWindowCustomizer}, but for tool windows that aren't
 * in the static {@code <toolWindow>} extension point.
 *
 * <p>Known dynamically-registered tool windows in the IntelliJ platform:
 * <ul>
 *   <li><b>Run</b> — registered by {@code RunContentManagerImpl} when
 *       a run configuration is executed, or eagerly by certain executor
 *       registration hooks.</li>
 *   <li><b>Debug</b> — similar to Run, via {@code DebugExecutor}. TermLab
 *       already stubs the Debug action via {@code NoopExecutorStubAction}
 *       but the tool window may still register.</li>
 *   <li><b>Hierarchy</b> — registered by
 *       {@code HierarchyBrowserManager}'s constructor when it's
 *       first instantiated as a project service.</li>
 *   <li><b>Services</b> — registered by
 *       {@code ServiceViewManagerImpl.registerToolWindows()} for
 *       managing running services.</li>
 * </ul>
 *
 * <p>The listener fires on {@link #toolWindowRegistered(String)}, checks
 * the id against the unwanted set, and immediately calls
 * {@code ToolWindowManager.unregisterToolWindow(id)}. This is a
 * reactive strip — the tool window exists for a fraction of a second
 * before being removed, which is imperceptible to the user because it
 * happens before any UI paint.
 *
 * <p>Registered via {@code <projectListeners>} in core plugin.xml.
 */
public final class TermLabToolWindowStripper implements ToolWindowManagerListener {

    private static final Logger LOG = Logger.getInstance(TermLabToolWindowStripper.class);

    /**
     * Tool window IDs to strip on dynamic registration. Kept in sync
     * with the reasoning above — add new entries here when platform
     * modules we bundle register unwanted tool windows at runtime.
     */
    private static final Set<String> UNWANTED_IDS = Set.of(
        "Find",
        "Run",
        "Debug",
        "Hierarchy",
        "Services"
    );

    private final Project project;

    public TermLabToolWindowStripper(@NotNull Project project) {
        this.project = project;
        // Sweep anything already registered by the time we're constructed.
        // Services that register tool windows eagerly in their constructor
        // may have fired before the listener subscription wires up.
        sweepExisting();
    }

    @Override
    public void toolWindowRegistered(@NotNull String id) {
        if (UNWANTED_IDS.contains(id)) {
            stripToolWindow(id);
        }
    }

    private void sweepExisting() {
        ToolWindowManager manager = ToolWindowManager.getInstance(project);
        for (String id : UNWANTED_IDS) {
            ToolWindow tw = manager.getToolWindow(id);
            if (tw != null) {
                stripToolWindow(id);
            }
        }
    }

    private void stripToolWindow(@NotNull String id) {
        try {
            ToolWindowManager.getInstance(project).unregisterToolWindow(id);
            LOG.info("TermLab: stripped dynamic tool window '" + id + "'");
        } catch (Exception e) {
            // Defensive — if the tool window is already gone or the
            // manager is in a bad state, log and move on. The tool
            // window stripe button might appear briefly but the
            // next registration attempt will be caught.
            LOG.warn("TermLab: failed to strip tool window '" + id + "': " + e.getMessage());
        }
    }
}
