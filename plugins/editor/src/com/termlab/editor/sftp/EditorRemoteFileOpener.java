package com.termlab.editor.sftp;

import com.termlab.editor.guard.BinarySniffer;
import com.termlab.editor.guard.OpenGuards;
import com.termlab.sftp.client.SshSftpSession;
import com.termlab.sftp.model.RemoteFileEntry;
import com.termlab.sftp.spi.RemoteFileOpener;
import com.termlab.sftp.vfs.SftpUrl;
import com.termlab.ssh.model.SshHost;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;

public final class EditorRemoteFileOpener implements RemoteFileOpener {

    private static final String NOTIFICATION_GROUP = "TermLab SFTP";

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

        // Acquire a tab-scoped session reference; SftpEditorTabListener
        // releases it when the editor tab closes.
        try {
            com.termlab.sftp.session.SftpSessionManager.getInstance().acquire(host, vf);
        } catch (com.termlab.ssh.client.SshConnectException e) {
            notifyError(project, "Session lost for " + host.label() + ": " + e.getMessage());
            return;
        }

        FileEditorManager.getInstance(project).openFile(vf, true);
    }

    private static void notifyError(@NotNull Project project, @NotNull String message) {
        Notifications.Bus.notify(
            new Notification(NOTIFICATION_GROUP, "TermLab SFTP", message, NotificationType.ERROR),
            project);
    }
}
