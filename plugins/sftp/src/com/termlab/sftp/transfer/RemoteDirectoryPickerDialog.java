package com.termlab.sftp.transfer;

import com.termlab.core.filepicker.ui.FileTableModel;
import com.termlab.core.filepicker.ui.FileBrowserTable;
import com.termlab.sftp.client.SshSftpSession;
import com.termlab.sftp.model.RemoteFileEntry;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.apache.sshd.sftp.client.SftpClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class RemoteDirectoryPickerDialog extends DialogWrapper {

    private final SshSftpSession session;
    private final JTextField pathField = new JTextField();
    private final FileBrowserTable browser = new FileBrowserTable();
    private @Nullable String currentPath;
    private @Nullable String result;

    public RemoteDirectoryPickerDialog(
        @Nullable Project project,
        @NotNull String hostLabel,
        @NotNull SshSftpSession session,
        @NotNull String initialPath
    ) {
        super(project, true);
        this.session = session;
        setTitle("Choose Remote Destination on " + hostLabel);
        setOKButtonText("Choose");
        String normalizedInitialPath = normalizeInitialPath(initialPath);
        init();
        pathField.setText(normalizedInitialPath);

        browser.getTable().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int viewRow = browser.getTable().rowAtPoint(e.getPoint());
                if (viewRow < 0 || currentPath == null) {
                    return;
                }
                int modelRow = browser.getTable().convertRowIndexToModel(viewRow);
                if (!(browser.getTable().getModel() instanceof FileTableModel model)) {
                    return;
                }
                if (!(model.getEntryAt(modelRow) instanceof RemoteFileEntry remote) || !remote.isDirectory()) {
                    return;
                }
                navigateTo(joinPath(currentPath, remote.name()));
            }
        });
        pathField.addActionListener(e -> {
            String text = pathField.getText().trim();
            if (!text.isEmpty()) {
                navigateTo(text);
            }
        });
        navigateTo(normalizedInitialPath);
    }

    public @Nullable String showAndGetSelection() {
        if (showAndGet()) {
            return result;
        }
        return null;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setPreferredSize(new Dimension(640, 420));

        JPanel north = new JPanel(new BorderLayout(6, 0));
        JButton upButton = new JButton("▲");
        upButton.addActionListener(e -> {
            String parent = parentOf(currentPath);
            if (parent != null) {
                navigateTo(parent);
            }
        });
        north.add(upButton, BorderLayout.WEST);
        north.add(pathField, BorderLayout.CENTER);

        root.add(north, BorderLayout.NORTH);
        root.add(browser.getComponent(), BorderLayout.CENTER);
        return root;
    }

    @Override
    protected void doOKAction() {
        String selectedPath = currentPath;
        var selected = browser.getSelectedEntry();
        if (selected instanceof RemoteFileEntry entry && entry.isDirectory() && currentPath != null) {
            selectedPath = joinPath(currentPath, entry.name());
        }
        if (selectedPath == null || selectedPath.isBlank()) {
            return;
        }
        result = selectedPath;
        super.doOKAction();
    }

    private void navigateTo(@NotNull String path) {
        AppExecutorUtil.getAppExecutorService().submit(() -> {
            List<RemoteFileEntry> entries = new ArrayList<>();
            IOException error = null;
            try {
                if (!session.client().stat(path).isDirectory()) {
                    throw new IOException("Not a directory: " + path);
                }
                for (SftpClient.DirEntry dirEntry : session.client().readDir(path)) {
                    String name = dirEntry.getFilename();
                    if (".".equals(name) || "..".equals(name)) continue;
                    entries.add(RemoteFileEntry.of(dirEntry));
                }
            } catch (IOException e) {
                error = e;
            }
            IOException finalError = error;
            List<RemoteFileEntry> snapshot = entries;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (finalError != null) {
                    Messages.showErrorDialog(getContentPanel(),
                        "Could not open remote path:\n" + path + "\n\n" + finalError.getMessage(),
                        "Remote Path Error");
                    return;
                }
                currentPath = path;
                pathField.setText(path);
                browser.setEntries(snapshot);
            }, ModalityState.any());
        });
    }

    private static @Nullable String parentOf(@Nullable String path) {
        if (path == null || "/".equals(path) || path.isEmpty()) return null;
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        if (slash <= 0) return "/";
        return trimmed.substring(0, slash);
    }

    private static @NotNull String joinPath(@NotNull String base, @NotNull String child) {
        if (base.endsWith("/")) return base + child;
        return base + "/" + child;
    }

    private static @NotNull String normalizeInitialPath(@Nullable String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path;
    }
}
