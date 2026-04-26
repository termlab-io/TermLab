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
            case 6 -> displayName(process.command());
            default -> "";
        };
    }

    static @NotNull String displayName(@NotNull String command) {
        String trimmed = command.trim();
        if (trimmed.isEmpty()) return "";

        String executable = firstCommandToken(trimmed);
        int slash = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        String name = slash >= 0 ? executable.substring(slash + 1) : executable;
        return name.isBlank() ? trimmed : name;
    }

    private static @NotNull String firstCommandToken(@NotNull String command) {
        char first = command.charAt(0);
        if (first == '"' || first == '\'') {
            int closing = command.indexOf(first, 1);
            if (closing > 1) {
                return command.substring(1, closing);
            }
        }

        if (command.startsWith("/Applications/") && !command.contains(".app/")) {
            return command;
        }

        int whitespace = -1;
        for (int i = 0; i < command.length(); i++) {
            if (Character.isWhitespace(command.charAt(i))) {
                whitespace = i;
                break;
            }
        }
        return whitespace > 0 ? command.substring(0, whitespace) : command;
    }
}
