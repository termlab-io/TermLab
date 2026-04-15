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
        this(ListSelectionModel.SINGLE_SELECTION);
    }

    /**
     * Create a widget with the given selection mode (one of the
     * {@link ListSelectionModel} constants). The SFTP tool-window panes
     * use {@link ListSelectionModel#MULTIPLE_INTERVAL_SELECTION} so that
     * multi-file delete / DnD work correctly.
     */
    public FileBrowserTable(int selectionMode) {
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

        // Enable drag BEFORE adding any further mouse listeners and BEFORE
        // wrapping in a JScrollPane.  Swing's BasicTableUI.MouseInputHandler
        // reads the dragEnabled property during mousePressed to decide whether
        // to hand control to the DragRecognitionSupport machinery or fall back
        // to plain row-selection.  If the JScrollPane is constructed (which
        // internally triggers addNotify / updateUI on the viewport peer) before
        // dragEnabled is set, the L&F mouse handler is wired up in
        // "selection-only" mode and a later setDragEnabled(true) call cannot
        // retro-fit the drag recogniser — the flag is checked at event time,
        // but the handler's internal pressed-point bookkeeping has already been
        // initialised without drag awareness.  Setting it here, before both the
        // JScrollPane construction and our own addMouseListener call, restores
        // the ordering that the pre-refactor panes used and that DnD requires.
        table.setDragEnabled(true);

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
     * Configure this table as a drag-and-drop source and target.
     * Sets {@link DropMode#ON} and the given {@link TransferHandler} on
     * the underlying table.  {@code dragEnabled} is already set to
     * {@code true} in the constructor (it must be set before the internal
     * mouse listener and the JScrollPane are created); this method only
     * supplies the drop mode and the transfer handler, which can safely be
     * set at any point before the widget is shown.
     *
     * <p>Call this method instead of reaching through {@link #getTable()}
     * to configure DnD individually.
     */
    public void enableDragAndDrop(@NotNull TransferHandler handler) {
        table.setDropMode(DropMode.ON);
        table.setTransferHandler(handler);
    }

    /**
     * The embeddable component. Put this into your layout wherever you
     * want the table to appear.
     */
    public @NotNull JComponent getComponent() {
        return scrollPane;
    }

    /**
     * Direct access to the underlying {@link JBTable}. Used by the
     * existing SFTP tool window panes for DnD source/target setup.
     * New callers should prefer the higher-level API methods on this
     * class (e.g. {@link #enableDragAndDrop}).
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
