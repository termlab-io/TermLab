package com.termlab.proxmox.toolwindow;

import com.termlab.proxmox.model.PveGuest;
import org.jetbrains.annotations.NotNull;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

public final class PveGuestTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
        "Type", "Name", "VMID", "Node", "Status", "CPU %", "Memory", "Disk", "Uptime"
    };

    private final List<PveGuest> guests = new ArrayList<>();

    public void setGuests(@NotNull List<PveGuest> updated) {
        guests.clear();
        guests.addAll(updated);
        fireTableDataChanged();
    }

    public @NotNull PveGuest guestAt(int row) {
        return guests.get(row);
    }

    public @NotNull List<PveGuest> guests() {
        return List.copyOf(guests);
    }

    @Override
    public int getRowCount() {
        return guests.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return switch (columnIndex) {
            case 2 -> Integer.class;
            case 5 -> Double.class;
            default -> String.class;
        };
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        PveGuest guest = guests.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> guest.type().displayName();
            case 1 -> guest.name();
            case 2 -> guest.vmid();
            case 3 -> guest.node();
            case 4 -> guest.status().displayName();
            case 5 -> guest.cpuPercent();
            case 6 -> formatBytes(guest.memoryBytes()) + " / " + formatBytes(guest.maxMemoryBytes());
            case 7 -> formatBytes(guest.diskBytes()) + " / " + formatBytes(guest.maxDiskBytes());
            case 8 -> formatDuration(guest.uptimeSeconds());
            default -> "";
        };
    }

    static @NotNull String formatBytes(long bytes) {
        if (bytes <= 0) return "--";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return unit <= 1 ? String.format("%.0f %s", value, units[unit]) : String.format("%.1f %s", value, units[unit]);
    }

    static @NotNull String formatDuration(long seconds) {
        if (seconds <= 0) return "--";
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }
}
