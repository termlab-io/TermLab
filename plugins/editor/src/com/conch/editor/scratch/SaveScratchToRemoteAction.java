package com.conch.editor.scratch;

import com.conch.sftp.client.SshSftpSession;
import com.conch.sftp.session.SftpSessionManager;
import com.conch.sftp.vfs.SftpUrl;
import com.conch.ssh.client.SshConnectException;
import com.conch.ssh.model.HostStore;
import com.conch.ssh.model.SshHost;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Save the active scratch file to a connected SFTP host. Cmd+Shift+S /
 * Ctrl+Shift+S. Disabled unless the active editor is on a marked scratch
 * LightVirtualFile.
 *
 * <p>Implementation note: the platform's {@code FileSaverDialog} is
 * hard-wired to the local filesystem (its OK-button enablement logic calls
 * {@code java.io.File#exists()} on the selected path, which always returns
 * {@code false} for SFTP paths). It cannot be rooted at an SFTP VirtualFile.
 * Instead, this action prompts the user for a remote destination path via a
 * simple input dialog, then writes the scratch content through
 * {@link SshSftpSession#writeBytesAtomically}.
 */
public final class SaveScratchToRemoteAction extends AnAction {

    private static final String NOTIFICATION_GROUP = "Conch SFTP";

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(canRun(e));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private static boolean canRun(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return false;
        return activeScratchFile(project) != null;
    }

    private static @Nullable VirtualFile activeScratchFile(@NotNull Project project) {
        FileEditor editor = FileEditorManager.getInstance(project).getSelectedEditor();
        if (editor == null) return null;
        VirtualFile file = editor.getFile();
        if (!(file instanceof LightVirtualFile lvf)) return null;
        if (lvf.getUserData(ScratchMarker.KEY) != Boolean.TRUE) return null;
        return file;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        VirtualFile scratch = activeScratchFile(project);
        if (scratch == null) return;

        // If the SFTP tool window already has an active session, use it directly.
        SftpSessionManager.ActiveSession active =
            SftpSessionManager.getInstance().getActiveSessionForCurrentProject(project);
        if (active != null) {
            proceedWithHost(project, scratch, active.host(), active.session(), active.currentRemotePath());
            return;
        }

        showHostPickerThenConnect(project, scratch);
    }

    private static void showHostPickerThenConnect(
        @NotNull Project project, @NotNull VirtualFile scratch
    ) {
        HostStore store = ApplicationManager.getApplication().getService(HostStore.class);
        if (store == null) {
            notifySftp(project, "No host store available", NotificationType.ERROR);
            return;
        }
        List<SshHost> hosts = store.getHosts();
        if (hosts.isEmpty()) {
            notifySftp(project,
                "No SFTP hosts configured. Add one in the SFTP tool window first.",
                NotificationType.WARNING);
            return;
        }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(hosts)
            .setTitle("Connect to host")
            .setVisibleRowCount(8)
            .setNamerForFiltering(SshHost::label)
            .setItemChosenCallback(host -> connectThenProceed(project, scratch, host))
            .createPopup()
            .showCenteredInCurrentWindow(project);
    }

    private static void connectThenProceed(
        @NotNull Project project, @NotNull VirtualFile scratch, @NotNull SshHost host
    ) {
        ProgressManager.getInstance().run(new Task.Modal(
            project, "Connecting to " + host.label() + "...", true
        ) {
            private SshSftpSession session;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    session = SftpSessionManager.getInstance().acquire(host, this);
                } catch (SshConnectException ex) {
                    ApplicationManager.getApplication().invokeLater(() ->
                        notifySftp(project, "Connection failed: " + ex.getMessage(),
                            NotificationType.ERROR));
                }
            }

            @Override
            public void onSuccess() {
                if (session == null) return;
                try {
                    proceedWithHost(project, scratch, host, session, "/");
                } finally {
                    SftpSessionManager.getInstance().release(host.id(), this);
                }
            }
        });
    }

    /**
     * Core logic: prompt for a remote destination path, write the scratch
     * content, then transition the editor tab from scratch to the new
     * remote file.
     *
     * <p>Session ownership for the duration of this method is managed via a
     * fresh {@code actionOwner} token so the refcount stays above zero
     * throughout. Before returning, ownership is transferred to the newly
     * opened {@code SftpVirtualFile} so the tab keeps the session alive.
     */
    private static void proceedWithHost(
        @NotNull Project project,
        @NotNull VirtualFile scratch,
        @NotNull SshHost host,
        @NotNull SshSftpSession session,
        @NotNull String startingRemoteDir
    ) {
        // Acquire an action-scoped reference to keep the session alive.
        SftpSessionManager mgr = SftpSessionManager.getInstance();
        Object actionOwner = new Object();
        try {
            mgr.acquire(host, actionOwner);
        } catch (SshConnectException ex) {
            notifySftp(project, "Session lost: " + ex.getMessage(), NotificationType.ERROR);
            return;
        }

        try {
            // Prompt the user for the remote destination path.
            // FileSaverDialog cannot be used here because its OK-button
            // enablement calls java.io.File#exists(), which is always false
            // for remote SFTP paths.
            String suggested = startingRemoteDir.endsWith("/")
                ? startingRemoteDir + scratch.getName()
                : startingRemoteDir + "/" + scratch.getName();
            String destPath = Messages.showInputDialog(
                project,
                "Remote path on " + host.label() + ":",
                "Save Scratch to Remote",
                Messages.getQuestionIcon(),
                suggested,
                null
            );
            if (destPath == null || destPath.isBlank()) return; // user cancelled

            destPath = destPath.strip();
            if (!destPath.startsWith("/")) {
                notifySftp(project, "Remote path must be absolute (start with /)",
                    NotificationType.ERROR);
                return;
            }

            // Normalize Windows-style separators (defensive).
            destPath = destPath.replace('\\', '/');

            // Write scratch contents atomically via the SFTP session helper.
            String docText = FileDocumentManager.getInstance().getDocument(scratch).getText();
            byte[] content = docText.getBytes(StandardCharsets.UTF_8);
            try {
                session.writeBytesAtomically(destPath, content);
            } catch (IOException writeEx) {
                notifySftp(project, "Save failed: " + writeEx.getMessage(), NotificationType.ERROR);
                return;
            }

            // Resolve the newly-written file through the VFS so we get an
            // interned SftpVirtualFile instance (triggers a remote stat).
            String destUrl = SftpUrl.compose(host.id(), destPath);
            VirtualFile saved = VirtualFileManager.getInstance().findFileByUrl(destUrl);
            if (saved == null) {
                notifySftp(project,
                    "Saved, but could not reopen the file. Refresh the SFTP pane.",
                    NotificationType.WARNING);
                return;
            }

            // Transfer session ownership: acquire for the new tab BEFORE
            // releasing the action's reference so the refcount never hits 0.
            try {
                mgr.acquire(host, saved);
            } catch (SshConnectException ce) {
                notifySftp(project, "Session lost after save: " + ce.getMessage(),
                    NotificationType.ERROR);
                return;
            }
            mgr.release(host.id(), actionOwner);
            actionOwner = null; // ownership transferred to the new tab's VirtualFile

            // Close the scratch tab and open the remote file.
            FileEditorManager editorMgr = FileEditorManager.getInstance(project);
            editorMgr.closeFile(scratch);
            editorMgr.openFile(saved, /*focusEditor=*/true);

            notifySftp(project, "Saved to " + host.label() + ":" + destPath,
                NotificationType.INFORMATION);
        } finally {
            if (actionOwner != null) {
                mgr.release(host.id(), actionOwner);
            }
        }
    }

    private static void notifySftp(
        @NotNull Project project, @NotNull String message, @NotNull NotificationType type
    ) {
        Notifications.Bus.notify(
            new Notification(NOTIFICATION_GROUP, "Save Scratch to Remote", message, type),
            project);
    }
}
