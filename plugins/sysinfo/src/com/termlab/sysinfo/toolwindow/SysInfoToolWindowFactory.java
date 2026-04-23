package com.termlab.sysinfo.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class SysInfoToolWindowFactory implements ToolWindowFactory {

    public static final String ID = "SysInfo";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        SysInfoToolWindow panel = new SysInfoToolWindow(project, toolWindow);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setCloseable(false);
        toolWindow.getContentManager().addContent(content);
    }
}
