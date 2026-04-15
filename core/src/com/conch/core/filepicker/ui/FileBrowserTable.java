package com.conch.core.filepicker.ui;

import com.conch.core.filepicker.FileEntry;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.TransferHandler;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Reusable file browser widget: a sortable table with Name / Size /
 * Modified / Permissions columns backed by a {@link FileTableModel}.
 * Used by the unified file picker dialog and by the SFTP tool window
 * panes.
 *
 * <p>Consumers:
 * <ul>
 *   <li>{@link #setEntries} to populate the list</li>
 *   <li>{@link #getSelectedEntry} to read the current selection</li>
 *   <li>{@link #addDoubleClickListener} to handle row activation</li>
 *   <li>{@link #addSelectionListener} to react to selection changes</li>
 *   <li>{@link #getComponent} to embed the widget into a larger layout</li>
 * </ul>
 */
public final class FileBrowserTable {

    private final FileTableModel model = new FileTableModel();
    private final JBTable table = new JBTable(model);
    private final JScrollPane scrollPane;
    private final CopyOnWriteArrayList<Consumer<FileEntry>> doubleClickListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> selectionListeners = new CopyOnWriteArrayList<>();

    /**
     * Create a widget with {@link ListSelectionModel#SINGLE_SELECTION}
     * — appropriate for picker dialogs where only one item is chosen.
     * Use {@link #FileBrowserTable(int)} to override.
     */
    public FileBrowserTable() {
        this(ListSelectionModel.SINGLE_SELECTION, null);
    }

    /**
     * Create a widget with the given selection mode (one of the
     * {@link ListSelectionModel} constants). The SFTP tool-window panes
     * use {@link ListSelectionModel#MULTIPLE_INTERVAL_SELECTION} so that
     * multi-file delete / DnD work correctly.
     */
    public FileBrowserTable(int selectionMode) {
        this(selectionMode, null);
    }

    /**
     * Create a widget with the given selection mode and an optional
     * {@link TransferHandler} for drag-and-drop. Passing the handler here
     * ensures that {@code dragEnabled}, {@code dropMode}, and
     * {@code transferHandler} are all set before the terminal
     * {@link javax.swing.JComponent#updateUI()} call that re-installs the
     * L&amp;F delegate. Some L&amp;F implementations (e.g. the IntelliJ
     * Darcula/New UI delegate) cache drag-recognition state at
     * {@code installUI} time; passing {@code null} here is equivalent to
     * calling the two-argument constructor.
     */
    public FileBrowserTable(int selectionMode, @Nullable TransferHandler transferHandler) {
        // Configure drag/drop FIRST, before any other table setup. The
        // BasicTableUI may check these properties at install time and we
        // want them in their final state as early as possible.
        table.setDragEnabled(true);
        table.setDropMode(DropMode.ON);
        if (transferHandler != null) {
            table.setTransferHandler(transferHandler);
        }

        table.setAutoResizeMode(JBTable.AUTO_RESIZE_LAST_COLUMN);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().setSelectionMode(selectionMode);
        table.getColumnModel().getColumn(FileTableModel.COL_NAME)
            .setCellRenderer(new FileNameCellRenderer());
        table.getColumnModel().getColumn(FileTableModel.COL_SIZE)
            .setCellRenderer(new SizeCellRenderer());
        table.getColumnModel().getColumn(FileTableModel.COL_MODIFIED)
            .setCellRenderer(new ModifiedCellRenderer());

        TableRowSorter<FileTableModel> sorter = new TableRowSorter<>(model);
        sorter.setSortsOnUpdates(true);
        table.setRowSorter(sorter);

        // Install the double-click listener AFTER drag setup so
        // BasicTableUI's MouseInputHandler is already registered.
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    int viewRow = table.getSelectedRow();
                    FileEntry entry = entryAtViewRow(viewRow);
                    if (entry != null) {
                        for (Consumer<FileEntry> listener : doubleClickListeners) {
                            listener.accept(entry);
                        }
                    }
                }
            }
        });

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                for (Runnable listener : selectionListeners) {
                    listener.run();
                }
            }
        });

        scrollPane = new JScrollPane(table);

        // Force BasicTableUI to uninstall + reinstall after all properties
        // are set. Some L&F implementations cache dragEnabled / transferHandler
        // state at install time; the retroactive flag flips don't affect the
        // already-installed MouseInputHandler. updateUI() triggers a full
        // re-install with dragEnabled=true and transferHandler in place.
        table.updateUI();
    }

    public void setEntries(@NotNull List<? extends FileEntry> entries) {
        model.setEntries(entries);
    }

    public @Nullable FileEntry getSelectedEntry() {
        return entryAtViewRow(table.getSelectedRow());
    }

    public void addDoubleClickListener(@NotNull Consumer<FileEntry> listener) {
        doubleClickListeners.add(listener);
    }

    public void addSelectionListener(@NotNull Runnable listener) {
        selectionListeners.add(listener);
    }

    /**
     * The embeddable component. Put this into your layout wherever you
     * want the table to appear.
     */
    public @NotNull JComponent getComponent() {
        return scrollPane;
    }

    /**
     * Direct access to the underlying {@link JBTable}. Use for
     * advanced configuration not exposed by the higher-level API
     * methods on this class.
     */
    public @NotNull JBTable getTable() {
        return table;
    }

    private @Nullable FileEntry entryAtViewRow(int viewRow) {
        if (viewRow < 0) return null;
        int modelRow = table.convertRowIndexToModel(viewRow);
        return model.getEntryAt(modelRow);
    }
}
