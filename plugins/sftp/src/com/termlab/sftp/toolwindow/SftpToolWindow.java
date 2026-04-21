package com.termlab.sftp.toolwindow;

import com.termlab.sftp.persistence.TermLabSftpConfig;
import com.termlab.sftp.transfer.RemoteDirectoryPickerDialog;
import com.termlab.sftp.transfer.TransferCoordinator;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.ui.JBSplitter;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

/**
 * Dual-pane SFTP browser. The left pane shows the local filesystem;
 * the right pane shows the currently-connected remote host's
 * filesystem; a narrow column of transfer buttons between them fires
 * upload / download jobs through {@link TransferCoordinator}.
 */
public final class SftpToolWindow extends JPanel {

    private static final float DEFAULT_SPLITTER_PROPORTION = 0.5f;

    private final Project project;
    private final ToolWindow toolWindow;
    private final LocalFilePane local;
    private final RemoteFilePane remote;
    private final TransferCoordinator coordinator;
    private final JPanel contentPanel;
    private final JBSplitter dualPaneSplitter;
    private TermLabSftpConfig.ViewMode viewMode;
    private boolean stackedLayout;

    public SftpToolWindow(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        super(new BorderLayout());
        this.project = project;
        this.toolWindow = toolWindow;
        this.local = new LocalFilePane(project);
        this.remote = new RemoteFilePane(project);
        this.coordinator = new TransferCoordinator(project, local, remote);
        TermLabSftpConfig config = TermLabSftpConfig.getInstance();
        this.viewMode = config.getViewMode();
        this.stackedLayout = shouldStackForAnchor(toolWindow);

        this.contentPanel = new JPanel(new BorderLayout());
        this.dualPaneSplitter = new JBSplitter(stackedLayout, DEFAULT_SPLITTER_PROPORTION);
        this.dualPaneSplitter.setFirstComponent(local);
        this.dualPaneSplitter.setSecondComponent(remote);

        this.local.setTransferActions(new LocalFilePane.TransferActions() {
            @Override
            public boolean canUpload() {
                return coordinator.canUpload();
            }

            @Override
            public boolean canUploadToPath() {
                return coordinator.canUploadToRemotePath();
            }

            @Override
            public @NotNull String uploadTargetLabel() {
                String label = coordinator.remoteHostLabel();
                return label != null ? label : "remote host";
            }

            @Override
            public void uploadSelection() {
                coordinator.upload();
            }

            @Override
            public void uploadSelectionTo(@NotNull String remotePath) {
                coordinator.uploadTo(remotePath);
            }

            @Override
            public void chooseAndUploadSelectionToPath() {
                if (remote.activeSession() == null) return;
                String initialPath = resolveInitialRemotePath();
                String selection = new RemoteDirectoryPickerDialog(
                    project,
                    uploadTargetLabel(),
                    remote.activeSession(),
                    initialPath
                ).showAndGetSelection();
                if (selection != null) {
                    coordinator.uploadTo(selection);
                }
            }
        });

        this.remote.setTransferActions(new RemoteFilePane.TransferActions() {
            @Override
            public boolean canDownload() {
                return coordinator.canDownload();
            }

            @Override
            public boolean canDownloadToPath() {
                return coordinator.canDownloadToLocalPath();
            }

            @Override
            public @NotNull String downloadTargetLabel() {
                return coordinator.localHostLabel();
            }

            @Override
            public void downloadSelection() {
                coordinator.download();
            }

            @Override
            public void downloadSelectionTo(@NotNull java.nio.file.Path localPath) {
                coordinator.downloadTo(localPath);
            }

            @Override
            public void chooseAndDownloadSelectionToPath() {
                VirtualFile initial = null;
                if (local.currentDirectory() != null) {
                    initial = LocalFileSystem.getInstance().findFileByNioFile(local.currentDirectory());
                }
                VirtualFile chosen = FileChooser.chooseFile(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                    project,
                    initial
                );
                if (chosen != null) {
                    coordinator.downloadTo(chosen.toNioPath());
                }
            }
        });

        add(contentPanel, BorderLayout.CENTER);
        installToolWindowLayoutListener();
        applyViewMode(viewMode);
    }

    public void setViewMode(@NotNull TermLabSftpConfig.ViewMode mode) {
        if (viewMode == mode) return;
        applyViewMode(mode);
        TermLabSftpConfig.getInstance().setViewMode(mode);
    }

    public @NotNull TermLabSftpConfig.ViewMode getViewMode() {
        return viewMode;
    }

    private void applyViewMode(@NotNull TermLabSftpConfig.ViewMode mode) {
        this.viewMode = mode;

        contentPanel.removeAll();
        switch (mode) {
            case BOTH -> contentPanel.add(dualPaneSplitter, BorderLayout.CENTER);
            case LOCAL_ONLY -> contentPanel.add(local, BorderLayout.CENTER);
            case REMOTE_ONLY -> contentPanel.add(remote, BorderLayout.CENTER);
        }
        contentPanel.revalidate();
        contentPanel.repaint();
    }

    private void installToolWindowLayoutListener() {
        project.getMessageBus().connect(toolWindow.getDisposable()).subscribe(ToolWindowManagerListener.TOPIC, new ToolWindowManagerListener() {
            @Override
            public void stateChanged(@NotNull com.intellij.openapi.wm.ToolWindowManager toolWindowManager) {
                SwingUtilities.invokeLater(() -> {
                    if (!toolWindow.isDisposed()) {
                        updateDualPaneOrientation();
                    }
                });
            }
        });
    }

    private void updateDualPaneOrientation() {
        boolean shouldStack = shouldStackForAnchor(toolWindow);
        if (stackedLayout == shouldStack) {
            return;
        }
        stackedLayout = shouldStack;
        dualPaneSplitter.setOrientation(stackedLayout);
        dualPaneSplitter.revalidate();
        dualPaneSplitter.repaint();
    }

    private static boolean shouldStackForAnchor(@NotNull ToolWindow toolWindow) {
        return !toolWindow.getAnchor().isHorizontal();
    }

    private @NotNull String resolveInitialRemotePath() {
        String current = remote.currentRemotePath();
        if (current != null && !current.isBlank()) {
            return current;
        }
        if (remote.activeSession() != null) {
            try {
                String canonical = remote.activeSession().client().canonicalPath(".");
                if (canonical != null && !canonical.isBlank()) {
                    return canonical;
                }
            } catch (IOException ignored) {
            }
        }
        return "/";
    }

    public @NotNull LocalFilePane localPane() {
        return local;
    }

    public @NotNull RemoteFilePane remotePane() {
        return remote;
    }

}
