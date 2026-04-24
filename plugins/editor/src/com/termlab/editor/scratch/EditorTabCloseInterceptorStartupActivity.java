package com.termlab.editor.scratch;

import com.intellij.ide.DataManager;
import com.intellij.ide.IdeEventQueue;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.tabs.JBTabs;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.util.ui.UIUtil;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Routes editor tab close-icon clicks through {@code closeFileWithChecks}.
 *
 * <p>The platform action path already runs {@link com.intellij.openapi.vfs.VirtualFilePreCloseCheck}
 * extensions, but the close icon dispatches directly to the unchecked close API.
 */
public final class EditorTabCloseInterceptorStartupActivity implements ProjectActivity {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (INSTALLED.compareAndSet(false, true)) {
            Toolkit.getDefaultToolkit().addAWTEventListener(
                new CloseClickListener(),
                AWTEvent.MOUSE_EVENT_MASK
            );
        }
        return Unit.INSTANCE;
    }

    private static final class CloseClickListener implements AWTEventListener {
        @Override
        public void eventDispatched(AWTEvent event) {
            if (!(event instanceof MouseEvent mouseEvent)) return;
            if (mouseEvent.getID() != MouseEvent.MOUSE_RELEASED) return;
            if (mouseEvent.isConsumed()) return;
            if (!UIUtil.isCloseClick(mouseEvent, MouseEvent.MOUSE_RELEASED)) return;
            if ((mouseEvent.getModifiersEx() & InputEvent.ALT_DOWN_MASK) != 0) return;

            Component component = mouseEvent.getComponent();
            JBTabs tabs = findTabs(component);
            if (tabs == null) return;

            TabInfo info = tabs.findInfo(mouseEvent);
            if (info == null || !(info.getObject() instanceof VirtualFile file)) return;

            DataContext dataContext = DataManager.getInstance().getDataContext(component);
            Project project = CommonDataKeys.PROJECT.getData(dataContext);
            if (project == null) {
                project = findProjectFor(file);
            }
            if (project == null || project.isDisposed()) return;

            FileEditorManagerEx manager = (FileEditorManagerEx)FileEditorManager.getInstance(project);
            EditorWindow window = findWindow(manager, EditorWindow.DATA_KEY.getData(dataContext), file);
            if (window == null) return;

            mouseEvent.consume();
            IdeEventQueue.getInstance().blockNextEvents(mouseEvent);
            manager.closeFileWithChecks(file, window);
        }
    }

    private static @Nullable JBTabs findTabs(@Nullable Component component) {
        Component current = component;
        while (current != null) {
            if (current instanceof JBTabs tabs) {
                return tabs;
            }
            current = current.getParent();
        }
        return null;
    }

    private static @Nullable EditorWindow findWindow(
        @NotNull FileEditorManagerEx manager,
        @Nullable EditorWindow preferred,
        @NotNull VirtualFile file
    ) {
        if (preferred != null && preferred.getComposite(file) != null) {
            return preferred;
        }
        for (EditorWindow window : manager.getWindows()) {
            if (window.getComposite(file) != null) {
                return window;
            }
        }
        return manager.getCurrentWindow();
    }

    private static @Nullable Project findProjectFor(@NotNull VirtualFile file) {
        Project fallback = null;
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;
            if (fallback == null) fallback = project;
            if (FileEditorManager.getInstance(project).isFileOpen(file)) {
                return project;
            }
        }
        return fallback;
    }
}
