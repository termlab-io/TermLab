package com.conch.sftp.transfer;

import com.conch.sftp.client.SshSftpSession;
import com.conch.sftp.toolwindow.LocalFilePane;
import com.conch.sftp.toolwindow.RemoteFilePane;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Glue between {@link LocalFilePane}, {@link RemoteFilePane}, and
 * the transfer engine. Owns the "is a transfer allowed right now"
 * predicate, dispatches uploads/downloads on a background task,
 * and refreshes the destination pane on completion.
 */
public final class TransferCoordinator {

    private static final Logger LOG = Logger.getInstance(TransferCoordinator.class);

    private final Project project;
    private final LocalFilePane localPane;
    private final RemoteFilePane remotePane;

    public TransferCoordinator(
        @NotNull Project project,
        @NotNull LocalFilePane localPane,
        @NotNull RemoteFilePane remotePane
    ) {
        this.project = project;
        this.localPane = localPane;
        this.remotePane = remotePane;
    }

    // -- Enablement predicates (wired into button setEnabled) ----------------

    public boolean canUpload() {
        return remotePane.activeSession() != null
            && remotePane.currentRemotePath() != null
            && !localPane.selectedPaths().isEmpty();
    }

    public boolean canDownload() {
        return remotePane.activeSession() != null
            && localPane.currentDirectory() != null
            && !remotePane.selectedRemotePaths().isEmpty();
    }

    // -- Entry points --------------------------------------------------------

    public void upload() {
        SshSftpSession session = remotePane.activeSession();
        String remoteDest = remotePane.currentRemotePath();
        List<Path> sources = localPane.selectedPaths();
        if (session == null || remoteDest == null || sources.isEmpty()) return;

        runTransfer("Upload to " + remoteDest, indicator -> {
            CollisionResolver resolver = new CollisionResolver(
                (destination, sourceSize, existingSize) ->
                    CollisionDialog.prompt(destination, sourceSize, existingSize));
            TransferEngine engine = new TransferEngine(session.client(), resolver, indicator);
            return engine.upload(sources, remoteDest);
        }, /* refreshRemote = */ true);
    }

    public void download() {
        SshSftpSession session = remotePane.activeSession();
        Path localDest = localPane.currentDirectory();
        List<String> sources = remotePane.selectedRemotePaths();
        if (session == null || localDest == null || sources.isEmpty()) return;

        runTransfer("Download to " + localDest, indicator -> {
            CollisionResolver resolver = new CollisionResolver(
                (destination, sourceSize, existingSize) ->
                    CollisionDialog.prompt(destination, sourceSize, existingSize));
            TransferEngine engine = new TransferEngine(session.client(), resolver, indicator);
            return engine.download(sources, localDest);
        }, /* refreshRemote = */ false);
    }

    // -- Plumbing ------------------------------------------------------------

    @FunctionalInterface
    private interface TransferBody {
        @NotNull TransferResult run(@NotNull ProgressIndicator indicator) throws IOException;
    }

    private void runTransfer(@NotNull String title, @NotNull TransferBody body, boolean refreshRemote) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, title, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                TransferResult result = TransferResult.EMPTY;
                Throwable failure = null;
                boolean cancelled = false;
                try {
                    result = body.run(indicator);
                } catch (ProcessCanceledException pce) {
                    LOG.info("SFTP transfer cancelled: " + title);
                    cancelled = true;
                } catch (Throwable t) {
                    LOG.warn("SFTP transfer failed: " + title, t);
                    failure = t;
                }
                final TransferResult finalResult = result;
                final Throwable finalFailure = failure;
                final boolean finalCancelled = cancelled;
                ApplicationManager.getApplication().invokeLater(() -> {
                    // Always refresh the destination side so the user
                    // sees whatever did complete before a failure /
                    // cancellation.
                    if (refreshRemote) {
                        remotePane.refresh();
                    } else {
                        localPane.refresh();
                    }
                    if (finalFailure != null) {
                        TransferNotifications.failed(project, title, finalResult, finalFailure);
                    } else if (finalCancelled) {
                        TransferNotifications.cancelled(project, title, finalResult);
                    } else {
                        TransferNotifications.success(project, title, finalResult);
                    }
                    LOG.info("SFTP transfer finished: " + title
                        + " (" + finalResult.filesTransferred() + " files, "
                        + finalResult.bytesTransferred() + " bytes)");
                });
            }
        });
    }
}
