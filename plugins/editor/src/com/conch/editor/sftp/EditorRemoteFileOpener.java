package com.conch.editor.sftp;

import com.conch.editor.remote.RemoteEditService;
import com.conch.sftp.client.SshSftpSession;
import com.conch.sftp.model.RemoteFileEntry;
import com.conch.sftp.spi.RemoteFileOpener;
import com.conch.ssh.model.SshHost;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class EditorRemoteFileOpener implements RemoteFileOpener {

    @Override
    public void open(
        @NotNull Project project,
        @NotNull SshHost host,
        @NotNull SshSftpSession session,
        @NotNull String absoluteRemotePath,
        @NotNull RemoteFileEntry entry
    ) {
        RemoteEditService service = ApplicationManager.getApplication()
            .getService(RemoteEditService.class);
        if (service == null) return;
        service.openRemoteFile(project, host, session, absoluteRemotePath, entry);
    }
}
