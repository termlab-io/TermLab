package com.conch.editor.remote;

import com.conch.sftp.transfer.SftpSingleFileTransfer;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Listens for document saves and, if the saved document backs a
 * file registered in {@link RemoteFileBindingRegistry}, uploads
 * the new contents back to the remote host.
 *
 * <p>Runs after the platform has written the document to disk:
 * {@link #beforeDocumentSaving} captures the binding, but the
 * upload itself is deferred to {@link #fileContentLoaded} /
 * after the save completes so we read the written bytes. In
 * practice the simplest correct option is to hook
 * {@link #beforeDocumentSaving} and chain the upload on the app
 * executor — the platform write-out is synchronous within the
 * save path so the temp file is up-to-date by the time our
 * executor task runs.
 */
public final class RemoteSaveListener implements FileDocumentManagerListener {

    private static final String NOTIFICATION_GROUP = "Conch Light Editor";

    @Override
    public void beforeDocumentSaving(@NotNull Document document) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null) return;
        Path path = Paths.get(file.getPath());
        RemoteFileBindingRegistry registry = ApplicationManager.getApplication()
            .getService(RemoteFileBindingRegistry.class);
        RemoteFileBinding binding = registry.get(path.toAbsolutePath().toString());
        if (binding == null) return;

        // The platform writes the document to disk as part of its
        // own save sequence. Kick the upload onto the app executor
        // so it runs off-EDT after that write has landed.
        AppExecutorUtil.getAppExecutorService().submit(() -> uploadAndNotify(binding));
    }

    private void uploadAndNotify(@NotNull RemoteFileBinding binding) {
        try {
            SftpSingleFileTransfer.upload(
                binding.session().client(),
                binding.tempPath(),
                binding.absoluteRemotePath());
        } catch (IOException e) {
            notifyUploadFailure(binding, e);
            return;
        }
        notifyUploadSuccess(binding);
    }

    private static void notifyUploadSuccess(@NotNull RemoteFileBinding binding) {
        String title = binding.host().host() + ":" + binding.absoluteRemotePath();
        Notifications.Bus.notify(new Notification(
            NOTIFICATION_GROUP,
            "Uploaded",
            title,
            NotificationType.INFORMATION));
    }

    private static void notifyUploadFailure(
        @NotNull RemoteFileBinding binding,
        @NotNull IOException cause
    ) {
        String title = binding.host().host() + ":" + binding.absoluteRemotePath();
        Notification n = new Notification(
            NOTIFICATION_GROUP,
            "Upload failed",
            title + "\n" + cause.getMessage(),
            NotificationType.ERROR);
        n.addAction(new NotificationAction("Retry upload") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                notification.expire();
                AppExecutorUtil.getAppExecutorService().submit(() -> {
                    try {
                        SftpSingleFileTransfer.upload(
                            binding.session().client(),
                            binding.tempPath(),
                            binding.absoluteRemotePath());
                        notifyUploadSuccess(binding);
                    } catch (IOException retryErr) {
                        notifyUploadFailure(binding, retryErr);
                    }
                });
            }
        });
        Notifications.Bus.notify(n);
    }
}
