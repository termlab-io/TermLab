package com.termlab.search;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicInteger;

final class FileSearchStatusProgress extends Task.Backgroundable {

    private static final AtomicInteger ACTIVE_TASKS = new AtomicInteger();

    @FunctionalInterface
    interface ProgressWork {
        void run(@NotNull ProgressIndicator indicator);
    }

    private final ProgressWork work;

    FileSearchStatusProgress(
        @NotNull Project project,
        @NotNull String title,
        @NotNull ProgressWork work
    ) {
        super(project, title, true);
        this.work = work;
    }

    @Override
    public void run(@NotNull ProgressIndicator indicator) {
        ACTIVE_TASKS.incrementAndGet();
        indicator.setIndeterminate(true);
        indicator.setText(getTitle());
        ApplicationManager.getApplication().invokeLater(() -> StatusBar.Info.set(getTitle(), getProject()));
        work.run(indicator);
    }

    @Override
    public void onFinished() {
        int remaining = ACTIVE_TASKS.decrementAndGet();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (remaining > 0) {
                StatusBar.Info.set("Indexing files…", getProject());
            } else {
                StatusBar.Info.set("", getProject());
            }
        });
    }

    static @NotNull String localTitle(@NotNull String root) {
        return "Indexing files in " + root;
    }

    static @NotNull String remoteTitle(@NotNull String host) {
        return "Indexing files on " + host;
    }
}
