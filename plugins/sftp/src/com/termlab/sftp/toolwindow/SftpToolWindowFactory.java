package com.termlab.sftp.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

public final class SftpToolWindowFactory implements ToolWindowFactory {

    public static final String ID = "SFTP";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        SftpToolWindow panel = new SftpToolWindow(project, toolWindow);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setCloseable(false);
        toolWindow.getContentManager().addContent(content);
    }

    public static @Nullable SftpToolWindow find(@NotNull Project project) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            AtomicReference<SftpToolWindow> result = new AtomicReference<>();
            ApplicationManager.getApplication().invokeAndWait(() -> result.set(find(project)));
            return result.get();
        }

        ToolWindow toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(ID);
        if (toolWindow == null) return null;
        ContentManager contentManager = toolWindow.getContentManagerIfCreated();
        if (contentManager == null) return null;
        Content content = contentManager.getSelectedContent();
        if (content == null) return null;
        return content.getComponent() instanceof SftpToolWindow panel ? panel : null;
    }
}
