package com.termlab.core.notifications;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.SystemNotifications;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Routes TermLab-owned notifications to an IDE balloon while the app is active
 * and to the OS native notification center while it is backgrounded.
 */
public final class TermLabNotifier {
    private static final String SYSTEM_NOTIFICATION_NAME = "TermLab";

    private TermLabNotifier() {
    }

    public static void notify(
        @Nullable Project project,
        @NotNull String groupId,
        @NotNull String title,
        @NotNull String content,
        @NotNull NotificationType type
    ) {
        Notification notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(groupId)
            .createNotification(title, content, type);
        notify(project, notification);
    }

    public static void notify(@Nullable Project project, @NotNull Notification notification) {
        if (ApplicationManager.getApplication().isActive()) {
            notification.notify(project);
            return;
        }

        SystemNotifications.getInstance().notify(
            SYSTEM_NOTIFICATION_NAME,
            sanitize(notification.getTitle()),
            sanitize(notification.getContent())
        );
    }

    private static @NotNull String sanitize(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return StringUtil.convertLineSeparators(StringUtil.stripHtml(text, true)).trim();
    }
}
