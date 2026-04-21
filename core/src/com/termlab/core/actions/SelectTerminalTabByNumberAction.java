package com.termlab.core.actions;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.termlab.core.terminal.TermLabTabNumberSupport;
import org.jetbrains.annotations.NotNull;

public final class SelectTerminalTabByNumberAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        int tabNumber = getTabNumber();
        if (tabNumber <= 0) {
            return;
        }

        EditorWindow window = TermLabTabNumberSupport.resolveWindow(project, e.getData(EditorWindow.DATA_KEY), null);
        if (window == null) {
            return;
        }

        VirtualFile[] files = window.getFiles();
        if (tabNumber > files.length) {
            return;
        }

        window.setAsCurrentWindow(true);
        window.setSelectedComposite(files[tabNumber - 1], true);
        window.requestFocus(true);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean enabled = false;
        if (project != null) {
            int tabNumber = getTabNumber();
            EditorWindow window = TermLabTabNumberSupport.resolveWindow(project, e.getData(EditorWindow.DATA_KEY), null);
            enabled = tabNumber > 0 && window != null && tabNumber <= window.getFiles().length;
        }
        e.getPresentation().setEnabled(enabled);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private int getTabNumber() {
        String actionId = ActionManager.getInstance().getId(this);
        if (actionId == null) {
            return -1;
        }

        int index = actionId.length() - 1;
        while (index >= 0 && Character.isDigit(actionId.charAt(index))) {
            index--;
        }
        if (index == actionId.length() - 1) {
            return -1;
        }
        return Integer.parseInt(actionId.substring(index + 1));
    }
}
