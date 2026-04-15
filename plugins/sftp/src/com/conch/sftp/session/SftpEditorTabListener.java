package com.conch.sftp.session;

import com.conch.sftp.vfs.SftpVirtualFile;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Project-scoped listener that releases the SFTP session reference
 * when an SftpVirtualFile-backed editor tab closes. The matching
 * acquire happens in EditorRemoteFileOpener (and SaveScratchToRemoteAction)
 * with the VirtualFile as the owner key.
 */
public final class SftpEditorTabListener implements FileEditorManagerListener {

    @Override
    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        // Acquire is done by the caller that opened the file (the opener
        // or the save action). We don't acquire here because we don't have
        // the SshHost — only the VirtualFile.
    }

    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        if (!(file instanceof SftpVirtualFile sftp)) return;
        SftpSessionManager.getInstance().release(sftp.hostId(), file);
    }
}
