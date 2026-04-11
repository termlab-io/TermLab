package com.conch.core.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Focuses a neighboring editor split in an explicit direction.
 */
public abstract class DirectionalTerminalNavigationAction extends AnAction implements DumbAware {

    protected enum Direction {
        LEFT, RIGHT, UP, DOWN
    }

    protected abstract @NotNull Direction direction();

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(project);
        EditorWindow target = findTargetWindow(manager, direction());
        if (target != null) {
            target.setAsCurrentWindow(true);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean enabled = false;
        if (project != null) {
            FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(project);
            enabled = findTargetWindow(manager, direction()) != null;
        }
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    private static @Nullable EditorWindow findTargetWindow(@NotNull FileEditorManagerEx manager,
                                                           @NotNull Direction direction) {
        EditorWindow current = manager.getCurrentWindow();
        if (current == null) return null;

        return findAdjacentWindow(current, direction.name());
    }

    private static @Nullable EditorWindow findAdjacentWindow(@NotNull EditorWindow current, @NotNull String directionName) {
        try {
            Method adjacentMethod = null;
            for (Method method : current.getClass().getMethods()) {
                if (method.getName().startsWith("getAdjacentEditors") && method.getParameterCount() == 0) {
                    adjacentMethod = method;
                    break;
                }
            }
            if (adjacentMethod == null) return null;

            Object result = adjacentMethod.invoke(current);
            if (!(result instanceof Map<?, ?> adjacent)) return null;

            for (Map.Entry<?, ?> entry : adjacent.entrySet()) {
                Object key = entry.getKey();
                if (key != null && directionName.equals(key.toString()) && entry.getValue() instanceof EditorWindow window) {
                    return window;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
}
