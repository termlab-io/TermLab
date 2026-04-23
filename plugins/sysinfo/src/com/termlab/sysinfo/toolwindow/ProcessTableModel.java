package com.termlab.sysinfo.toolwindow;

import com.termlab.sysinfo.model.ProcessInfo;
import org.jetbrains.annotations.NotNull;

import javax.swing.table.AbstractTableModel;
import java.util.List;

final class ProcessTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"PID", "User", "CPU %", "Mem %", "RSS", "VSZ", "Command"};
    private List<ProcessInfo> processes = List.of();

    void setProcesses(@NotNull List<ProcessInfo> processes) {
        this.processes = List.copyOf(processes);
        fireTableDataChanged();
    }

    @NotNull ProcessInfo processAt(int row) {
        return processes.get(row);
    }

    @Override
    public int getRowCount() {
        return processes.size();
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
            case 0, 4, 5 -> Long.class;
            case 2, 3 -> Double.class;
            default -> String.class;
        };
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        ProcessInfo process = processes.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> process.pid();
            case 1 -> process.user();
            case 2 -> process.cpuPercent();
            case 3 -> process.memoryPercent();
            case 4 -> process.rssKb();
            case 5 -> process.vszKb();
            case 6 -> process.command();
            default -> "";
        };
    }
}
