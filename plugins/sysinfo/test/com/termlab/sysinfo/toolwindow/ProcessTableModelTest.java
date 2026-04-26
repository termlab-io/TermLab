package com.termlab.sysinfo.toolwindow;

import com.termlab.sysinfo.model.ProcessInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessTableModelTest {

    @Test
    void commandColumnShowsExecutableName() {
        ProcessTableModel model = new ProcessTableModel();
        model.setProcesses(List.of(
            new ProcessInfo(1, "root", 0.0, 0.0, 10, 20, "/usr/bin/demo worker")
        ));

        assertEquals("demo", model.getValueAt(0, 6));
    }

    @Test
    void displayNameHandlesMacAppPathsAndQuotedCommands() {
        assertEquals("App Name", ProcessTableModel.displayName("/Applications/App Name"));
        assertEquals("tool.exe", ProcessTableModel.displayName("\"C:\\Program Files\\Tool\\tool.exe\" --flag"));
    }

    @Test
    void displayNameHandlesWindowsPaths() {
        assertEquals("conhost.exe", ProcessTableModel.displayName("C:\\Windows\\System32\\conhost.exe 0xffffffff"));
    }
}
