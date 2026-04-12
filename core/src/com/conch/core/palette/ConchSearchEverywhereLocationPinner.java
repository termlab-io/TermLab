package com.conch.core.palette;

import com.intellij.ide.actions.SearchEverywhereAction;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManagerImpl;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.WindowStateService;
import org.jetbrains.annotations.NotNull;

/**
 * Application-level listener that wipes Search Everywhere's persisted
 * popup location before each {@link SearchEverywhereAction} invocation.
 *
 * <p>With the saved location cleared, {@code SearchEverywhereManagerImpl.
 * calcPositionAndShow} sees {@code savedLocation == null} and falls into
 * its fresh-install code path, which computes a horizontally-centered
 * position with the popup's vertical center at {@code windowHeight / 4}
 * — the "center of the upper half" look users expect from a command
 * palette. No subclassing, no reflection, no duplicated position math
 * in Conch.
 *
 * <p>There is explicit precedent for this {@code putLocation(key, null)}
 * pattern in the platform itself: see
 * {@code SearchEverywhereRiderMainToolbarAction.beforeActionPerformed}.
 *
 * <p>Registered via {@code <applicationListener>} in the core plugin
 * XML, subscribing to {@link AnActionListener#TOPIC}.
 */
public final class ConchSearchEverywhereLocationPinner implements AnActionListener {

    @Override
    public void beforeActionPerformed(@NotNull AnAction action, @NotNull AnActionEvent event) {
        if (!(action instanceof SearchEverywhereAction)) return;
        Project project = event.getProject();
        if (project == null) return;
        WindowStateService.getInstance(project)
            .putLocation(SearchEverywhereManagerImpl.LOCATION_SETTINGS_KEY, null);
    }
}
