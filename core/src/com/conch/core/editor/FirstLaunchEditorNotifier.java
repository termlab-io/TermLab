package com.conch.core.editor;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One-time first-launch notification for the opt-in light editor.
 * Runs on the first project-open that sees no prior
 * {@code conch.editor.firstLaunchHandled} flag and presents the
 * user with an Enable / Not now / Don't ask again choice.
 */
public final class FirstLaunchEditorNotifier implements ProjectActivity {

    public static final String FIRST_LAUNCH_KEY = "conch.editor.firstLaunchHandled";
    private static final String NOTIFICATION_GROUP_ID = "com.conch.editor.firstLaunch";

    public static final PluginId EDITOR_PLUGIN_ID = PluginId.getId("com.conch.editor");
    public static final PluginId TEXTMATE_PLUGIN_ID = PluginId.getId("org.jetbrains.plugins.textmate");

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        PropertiesComponent props = PropertiesComponent.getInstance();
        if (props.getBoolean(FIRST_LAUNCH_KEY, false)) return Unit.INSTANCE;

        Notification n = new Notification(
            NOTIFICATION_GROUP_ID,
            "Light scripting & file editing",
            "Conch Workbench can provide an editor-like environment for light"
                + " scripting and remote file editing. Enable it?",
            NotificationType.INFORMATION);

        n.addAction(new NotificationAction("Enable") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                enablePluginsAndRestart();
                props.setValue(FIRST_LAUNCH_KEY, true);
                notification.expire();
            }
        });
        n.addAction(new NotificationAction("Not now") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                notification.expire();
            }
        });
        n.addAction(new NotificationAction("Don't ask again") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                props.setValue(FIRST_LAUNCH_KEY, true);
                notification.expire();
            }
        });

        Notifications.Bus.notify(n, project);
        return Unit.INSTANCE;
    }

    public static void enablePluginsAndRestart() {
        PluginManagerCore.enablePlugin(EDITOR_PLUGIN_ID);
        PluginManagerCore.enablePlugin(TEXTMATE_PLUGIN_ID);
        ApplicationManager.getApplication().invokeLater(() ->
            ApplicationManagerEx.getApplicationEx().restart(true));
    }

    public static void disablePluginsAndRestart() {
        PluginManagerCore.disablePlugin(EDITOR_PLUGIN_ID);
        PluginManagerCore.disablePlugin(TEXTMATE_PLUGIN_ID);
        ApplicationManager.getApplication().invokeLater(() ->
            ApplicationManagerEx.getApplicationEx().restart(true));
    }

    public static boolean isEditorEnabled() {
        return !PluginManagerCore.isDisabled(EDITOR_PLUGIN_ID);
    }
}
