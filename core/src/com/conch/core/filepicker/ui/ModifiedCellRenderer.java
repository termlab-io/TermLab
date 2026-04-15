package com.conch.core.filepicker.ui;

import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Formats an {@link Instant} column value as
 * {@code yyyy-MM-dd HH:mm} in the system default zone. Sorting uses
 * the raw {@code Instant}, so this renderer is purely cosmetic.
 */
public final class ModifiedCellRenderer extends DefaultTableCellRenderer {

    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public ModifiedCellRenderer() {
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
        setText(value instanceof Instant instant ? format(instant) : "");
        return this;
    }

    private static @NotNull String format(@NotNull Instant instant) {
        return TIME_FORMAT.format(instant);
    }
}
