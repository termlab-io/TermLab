package com.termlab.editor.guard;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Pre-open guards shared by the local and remote file openers. Refuses
 * blocked extensions and files larger than 5 MB before any IO happens.
 */
public final class OpenGuards {

    public static final long SIZE_CAP_BYTES = 5L * 1024 * 1024;
    private static final String NOTIFICATION_GROUP = "TermLab SFTP";

    private OpenGuards() {}

    public static boolean allow(@NotNull Project project, @NotNull String filename, long sizeBytes) {
        if (ExtensionBlocklist.isBlocked(filename)) {
            notify(project, "Cannot edit " + filename + ": binary file type.");
            return false;
        }
        if (sizeBytes > SIZE_CAP_BYTES) {
            notify(project, "File too large (" + formatMb(sizeBytes) + " MB). Maximum editable size is 5 MB.");
            return false;
        }
        return true;
    }

    private static String formatMb(long bytes) {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }

    private static void notify(@NotNull Project project, @NotNull String message) {
        Notifications.Bus.notify(
            new Notification(NOTIFICATION_GROUP, "TermLab Editor", message, NotificationType.ERROR),
            project);
    }
}
