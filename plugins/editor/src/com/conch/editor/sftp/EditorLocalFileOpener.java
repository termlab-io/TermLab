package com.conch.editor.sftp;

import com.conch.editor.remote.RemoteEditService;
import com.conch.sftp.model.LocalFileEntry;
import com.conch.sftp.spi.LocalFileOpener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class EditorLocalFileOpener implements LocalFileOpener {

    @Override
    public void open(@NotNull Project project, @NotNull LocalFileEntry entry) {
        RemoteEditService service = ApplicationManager.getApplication()
            .getService(RemoteEditService.class);
        if (service == null) return;
        service.openLocalFile(project, entry);
    }
}
