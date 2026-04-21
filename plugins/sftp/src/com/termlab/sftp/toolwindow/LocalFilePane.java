package com.termlab.sftp.toolwindow;

import com.termlab.core.filepicker.ui.FileNameCellRenderer;
import com.termlab.core.filepicker.ui.FileTableModel;
import com.termlab.core.filepicker.ui.ModifiedCellRenderer;
import com.termlab.core.filepicker.ui.SizeCellRenderer;
import com.termlab.sftp.model.LocalFileEntry;
import com.termlab.sftp.ops.LocalFileOps;
import com.termlab.sftp.persistence.TermLabSftpConfig;
import com.termlab.sftp.search.FileListCache;
import com.termlab.sftp.spi.LocalFileOpener;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionPopupMenu;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.event.ListSelectionListener;
import javax.swing.event.ChangeEvent;
import javax.swing.table.TableRowSorter;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Local-filesystem side of the dual-pane SFTP browser. Reads
 * directory listings through {@link java.nio.file.Files} on the
 * application executor and updates its {@link FileTableModel} on
 * the EDT.
 */
public final class LocalFilePane extends JPanel {
    private static final TermLabSftpConfig.TablePane TABLE_PANE = TermLabSftpConfig.TablePane.LOCAL;

    private final Project project;
    private final TermLabSftpConfig config = TermLabSftpConfig.getInstance();
    private final FileTableModel model = new FileTableModel();
    private final JBTable table = new JBTable(model);
    private final JScrollPane scrollPane = new JScrollPane(table);
    private final TableColumn[] columns = new TableColumn[model.getColumnCount()];
    private final JBTextField pathField = new JBTextField();
    private final FileListCache fileListCache = new FileListCache();
    private volatile boolean showHiddenFiles = config.isShowHiddenLocalFiles();

    private Path currentDir;
    private final CopyOnWriteArrayList<Runnable> directoryListeners = new CopyOnWriteArrayList<>();
    private TransferActions transferActions;

