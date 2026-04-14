package com.conch.editor.remote;

import com.conch.editor.guard.BinarySniffer;
import com.conch.editor.guard.ExtensionBlocklist;
import com.conch.sftp.client.SshSftpSession;
import com.conch.sftp.model.LocalFileEntry;
import com.conch.sftp.model.RemoteFileEntry;
import com.conch.sftp.transfer.SftpSingleFileTransfer;
import com.conch.ssh.model.SshHost;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Application service that opens local and remote files for
 * editing in Conch's main editor area. Guards with the size cap
 * (5 MB), extension blocklist, and (for remote only) a null-byte
 * binary sniff.
 */
@Service(Service.Level.APP)
public final class RemoteEditService {

    private static final long SIZE_CAP_BYTES = 5L * 1024 * 1024;
    private static final String TEMP_ROOT_NAME = "conch-sftp-edits";
    private static final String NOTIFICATION_GROUP = "Conch Light Editor";

    public @NotNull Path tempRoot() {
        return Paths.get(PathManager.getSystemPath(), TEMP_ROOT_NAME);
    }

    /**
     * Open a local file (from the SFTP local pane) directly in the
     * main editor area. Applies size cap and extension blocklist.
     */
    public void openLocalFile(@NotNull Project project, @NotNull LocalFileEntry entry) {
        if (entry.isDirectory()) return;
        if (ExtensionBlocklist.isBlocked(entry.name())) {
            notifyError(project, "Cannot edit " + entry.name() + ": binary file type.");
            return;
        }
        if (entry.size() > SIZE_CAP_BYTES) {
            notifyError(project, "File too large (" + formatMb(entry.size())
                + " MB). Maximum editable size is 5 MB.");
            return;
        }
        VirtualFile vf = LocalFileSystem.getInstance()
            .refreshAndFindFileByNioFile(entry.path());
        if (vf == null) {
            notifyError(project, "Could not open " + entry.path() + " — file not found.");
            return;
        }
        FileEditorManager.getInstance(project).openFile(vf, true);
    }

    /**
     * Download a remote file to a deterministic temp path, sniff
     * for binary content, open in the main editor area, and
     * register a {@link RemoteFileBinding} so save uploads back.
     */
    public void openRemoteFile(
        @NotNull Project project,
        @NotNull SshHost host,
        @NotNull SshSftpSession session,
        @NotNull String absoluteRemotePath,
        @NotNull RemoteFileEntry entry
    ) {
        if (entry.isDirectory()) return;
        if (ExtensionBlocklist.isBlocked(entry.name())) {
            notifyError(project, "Cannot edit " + entry.name() + ": binary file type.");
            return;
        }
        if (entry.size() > SIZE_CAP_BYTES) {
            notifyError(project, "File too large (" + formatMb(entry.size())
                + " MB). Maximum editable size is 5 MB.");
            return;
        }

        Path tempPath = TempPathResolver.resolve(
            tempRoot(), connectionString(host), absoluteRemotePath);

        AppExecutorUtil.getAppExecutorService().submit(() -> {
            try {
                Files.createDirectories(tempPath.getParent());
                SftpSingleFileTransfer.download(session.client(), absoluteRemotePath, tempPath);
                if (BinarySniffer.isBinary(tempPath)) {
                    Files.deleteIfExists(tempPath);
                    ApplicationManager.getApplication().invokeLater(() ->
                        notifyError(project, "Binary file detected: " + entry.name()));
                    return;
                }
            } catch (IOException e) {
                try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
                ApplicationManager.getApplication().invokeLater(() ->
                    notifyError(project, "Download failed for "
                        + entry.name() + ": " + e.getMessage()));
                return;
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                VirtualFile vf = LocalFileSystem.getInstance()
                    .refreshAndFindFileByNioFile(tempPath);
                if (vf == null) {
                    notifyError(project, "Could not open downloaded file: " + tempPath);
                    return;
                }
                RemoteFileBindingRegistry registry = ApplicationManager.getApplication()
                    .getService(RemoteFileBindingRegistry.class);
                registry.register(new RemoteFileBinding(tempPath, host, absoluteRemotePath, session));
                FileEditorManager.getInstance(project).openFile(vf, true);
            });
        });
    }

    private static @NotNull String connectionString(@NotNull SshHost host) {
        return host.username() + "@" + host.host() + ":" + host.port();
    }

    private static @NotNull String formatMb(long bytes) {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }

    private static void notifyError(@NotNull Project project, @NotNull String message) {
        Notifications.Bus.notify(
            new Notification(NOTIFICATION_GROUP, "Light Editor", message, NotificationType.ERROR),
            project);
    }
}
