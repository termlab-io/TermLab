package com.termlab.runner.output;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.termlab.runner.execution.ScriptExecution;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Main tabbed container for script execution output.
 */
public final class ScriptOutputPanel extends JPanel {

    private static final int MAX_TABS = 10;

    private final Project project;
    private final JTabbedPane tabbedPane;
    private final List<OutputTab> tabs = new ArrayList<>();

    public ScriptOutputPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        add(tabbedPane, BorderLayout.CENTER);
    }

    public @NotNull OutputTab addExecution(
        @NotNull ScriptExecution execution,
        @NotNull String filename,
        @NotNull String hostLabel,
        @NotNull String interpreter
    ) {
        String title = filename + " @ " + hostLabel;
        int existingIndex = findTabIndex(title);
        if (existingIndex < 0) {
            evictOldTabs();
        }

        OutputTab tab = new OutputTab(execution, interpreter, hostLabel);
        if (existingIndex >= 0) {
            OutputTab existing = tabs.get(existingIndex);
            if (existing.execution().isRunning()) {
                existing.execution().kill();
            }
            tabs.set(existingIndex, tab);
            tabbedPane.remove(existingIndex);
            tabbedPane.insertTab(title, null, tab, null, existingIndex);
        } else {
            tabs.add(tab);
            tabbedPane.addTab(title, tab);
        }

        int index = tabbedPane.indexOfComponent(tab);
        tabbedPane.setTabComponentAt(index, createTabLabel(title, tab));
        tabbedPane.setSelectedComponent(tab);
        return tab;
    }

    private int findTabIndex(@NotNull String title) {
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (title.equals(tabbedPane.getTitleAt(i))) {
                return i;
            }
        }
        return -1;
    }

    public boolean selectExecution(@NotNull String filename, @NotNull String hostLabel) {
        return selectExecution(filename + " @ " + hostLabel);
    }

    public boolean selectExecution(@NotNull String title) {
        int index = findTabIndex(title);
        if (index < 0) {
            return false;
        }
        tabbedPane.setSelectedIndex(index);
        return true;
    }

    private @NotNull JPanel createTabLabel(@NotNull String title, @NotNull OutputTab tab) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        panel.add(new JLabel(title));

        JButton stopButton = new JButton(AllIcons.Actions.Suspend);
        stopButton.setToolTipText("Stop");
        stopButton.setBorderPainted(false);
        stopButton.setContentAreaFilled(false);
        stopButton.setPreferredSize(new Dimension(16, 16));
        stopButton.addActionListener(e -> {
            if (tab.execution().isRunning()) {
                tab.execution().sendInterrupt();
                stopButton.setToolTipText("Force Kill");
                if (stopButton.getActionListeners().length > 0) {
                    stopButton.removeActionListener(stopButton.getActionListeners()[0]);
                }
                stopButton.addActionListener(e2 -> tab.execution().kill());
            }
        });
        tab.execution().addTerminationListener(() -> SwingUtilities.invokeLater(() -> {
            stopButton.setEnabled(false);
            stopButton.setToolTipText("Finished");
        }));
        panel.add(stopButton);

        JButton closeButton = new JButton(AllIcons.Actions.Close);
        closeButton.setToolTipText("Close");
        closeButton.setBorderPainted(false);
        closeButton.setContentAreaFilled(false);
        closeButton.setPreferredSize(new Dimension(16, 16));
        closeButton.addActionListener(e -> {
            if (tab.execution().isRunning()) {
                tab.execution().kill();
            }
            tabs.remove(tab);
            tabbedPane.remove(tab);
        });
        panel.add(closeButton);

        return panel;
    }

    private void evictOldTabs() {
        while (tabs.size() >= MAX_TABS) {
            OutputTab toRemove = null;
            for (OutputTab tab : tabs) {
                if (!tab.execution().isRunning()) {
                    toRemove = tab;
                    break;
                }
            }
            if (toRemove == null) {
                break;
            }
            tabs.remove(toRemove);
            tabbedPane.remove(toRemove);
        }
    }
}
