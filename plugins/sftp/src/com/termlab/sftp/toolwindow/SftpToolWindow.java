package com.termlab.sftp.toolwindow;

import com.termlab.sftp.transfer.TransferCoordinator;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBSplitter;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Dual-pane SFTP browser. The left pane shows the local filesystem;
 * the right pane shows the currently-connected remote host's
 * filesystem; a narrow column of transfer buttons between them fires
 * upload / download jobs through {@link TransferCoordinator}.
 */
public final class SftpToolWindow extends JPanel {

    private final LocalFilePane local;
    private final RemoteFilePane remote;
    private final TransferCoordinator coordinator;
    private final JButton uploadButton;
    private final JButton downloadButton;

    public SftpToolWindow(@NotNull Project project) {
        super(new BorderLayout());
        this.local = new LocalFilePane(project);
        this.remote = new RemoteFilePane(project);
        this.coordinator = new TransferCoordinator(project, local, remote);

        this.uploadButton = new JButton("→");
        uploadButton.setToolTipText("Upload selected local files to remote");
        uploadButton.addActionListener(e -> coordinator.upload());

        this.downloadButton = new JButton("←");
        downloadButton.setToolTipText("Download selected remote files to local");
        downloadButton.addActionListener(e -> coordinator.download());

        JPanel leftWithUpload = new JPanel(new BorderLayout());
        leftWithUpload.add(local, BorderLayout.CENTER);
        leftWithUpload.add(buildUploadBar(), BorderLayout.EAST);

        JPanel rightWithDownload = new JPanel(new BorderLayout());
        rightWithDownload.add(buildDownloadBar(), BorderLayout.WEST);
        rightWithDownload.add(remote, BorderLayout.CENTER);

        JBSplitter splitter = new JBSplitter(false, 0.5f);
        splitter.setFirstComponent(leftWithUpload);
        splitter.setSecondComponent(rightWithDownload);
        add(splitter, BorderLayout.CENTER);

        // Any change in either side's selection or connection state
        // can flip button enablement — wire both panes' selection
        // models into a shared refresh. Connection state changes
        // already call refresh() on the remote pane which ripples
        // through the selection listener we install here.
        local.addSelectionListener(e -> refreshButtons());
        remote.addSelectionListener(e -> refreshButtons());
        local.addDirectoryChangeListener(this::refreshButtons);
        remote.addConnectionStateListener(this::refreshButtons);
        refreshButtons();
    }

    private @NotNull JComponent buildUploadBar() {
        return buildButtonBar(uploadButton, "Upload");
    }

    private @NotNull JComponent buildDownloadBar() {
        return buildButtonBar(downloadButton, "Download");
    }

    private static @NotNull JComponent buildButtonBar(@NotNull JButton button, @NotNull String hintText) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(4, 2));
        panel.add(Box.createVerticalGlue());
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(button);
        panel.add(Box.createVerticalStrut(6));
        JLabel hint = new JLabel(hintText);
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 2f));
        panel.add(hint);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private void refreshButtons() {
        uploadButton.setEnabled(coordinator.canUpload());
        downloadButton.setEnabled(coordinator.canDownload());
    }

    public @NotNull LocalFilePane localPane() {
        return local;
    }

    public @NotNull RemoteFilePane remotePane() {
        return remote;
    }
}
