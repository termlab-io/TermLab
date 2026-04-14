package com.conch.editor.remote;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.util.concurrency.AppExecutorUtil;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runs once per project open when the editor plugin is enabled.
 * Sweeps the temp root to remove orphaned files from previous
 * crashed or force-quit sessions.
 *
 * <p>Guarded against multiple sweeps in the same session by a
 * simple atomic flag on the {@link RemoteEditService}.
 */
public final class RemoteEditorStartupSweep implements ProjectActivity {

    private static volatile boolean sweptThisSession = false;

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (sweptThisSession) return Unit.INSTANCE;
        sweptThisSession = true;
        AppExecutorUtil.getAppExecutorService().submit(() -> {
            RemoteEditService service = ApplicationManager.getApplication()
                .getService(RemoteEditService.class);
            if (service != null) {
                RemoteEditorCleanup.purgeRoot(service.tempRoot());
            }
        });
        return Unit.INSTANCE;
    }
}
