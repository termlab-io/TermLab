package com.termlab.sftp.transfer;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Balloon-style status notifications emitted at the end of every
 * SFTP transfer batch. Registered in {@code plugin.xml} under the
 * {@value #GROUP_ID} id.
 */
public final class TransferNotifications {

    public static final String GROUP_ID = "SFTP";

    private TransferNotifications() {
    }

    public static void success(
        @Nullable Project project,
        @NotNull String title,
        @NotNull TransferResult result
    ) {
        if (result.filesTransferred() == 0) {
            // A "success" with zero bytes copied usually means every
            // candidate was either a symlink or Skip-All'd. Surface
            // that as a warning so the user notices.
            fire(project, title, "No files were transferred.", NotificationType.WARNING);
            return;
        }
        fire(project, title, formatSummary(result), NotificationType.INFORMATION);
    }

    public static void cancelled(
        @Nullable Project project,
        @NotNull String title,
        @NotNull TransferResult result
    ) {
        String content = "Cancelled by user." + (result.filesTransferred() == 0
            ? ""
            : "\n" + formatSummary(result));
        fire(project, title, content, NotificationType.WARNING);
    }

    public static void failed(
        @Nullable Project project,
        @NotNull String title,
        @NotNull TransferResult result,
        @NotNull Throwable cause
    ) {
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        StringBuilder content = new StringBuilder(message);
        if (result.filesTransferred() > 0) {
            content.append("\n").append(formatSummary(result));
        }
        fire(project, title, content.toString(), NotificationType.ERROR);
    }

    // -- internals ------------------------------------------------------------

    private static void fire(
        @Nullable Project project,
        @NotNull String title,
        @NotNull String content,
        @NotNull NotificationType type
    ) {
        Notification notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type);
        notification.notify(project);
    }

    private static @NotNull String formatSummary(@NotNull TransferResult result) {
        double seconds = result.elapsedNanos() / 1_000_000_000.0;
        long bps = seconds > 0 ? (long) (result.bytesTransferred() / seconds) : 0L;
        return result.filesTransferred() + " "
            + pluralize("file", result.filesTransferred())
            + " • " + formatBytes(result.bytesTransferred())
            + " in " + formatDuration(seconds)
            + " (" + formatBytes(bps) + "/s)";
    }

    private static @NotNull String pluralize(@NotNull String word, int count) {
        return count == 1 ? word : word + "s";
    }

    private static @NotNull String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static @NotNull String formatDuration(double seconds) {
        if (seconds < 1.0) return String.format("%.1fs", seconds);
        long whole = (long) seconds;
        if (whole < 60) return whole + "s";
        if (whole < 3600) return (whole / 60) + "m " + (whole % 60) + "s";
        return (whole / 3600) + "h " + ((whole % 3600) / 60) + "m";
    }
}
