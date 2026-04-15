package com.conch.editor.sftp;

import com.conch.editor.guard.BinarySniffer;
import com.conch.editor.guard.OpenGuards;
import com.conch.sftp.client.SshSftpSession;
import com.conch.sftp.model.RemoteFileEntry;
import com.conch.sftp.spi.RemoteFileOpener;
import com.conch.sftp.vfs.SftpUrl;
import com.conch.ssh.model.SshHost;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;

public final class EditorRemoteFileOpener implements RemoteFileOpener {

    private static final String NOTIFICATION_GROUP = "Conch SFTP";

    @Override
    public void open(
        @NotNull Project project,
        @NotNull SshHost host,
        @NotNull SshSftpSession session,
        @NotNull String absoluteRemotePath,
        @NotNull RemoteFileEntry entry
    ) {
        if (!OpenGuards.allow(project, entry.name(), entry.size())) return;

        String url = SftpUrl.compose(host.id(), absoluteRemotePath);
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(url);
        if (vf == null) {
            notifyError(project, "Could not open " + entry.name() + " on " + host.label());
            return;
        }

        if (BinarySniffer.isBinaryByContent(vf)) {
            notifyError(project, "Binary file detected: " + entry.name());
            return;
        }

        FileEditorManager.getInstance(project).openFile(vf, true);
    }

    private static void notifyError(@NotNull Project project, @NotNull String message) {
        Notifications.Bus.notify(
            new Notification(NOTIFICATION_GROUP, "Conch SFTP", message, NotificationType.ERROR),
            project);
    }
}
