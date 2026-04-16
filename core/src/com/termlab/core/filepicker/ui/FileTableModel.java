package com.termlab.core.filepicker.ui;

import com.termlab.core.filepicker.FileEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.table.AbstractTableModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 4-column raw-data table model: Name, Size, Modified, Permissions.
 * {@link #getValueAt} returns typed values (String / Long / Instant)
 * so Swing's {@link javax.swing.table.TableRowSorter} can pick up
 * default {@link Comparable} comparators keyed on
 * {@link #getColumnClass}. Cell renderers installed by the panes
 * format those values for display.
 *
 * <p>Entries are pre-sorted directories-first, then alphabetically,
 * so the unsorted view — the state Swing shows when no column
 * header is clicked — matches typical file-browser expectations.
 * Clicking a column header then overrides with a value-typed sort,
 * and clicking again reverses direction or drops back to the
 * unsorted default.
 */
public final class FileTableModel extends AbstractTableModel {

    public static final int COL_NAME = 0;
    public static final int COL_SIZE = 1;
    public static final int COL_MODIFIED = 2;
    public static final int COL_PERMISSIONS = 3;

    private static final String[] COLUMN_NAMES = {"Name", "Size", "Modified", "Permissions"};

    private List<FileEntry> entries = List.of();

    public void setEntries(@NotNull List<? extends FileEntry> newEntries) {
        List<FileEntry> sorted = new ArrayList<>(newEntries);
        sorted.sort(Comparator
            .comparing((FileEntry e) -> !e.isDirectory())
            .thenComparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER));
        this.entries = sorted;
        fireTableDataChanged();
    }

    public @Nullable FileEntry getEntryAt(int row) {
        if (row < 0 || row >= entries.size()) return null;
        return entries.get(row);
    }

    @Override
    public int getRowCount() {
        return entries.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMN_NAMES.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMN_NAMES[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return switch (columnIndex) {
            case COL_NAME, COL_PERMISSIONS -> String.class;
            case COL_SIZE -> Long.class;
            case COL_MODIFIED -> Instant.class;
            default -> Object.class;
        };
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        FileEntry entry = entries.get(rowIndex);
        return switch (columnIndex) {
            case COL_NAME -> entry.name();
            // Directories get 0L so they cluster at the small-size
            // end rather than mixed in with real file sizes. The
            // size renderer hides the value entirely for directories.
            case COL_SIZE -> entry.isDirectory() ? 0L : entry.size();
            case COL_MODIFIED -> entry.modified();
            case COL_PERMISSIONS -> entry.permissions();
            default -> "";
        };
    }
}