    public LocalFilePane(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        JPanel north = new JPanel(new BorderLayout());
        north.add(buildToolbar().getComponent(), BorderLayout.WEST);
        pathField.setEditable(true);
        pathField.setToolTipText("Type an absolute path and press Enter to navigate");
        pathField.addActionListener(e -> onPathFieldSubmit());
        north.add(pathField, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);

        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(FileTableModel.COL_NAME)
            .setCellRenderer(new FileNameCellRenderer());
        table.getColumnModel().getColumn(FileTableModel.COL_SIZE)
            .setCellRenderer(new SizeCellRenderer());
        table.getColumnModel().getColumn(FileTableModel.COL_MODIFIED)
            .setCellRenderer(new ModifiedCellRenderer());
        TableRowSorter<FileTableModel> sorter = new TableRowSorter<>(model);
        sorter.setSortsOnUpdates(true);
        table.setRowSorter(sorter);
        captureColumns();
        installColumnLayoutPersistence();
        installHeaderPopupMenu();
        applyPersistedColumnVisibility();
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    onRowActivated(table.getSelectedRow());
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }
        });
        add(scrollPane, BorderLayout.CENTER);
        installTransferShortcut();

        reload(initialDirectory());
    }

    private void maybeShowPopup(@NotNull MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int viewRow = table.rowAtPoint(e.getPoint());
        if (viewRow >= 0 && !table.isRowSelected(viewRow)) {
            table.setRowSelectionInterval(viewRow, viewRow);
        }
        buildContextMenu().show(e.getComponent(), e.getX(), e.getY());
    }

    private @NotNull JPopupMenu buildContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        List<Path> selection = selectedPaths();

        JMenuItem newFolder = new JMenuItem("New Folder…", AllIcons.Actions.NewFolder);
        newFolder.setEnabled(currentDir != null);
        newFolder.addActionListener(e -> promptNewFolder());
        menu.add(newFolder);

        menu.addSeparator();

        JMenuItem rename = new JMenuItem("Rename…", AllIcons.Actions.Edit);
        rename.setEnabled(selection.size() == 1);
        rename.addActionListener(e -> {
            if (selection.size() == 1) promptRename(selection.get(0));
        });
        menu.add(rename);

        JMenuItem delete = new JMenuItem("Delete", AllIcons.Actions.GC);
        delete.setEnabled(!selection.isEmpty());
        delete.addActionListener(e -> confirmAndDelete(selection));
        menu.add(delete);

        menu.addSeparator();

        JMenuItem copyPath = new JMenuItem("Copy Path", AllIcons.Actions.Copy);
        copyPath.setEnabled(!selection.isEmpty());
        copyPath.addActionListener(e -> copyPathsToClipboard(selection));
        menu.add(copyPath);

        if (transferActions != null) {
            menu.addSeparator();

            JMenuItem upload = new JMenuItem("Upload to " + transferActions.uploadTargetLabel(), AllIcons.Actions.Upload);
            upload.setEnabled(transferActions.canUpload());
            upload.addActionListener(e -> transferActions.uploadSelection());
            menu.add(upload);

            JMenuItem uploadToPath = new JMenuItem("Upload to path…", AllIcons.Actions.MenuOpen);
            uploadToPath.setEnabled(transferActions.canUploadToPath());
            uploadToPath.addActionListener(e -> chooseUploadPath());
            menu.add(uploadToPath);
        }

        menu.addSeparator();

        JMenuItem refresh = new JMenuItem("Refresh", AllIcons.Actions.Refresh);
        refresh.addActionListener(e -> refresh());
        menu.add(refresh);

        return menu;
    }

    private void promptNewFolder() {
        if (currentDir == null) return;
        String name = Messages.showInputDialog(this, "Folder name:", "New Folder", null);
        if (name == null || name.isBlank()) return;
        runOps("Create folder", () -> {
            LocalFileOps.mkdir(currentDir, name.trim());
            return null;
        });
    }

    private void promptRename(@NotNull Path source) {
        String suggested = source.getFileName() == null ? "" : source.getFileName().toString();
        String newName = Messages.showInputDialog(this, "New name:", "Rename", null, suggested, null);
        if (newName == null || newName.isBlank() || newName.equals(suggested)) return;
        runOps("Rename", () -> {
            LocalFileOps.rename(source, newName.trim());
            return null;
        });
    }

    private void confirmAndDelete(@NotNull List<Path> selection) {
        String message = selection.size() == 1
            ? "Delete " + selection.get(0).getFileName() + "?\nThis cannot be undone."
            : "Delete " + selection.size() + " items?\nThis cannot be undone.";
        int confirm = Messages.showYesNoDialog(this, message, "Delete", AllIcons.General.WarningDialog);
        if (confirm != Messages.YES) return;
        runOps("Delete", () -> {
            for (Path path : selection) {
                LocalFileOps.delete(path);
            }
            return null;
        });
    }

    private void copyPathsToClipboard(@NotNull List<Path> selection) {
        String joined = selection.stream()
            .map(Path::toString)
            .collect(Collectors.joining(System.lineSeparator()));
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(joined), null);
    }

    private void installTransferShortcut() {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK;
        KeyStroke key = KeyStroke.getKeyStroke(KeyEvent.VK_U, mask);
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, "termlab.sftp.upload");
        table.getActionMap().put("termlab.sftp.upload", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (transferActions != null && transferActions.canUpload()) {
                    transferActions.uploadSelection();
                }
            }
        });
    }

    private void chooseUploadPath() {
        if (transferActions != null && transferActions.canUploadToPath()) {
            transferActions.chooseAndUploadSelectionToPath();
        }
    }

    /**
     * Run {@code op} on the application executor, refresh on
     * success, show an error dialog on failure.
     */
    private void runOps(@NotNull String title, @NotNull ThrowingRunnable op) {
        AppExecutorUtil.getAppExecutorService().submit(() -> {
            Throwable failure = null;
            try {
                op.run();
            } catch (Throwable t) {
                failure = t;
            }
            Throwable finalFailure = failure;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (finalFailure != null) {
                    Messages.showErrorDialog(LocalFilePane.this,
                        title + " failed:\n" + finalFailure.getMessage(),
                        "Local File Operation");
                }
                refresh();
            });
        });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        Object run() throws Exception;
    }

    private @NotNull ActionToolbar buildToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new AnAction("Up", "Go to parent directory", AllIcons.Actions.MoveUp) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (currentDir != null && currentDir.getParent() != null) {
                    reload(currentDir.getParent());
                }
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        group.add(new AnAction("Refresh", "Refresh directory", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (currentDir != null) reload(currentDir);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        group.add(new AnAction("Go Home", "Go to user home directory", AllIcons.Nodes.HomeFolder) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                reload(Paths.get(System.getProperty("user.home")));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        group.add(new ToggleAction(
            "Show Hidden Files",
            "Show or hide hidden files and folders in the local pane",
            AllIcons.Actions.ToggleVisibility
        ) {
            @Override
            public boolean isSelected(@NotNull AnActionEvent e) {
                return showHiddenFiles;
            }

            @Override
            public void setSelected(@NotNull AnActionEvent e, boolean state) {
                setShowHiddenFiles(state);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        ActionToolbar toolbar = ActionManager.getInstance()
            .createActionToolbar("TermLabSftpLocal", group, true);
        toolbar.setTargetComponent(this);
        return toolbar;
    }

    private void captureColumns() {
        for (int column = 0; column < model.getColumnCount(); column++) {
            columns[column] = table.getColumnModel().getColumn(column);
        }
    }

    private void installColumnLayoutPersistence() {
        applyPersistedColumnWidths();
        table.getColumnModel().addColumnModelListener(new TableColumnModelListener() {
            @Override
            public void columnAdded(TableColumnModelEvent e) {
            }

            @Override
            public void columnRemoved(TableColumnModelEvent e) {
            }

            @Override
            public void columnMoved(TableColumnModelEvent e) {
            }

            @Override
            public void columnSelectionChanged(javax.swing.event.ListSelectionEvent e) {
            }

            @Override
            public void columnMarginChanged(ChangeEvent e) {
                JTableHeader header = table.getTableHeader();
                if (header == null || header.getResizingColumn() == null) return;
                persistColumnWidths();
            }
        });
    }

    private void applyPersistedColumnWidths() {
        for (int column = 0; column < model.getColumnCount(); column++) {
            int width = config.getColumnWidth(TABLE_PANE, column);
            if (width <= 0) continue;
            columns[column].setPreferredWidth(width);
            columns[column].setWidth(width);
        }
    }

    private void persistColumnWidths() {
        Enumeration<TableColumn> columns = table.getColumnModel().getColumns();
        while (columns.hasMoreElements()) {
            TableColumn column = columns.nextElement();
            config.setColumnWidth(TABLE_PANE, column.getModelIndex(), column.getWidth());
        }
    }

    private void installHeaderPopupMenu() {
        JTableHeader header = table.getTableHeader();
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowHeaderPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowHeaderPopup(e);
            }
        });
    }

    private void maybeShowHeaderPopup(@NotNull MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        ActionPopupMenu popupMenu = buildHeaderMenu();
        popupMenu.setTargetComponent(table);
        popupMenu.getComponent().show(e.getComponent(), e.getX(), e.getY());
    }

    private @NotNull ActionPopupMenu buildHeaderMenu() {
        DefaultActionGroup group = new DefaultActionGroup();
        int visibleCount = table.getColumnModel().getColumnCount();
        for (int column = 0; column < model.getColumnCount(); column++) {
            final int modelColumn = column;
            group.add(new ToggleAction(model.getColumnName(modelColumn)) {
                @Override
                public boolean isSelected(@NotNull AnActionEvent e) {
                    return isColumnVisible(modelColumn);
                }

                @Override
                public void setSelected(@NotNull AnActionEvent e, boolean state) {
                    setColumnVisible(modelColumn, state);
                }

                @Override
                public void update(@NotNull AnActionEvent e) {
                    super.update(e);
                    e.getPresentation().setEnabled(isColumnVisible(modelColumn) ? visibleCount > 1 : true);
                }

                @Override
                public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.EDT;
                }
            });
        }
        return ActionManager.getInstance().createActionPopupMenu("TermLabSftpLocalHeader", group);
    }

    private void applyPersistedColumnVisibility() {
        for (int column = model.getColumnCount() - 1; column >= 0; column--) {
            if (!config.isColumnVisible(TABLE_PANE, column)) {
                hideColumn(column);
            }
        }
    }

    private boolean isColumnVisible(int modelColumn) {
        return findVisibleColumn(modelColumn) != null;
    }

    private void setColumnVisible(int modelColumn, boolean visible) {
        boolean changed = visible ? showColumn(modelColumn) : hideColumn(modelColumn);
        config.setColumnVisible(TABLE_PANE, modelColumn, changed ? visible : isColumnVisible(modelColumn));
    }

    private boolean showColumn(int modelColumn) {
        if (isColumnVisible(modelColumn)) return false;
        TableColumn column = columns[modelColumn];
        table.addColumn(column);
        int targetIndex = 0;
        for (int i = 0; i < modelColumn; i++) {
            if (isColumnVisible(i)) {
                targetIndex++;
            }
        }
        int lastIndex = table.getColumnModel().getColumnCount() - 1;
        if (targetIndex < lastIndex) {
            table.getColumnModel().moveColumn(lastIndex, targetIndex);
        }
        int width = config.getColumnWidth(TABLE_PANE, modelColumn);
        if (width > 0) {
            column.setPreferredWidth(width);
            column.setWidth(width);
        }
        return true;
    }

    private boolean hideColumn(int modelColumn) {
        if (table.getColumnModel().getColumnCount() <= 1) return false;
        TableColumn column = findVisibleColumn(modelColumn);
        if (column == null) return false;
        config.setColumnWidth(TABLE_PANE, modelColumn, column.getWidth());
        table.removeColumn(column);
        return true;
    }

    private TableColumn findVisibleColumn(int modelColumn) {
        Enumeration<TableColumn> columns = table.getColumnModel().getColumns();
        while (columns.hasMoreElements()) {
            TableColumn column = columns.nextElement();
            if (column.getModelIndex() == modelColumn) {
                return column;
            }
        }
        return null;
    }

    private @NotNull Path initialDirectory() {
        String saved = TermLabSftpConfig.getInstance().getLastLocalPath();
        if (saved != null) {
            try {
                Path path = Paths.get(saved);
                if (Files.isDirectory(path)) return path;
            } catch (RuntimeException ignored) {
            }
        }
        return Paths.get(System.getProperty("user.home"));
    }

    private void onRowActivated(int viewRow) {
        if (viewRow < 0 || currentDir == null) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        var entry = model.getEntryAt(modelRow);
        if (!(entry instanceof LocalFileEntry local)) return;
        if (local.isDirectory()) {
            reload(local.path());
            return;
        }
        var openers = LocalFileOpener.EP_NAME.getExtensionList();
        if (openers.isEmpty()) return;
        openers.get(0).open(project, local);
    }

    private void onPathFieldSubmit() {
        String text = pathField.getText() == null ? "" : pathField.getText().trim();
        if (text.isEmpty()) {
            // Restore the displayed path on empty submit — avoids
            // silently resetting to the current dir with no feedback.
            if (currentDir != null) pathField.setText(currentDir.toString());
            return;
        }
        Path target;
        try {
            target = Paths.get(text);
        } catch (InvalidPathException e) {
            Messages.showErrorDialog(LocalFilePane.this,
                "Invalid path:\n" + text + "\n\n" + e.getMessage(),
                "Local Directory Error");
            return;
        }
        if (!Files.isDirectory(target)) {
            Messages.showErrorDialog(LocalFilePane.this,
                "Not a directory:\n" + target,
                "Local Directory Error");
            return;
        }
        reload(target);
    }

    private void reload(@NotNull Path dir) {
        fileListCache.invalidate();
        pathField.setText(dir.toString());
        AppExecutorUtil.getAppExecutorService().submit(() -> {
            List<LocalFileEntry> entries = new ArrayList<>();
            IOException error = null;
            try (Stream<Path> stream = Files.list(dir)) {
                Iterator<Path> it = stream.iterator();
                while (it.hasNext()) {
                    try {
                        Path path = it.next();
                        if (!SftpVisibilityFilters.shouldShowLocal(path, showHiddenFiles)) continue;
                        entries.add(LocalFileEntry.of(path));
                    } catch (IOException ignored) {
                        // skip unreadable entry but keep going
                    }
                }
            } catch (IOException e) {
                error = e;
            }
            List<LocalFileEntry> snapshot = entries;
            IOException finalError = error;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (finalError != null) {
                    Messages.showErrorDialog(LocalFilePane.this,
                        "Could not list directory:\n" + dir + "\n\n" + finalError.getMessage(),
                        "Local Directory Error");
                    return;
                }
                currentDir = dir;
                model.setEntries(snapshot);
                TermLabSftpConfig.getInstance().setLastLocalPath(dir.toString());
                for (Runnable listener : directoryListeners) listener.run();
            });
        });
    }

    @SuppressWarnings("unused")
    public @NotNull Project getProject() {
        return project;
    }

    /** Directory currently displayed, or {@code null} before the first reload. */
    public @org.jetbrains.annotations.Nullable Path currentDirectory() {
        return currentDir;
    }

    public void focusPathField() {
        pathField.requestFocusInWindow();
        pathField.selectAll();
    }

    public @NotNull FileListCache fileListCache() {
        return fileListCache;
    }

    /** Files / directories the user has selected. Returned paths are absolute. */
    public @NotNull List<Path> selectedPaths() {
        int[] viewRows = table.getSelectedRows();
        List<Path> paths = new ArrayList<>(viewRows.length);
        for (int viewRow : viewRows) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            var entry = model.getEntryAt(modelRow);
            if (entry instanceof LocalFileEntry local) {
                paths.add(local.path());
            }
        }
        return paths;
    }

    public void addSelectionListener(@NotNull ListSelectionListener listener) {
        table.getSelectionModel().addListSelectionListener(listener);
    }

    public void addDirectoryChangeListener(@NotNull Runnable listener) {
        directoryListeners.add(listener);
    }

    public void setTransferActions(@NotNull TransferActions transferActions) {
        this.transferActions = transferActions;
    }

    public void setShowHiddenFiles(boolean showHiddenFiles) {
        if (this.showHiddenFiles == showHiddenFiles) return;
        this.showHiddenFiles = showHiddenFiles;
        config.setShowHiddenLocalFiles(showHiddenFiles);
        refresh();
        IdeFocusManager.getInstance(project).requestFocus(table, true);
    }

    /** Reload the currently displayed directory if there is one. */
    public void refresh() {
        if (currentDir != null) reload(currentDir);
    }

    public interface TransferActions {
        boolean canUpload();
        boolean canUploadToPath();
        @NotNull String uploadTargetLabel();
        void uploadSelection();
        void uploadSelectionTo(@NotNull String remotePath);
        void chooseAndUploadSelectionToPath();
    }
}
