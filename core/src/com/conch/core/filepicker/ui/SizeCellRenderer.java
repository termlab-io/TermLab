package com.conch.core.filepicker.ui;

import com.conch.core.filepicker.FileEntry;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableModel;
import java.awt.*;

/**
 * Right-aligned human-readable size column renderer. Pulls the
 * typed {@code Long} value from the model so sorting still compares
 * numerically, but displays it as "KB / MB / GB" in the cell.
 * Directories render as blank.
 */
public final class SizeCellRenderer extends DefaultTableCellRenderer {

    public SizeCellRenderer() {
        setHorizontalAlignment(SwingConstants.RIGHT);
        setBorder(JBUI.Borders.empty(0, 6));
    }

    @Override
    public Component getTableCellRendererComponent(
        JTable table,
        Object value,
        boolean isSelected,
        boolean hasFocus,
        int row,
        int column
    ) {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        int modelRow = table.convertRowIndexToModel(row);
        TableModel raw = table.getModel();
        if (raw instanceof FileTableModel model) {
            FileEntry entry = model.getEntryAt(modelRow);
            if (entry != null && entry.isDirectory()) {
                setText("");
                return this;
            }
        }
        if (value instanceof Long bytes) {
            setText(formatSize(bytes));
        } else if (value == null) {
            setText("");
        }
        return this;
    }

    private static @NotNull String formatSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
