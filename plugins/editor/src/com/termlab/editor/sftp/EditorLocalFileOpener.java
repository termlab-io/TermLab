package com.termlab.editor.sftp;

import com.termlab.editor.guard.OpenGuards;
import com.termlab.sftp.model.LocalFileEntry;
import com.termlab.sftp.spi.LocalFileOpener;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public final class EditorLocalFileOpener implements LocalFileOpener {

    private static final String NOTIFICATION_GROUP = "SFTP";

    @Override
    public void open(@NotNull Project project, @NotNull LocalFileEntry entry) {
        if (!OpenGuards.allow(project, entry.name(), entry.size())) return;
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry.path());
        if (vf == null) {
            Notifications.Bus.notify(
                new Notification(NOTIFICATION_GROUP, "SFTP",
                    "Could not open " + entry.path(), NotificationType.ERROR),
                project);
            return;
        }
        FileEditorManager.getInstance(project).openFile(vf, true);
    }
}
