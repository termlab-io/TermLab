package com.termlab.core.palette;

import com.intellij.codeWithMe.ClientId;
import com.intellij.ide.actions.SearchEverywhereAction;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.AnActionResult;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.wm.WindowManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.JFrame;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pins the Search Everywhere popup to the horizontal center + vertical
 * upper-half-center of the TermLab frame every time the user invokes a
 * {@link SearchEverywhereAction}. Reposition happens in
 * {@code afterActionPerformed}, after the platform has created and
 * shown the popup, by grabbing the live {@link JBPopup} from the
 * platform's public {@code SEARCH_EVERYWHERE_POPUP} user-data key and
 * calling {@code setLocation} directly.
 *
 * <p>Direct popup manipulation rather than {@code WindowStateService}
 * clearing: it's robust to whatever the platform computed for initial
 * placement and doesn't depend on the short-vs-full view-type gating
 * in {@code SearchEverywhereManagerImpl.calcPositionAndShow}.
 *
 * <p>The reposition runs under {@code invokeLater} so the popup has
 * finished its initial layout pass — setting location on a still-
 * validating popup can race with the platform's own placement call
 * and leave the window at the wrong coordinates.
 *
 * <p><b>Known unrelated issue:</b> the first {@code Cmd+Shift+P} press
 * after moving the TermLab window currently doesn't render the popup
 * (second press works). This is NOT caused by this listener — verified
 * by running TermLab with zero SE customizations — and predates this
 * work. Tracking as a follow-up; positional pinning is worth keeping
 * even with the double-press workaround.
 *
 * <p>Registered via {@code <applicationListener>} in core plugin.xml.
 */
public final class TermLabSearchEverywhereLocationPinner implements AnActionListener {

    @Override
    public void afterActionPerformed(@NotNull AnAction action,
                                      @NotNull AnActionEvent event,
                                      @NotNull AnActionResult result) {
        if (!(action instanceof SearchEverywhereAction)) return;
        if (!result.isPerformed()) return;

        Project project = event.getProject();
        if (project == null) return;

        ApplicationManager.getApplication().invokeLater(() -> repositionToUpperHalfCenter(project));
    }

    private static void repositionToUpperHalfCenter(@NotNull Project project) {
        JBPopup popup = findSearchEverywherePopup(project);
        if (popup == null || popup.isDisposed()) return;

        JFrame frame = WindowManager.getInstance().getFrame(project);
        if (frame == null) return;

        Rectangle bounds = frame.getBounds();
        Dimension size = popup.getSize();
        if (size == null || size.width <= 0 || size.height <= 0) return;

        // Horizontally centered on the frame, vertical center at
        // windowHeight/4 — the "command palette" look.
        int x = bounds.x + (bounds.width - size.width) / 2;
        int y = bounds.y + bounds.height / 4 - size.height / 2;
        popup.setLocation(new Point(x, y));
    }

    private static JBPopup findSearchEverywherePopup(@NotNull Project project) {
        ConcurrentHashMap<ClientId, JBPopup> map =
            project.getUserData(SearchEverywhereAction.SEARCH_EVERYWHERE_POPUP);
        if (map == null) return null;
        return map.get(ClientId.getCurrent());
    }
}
