package com.termlab.runner.output;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class ScriptOutputToolWindowFactory implements ToolWindowFactory {

    public static final String ID = "Script Output";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ScriptOutputPanel panel = new ScriptOutputPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setCloseable(false);
        toolWindow.getContentManager().addContent(content);
    }
}
