package com.conch.core.filepicker.ui;

import com.conch.core.filepicker.FileEntry;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableModel;
import java.awt.*;

/**
 * Swing cell renderer for the Name column in both the local and
 * remote SFTP panes. Looks the row up in the shared
 * {@link FileTableModel} (converting the view index through
 * {@link JTable#convertRowIndexToModel} so it works under a
 * {@link javax.swing.table.TableRowSorter}) and picks a platform
 * icon from {@link AllIcons} / {@link FileTypeManager}.
 *
 * <p>Icons:
 * <ul>
 *   <li>Directory → {@link AllIcons.Nodes#Folder}</li>
 *   <li>Symlink → {@link AllIcons.Nodes#Symlink} (overrides file-type)</li>
 *   <li>File → {@code FileTypeManager.getFileTypeByFileName(name).getIcon()},
 *       falling back to a generic file icon when no registered file
 *       type matches. The lookup is purely name-based, so this
 *       works identically against a remote host even though no VFS
 *       exists.</li>
 * </ul>
 */
public final class FileNameCellRenderer extends DefaultTableCellRenderer {

    public FileNameCellRenderer() {
        setIconTextGap(JBUI.scale(4));
        setBorder(JBUI.Borders.empty(0, 4));
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
        setIcon(pickIcon(table.getModel(), modelRow));
        return this;
    }

    private static @NotNull Icon pickIcon(@NotNull TableModel model, int modelRow) {
        if (!(model instanceof FileTableModel fileModel)) {
            return AllIcons.FileTypes.Unknown;
        }
        FileEntry entry = fileModel.getEntryAt(modelRow);
        if (entry == null) {
            return AllIcons.FileTypes.Unknown;
        }
        if (entry.isDirectory()) {
            return AllIcons.Nodes.Folder;
        }
        if (entry.isSymlink()) {
            return AllIcons.Nodes.Symlink;
        }
        FileType type = FileTypeManager.getInstance().getFileTypeByFileName(entry.name());
        Icon icon = type.getIcon();
        return icon != null ? icon : AllIcons.FileTypes.Any_type;
    }
}
