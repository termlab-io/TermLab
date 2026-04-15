package com.conch.editor.scratch;

import com.conch.sftp.client.SshSftpSession;
import com.conch.sftp.session.SftpSessionManager;
import com.conch.sftp.vfs.SftpUrl;
import com.conch.sftp.vfs.SftpVirtualFile;
import com.conch.sftp.vfs.SftpVirtualFileSystem;
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
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
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
 * Instead, this action uses a two-step flow: a platform
 * {@code FileChooser.chooseFile} (directory-only descriptor) lets the user
 * browse the SFTP VFS natively, then a small filename input dialog completes
 * the destination.
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
     * Core logic: use a two-step flow (directory chooser + filename prompt) to
     * let the user pick a remote destination, write the scratch content, then
     * transition the editor tab from scratch to the new remote file.
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
        // Acquire an action-scoped reference for the duration of this method.
        SftpSessionManager mgr = SftpSessionManager.getInstance();
        Object actionOwner = new Object();
        try {
            mgr.acquire(host, actionOwner);
        } catch (SshConnectException e) {
            notifySftp(project, "Session lost: " + e.getMessage(), NotificationType.ERROR);
            return;
        }

        try {
            // Step 1: resolve the starting directory as an SftpVirtualFile so
            // the FileChooser can browse the SFTP tree natively.
            String startUrl = SftpUrl.compose(host.id(), startingRemoteDir);
            VirtualFile root = VirtualFileManager.getInstance().findFileByUrl(startUrl);
            if (root == null) {
                notifySftp(project, "Could not list " + host.label() + ":" + startingRemoteDir,
                    NotificationType.ERROR);
                return;
            }

            // Step 2: platform directory chooser, rooted at the starting dir.
            // FileChooser.chooseFile is VirtualFile-native and works with our
            // SftpVirtualFileSystem. FileSaverDialog is NOT — it validates via
            // java.io.File#exists which always returns false for SFTP paths.
            FileChooserDescriptor descriptor =
                com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
                    .createSingleFolderDescriptor()
                    .withTitle("Save scratch to " + host.label() + " — pick a directory")
                    .withRoots(root);
            VirtualFile chosenDir = com.intellij.openapi.fileChooser.FileChooser
                .chooseFile(descriptor, project, root);
            if (chosenDir == null) return; // user cancelled

            // Step 3: prompt for the filename.
            String suggestedName = scratch.getName();
            String enteredName = Messages.showInputDialog(
                project,
                "File name on " + host.label() + " in " + safeRemotePath(chosenDir) + ":",
                "Save Scratch to Remote",
                null,
                suggestedName,
                null);
            if (enteredName == null || enteredName.isBlank()) return; // cancelled

            // Basic sanity: reject names containing path separators.
            if (enteredName.contains("/") || enteredName.contains("\\")) {
                notifySftp(project, "File name must not contain path separators: " + enteredName,
                    NotificationType.ERROR);
                return;
            }

            // Step 4: compose the full remote path and write.
            String chosenDirPath = safeRemotePath(chosenDir);
            String destPath = chosenDirPath.endsWith("/")
                ? chosenDirPath + enteredName
                : chosenDirPath + "/" + enteredName;

            String destUrl = SftpUrl.compose(host.id(), destPath);

            byte[] content = FileDocumentManager.getInstance().getDocument(scratch).getText()
                .getBytes(StandardCharsets.UTF_8);
            SftpVirtualFile destFile = new SftpVirtualFile(
                SftpVirtualFileSystem.getInstance(), host.id(), destPath, session,
                /*isDirectoryHint=*/false);
            try {
                destFile.setBinaryContent(content);
            } catch (IOException e) {
                notifySftp(project, "Save failed: " + e.getMessage(), NotificationType.ERROR);
                return;
            }

            // Step 5: resolve the canonical interned instance via findFileByUrl.
            VirtualFile saved = VirtualFileManager.getInstance().findFileByUrl(destUrl);
            if (saved == null) {
                notifySftp(project, "Saved, but could not reopen the file. Refresh the SFTP pane.",
                    NotificationType.WARNING);
                return;
            }

            // Step 6: transition session ownership from actionOwner to the new
            // tab's VirtualFile BEFORE releasing actionOwner, so the refcount
            // never hits zero.
            try {
                mgr.acquire(host, saved);
            } catch (SshConnectException ce) {
                notifySftp(project, "Session lost after save: " + ce.getMessage(), NotificationType.ERROR);
                return;
            }
            mgr.release(host.id(), actionOwner);
            actionOwner = null; // ownership transferred

            // Step 7: close the scratch tab, open the new file.
            FileEditorManager fem = FileEditorManager.getInstance(project);
            fem.closeFile(scratch);
            fem.openFile(saved, true);

            notifySftp(project, "Saved to " + host.label() + ":" + destPath,
                NotificationType.INFORMATION);
        } finally {
            if (actionOwner != null) {
                mgr.release(host.id(), actionOwner);
            }
        }
    }

    /**
     * Extract the remote path portion of an SftpVirtualFile's URL.
     * Falls back to the file's getPath() for non-SFTP files (shouldn't
     * happen on this code path, but defensive).
     */
    private static @NotNull String safeRemotePath(@NotNull VirtualFile vf) {
        if (vf instanceof SftpVirtualFile sftp) {
            // SftpVirtualFile.getPath() returns "<hostId>//<remotePath>".
            // We want just the remote path. Parse the URL instead.
            SftpUrl parsed = SftpUrl.parse(vf.getUrl());
            if (parsed != null) return parsed.remotePath();
        }
        return vf.getPath();
    }

    private static void notifySftp(
        @NotNull Project project, @NotNull String message, @NotNull NotificationType type
    ) {
        Notifications.Bus.notify(
            new Notification(NOTIFICATION_GROUP, "Save Scratch to Remote", message, type),
            project);
    }
}
