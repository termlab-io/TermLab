package com.termlab.core.terminal;

import com.termlab.sdk.TerminalSessionProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManagerKeys;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

public final class TermLabTerminalVirtualFile extends LightVirtualFile {
    private static final long SESSION_RELEASE_GRACE_MS = 750L;

    private final String sessionId;
    private final TerminalSessionProvider provider;
    private String currentWorkingDirectory;
    private volatile String terminalTitle;
    private volatile boolean manualTitleOverride;
    private volatile TerminalSessionProvider.SessionContext sessionContext;
    private @Nullable SharedTerminalSession sharedSession;

    public TermLabTerminalVirtualFile(@NotNull String title,
                                     @NotNull TerminalSessionProvider provider) {
        super(title, TermLabTerminalFileType.INSTANCE, "");
        this.sessionId = UUID.randomUUID().toString();
        this.provider = provider;
        putUserData(FileEditorManagerKeys.FORBID_PREVIEW_TAB, true);
    }

    public @NotNull String getSessionId() { return sessionId; }
    public @NotNull TerminalSessionProvider getProvider() { return provider; }
    public @Nullable String getCurrentWorkingDirectory() { return currentWorkingDirectory; }
    public void setCurrentWorkingDirectory(@Nullable String cwd) { this.currentWorkingDirectory = cwd; }
    public @Nullable String getTerminalTitle() { return terminalTitle; }
    public void setTerminalTitle(@Nullable String title) { this.terminalTitle = title; }

    /**
     * Optional session context the provider should receive on
     * {@link TerminalSessionProvider#createSession(TerminalSessionProvider.SessionContext) createSession}.
     *
     * <p>Plugins like the SSH session provider stash a richer context
     * here (carrying an {@code SshHost}, for example) so the editor
     * doesn't have to know about the plugin's context subtype. When
     * unset, {@link #getSessionContext()} returns a default
     * CWD-only context derived from {@link #getCurrentWorkingDirectory()}.
     */
    public void setSessionContext(@Nullable TerminalSessionProvider.SessionContext context) {
        this.sessionContext = context;
    }

    /**
     * Resolve the context to pass to {@code createSession()}. Returns the
     * caller-supplied {@link #setSessionContext(TerminalSessionProvider.SessionContext)}
     * when set; otherwise a fresh lambda that exposes
     * {@link #getCurrentWorkingDirectory()}, falling back to the user's
     * home directory.
     */
    public @NotNull TerminalSessionProvider.SessionContext getSessionContext() {
        TerminalSessionProvider.SessionContext stashed = sessionContext;
        if (stashed != null) return stashed;
        String cwd = currentWorkingDirectory != null
            ? currentWorkingDirectory
            : System.getProperty("user.home");
        return () -> cwd;
    }

    /**
     * When {@code true}, OSC 0/2 title updates from the shell are ignored and
     * the current {@link #terminalTitle} stays pinned. Set by the Rename
     * action so a user-chosen tab name doesn't get clobbered the next time
     * the shell prompt repaints. Clear it again by renaming back to an empty
     * string, which also restores auto-titling.
     */
    public boolean hasManualTitleOverride() { return manualTitleOverride; }
    public void setManualTitleOverride(boolean manualTitleOverride) {
        this.manualTitleOverride = manualTitleOverride;
    }

    public synchronized @NotNull SharedTerminalSession acquireSession(@NotNull Project project) {
        SharedTerminalSession session = sharedSession;
        if (session == null || session.isDisposed()) {
            session = new SharedTerminalSession(project, this);
            sharedSession = session;
        }
        session.retain();
        return session;
    }

    synchronized void clearSharedSession(@NotNull SharedTerminalSession session) {
        if (sharedSession == session) {
            sharedSession = null;
        }
    }

    @Override public boolean isWritable() { return false; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TermLabTerminalVirtualFile other)) return false;
        return sessionId.equals(other.sessionId);
    }

    @Override
    public int hashCode() { return sessionId.hashCode(); }

    public static final class SharedTerminalSession {
        private final Project project;
        private final TermLabTerminalVirtualFile file;
        private final TermLabTerminalWidget widget;
        private final TtyConnector connector;
        private int retainCount;
        private boolean disposed;
        private boolean closeScheduled;
        private @Nullable ScheduledFuture<?> closeFuture;

        private SharedTerminalSession(@NotNull Project project, @NotNull TermLabTerminalVirtualFile file) {
            this.project = project;
            this.file = file;
            this.widget = new TermLabTerminalWidget(new TermLabTerminalSettings());

            TerminalSessionProvider.SessionContext context = file.getSessionContext();
            TtyConnector rawConnector = file.getProvider().createSession(context);
            if (rawConnector == null) {
                throw new IllegalStateException("Terminal session provider returned null connector");
            }

            this.connector = new OscTrackingTtyConnector(
                rawConnector,
                file::setCurrentWorkingDirectory,
                newTitle -> updateTabTitle(project, file, newTitle)
            );

            widget.createTerminalSession(connector);
            widget.start();
            startExitWatcher();
        }

        public @NotNull TermLabTerminalWidget getWidget() {
            return widget;
        }

        public @NotNull TtyConnector getConnector() {
            return connector;
        }

        public synchronized boolean isDisposed() {
            return disposed;
        }

        private synchronized void retain() {
            if (disposed) {
                throw new IllegalStateException("Terminal session already disposed");
            }
            retainCount++;
            closeScheduled = false;
            if (closeFuture != null) {
                closeFuture.cancel(false);
                closeFuture = null;
            }
        }

        public void release() {
            boolean deferClose = false;
            synchronized (this) {
                if (disposed) return;
                if (retainCount > 0) {
                    retainCount--;
                }
                if (retainCount > 0) {
                    return;
                }
                if (!closeScheduled) {
                    closeScheduled = true;
                    deferClose = true;
                }
            }

            if (deferClose) {
                ScheduledFuture<?> future = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    () -> ApplicationManager.getApplication().invokeLater(this::closeIfUnused, project.getDisposed()),
                    SESSION_RELEASE_GRACE_MS,
                    TimeUnit.MILLISECONDS);
                synchronized (this) {
                    if (disposed || retainCount > 0) {
                        future.cancel(false);
                    } else {
                        closeFuture = future;
                    }
                }
            }
        }

        private void closeIfUnused() {
            synchronized (this) {
                if (disposed) return;
                closeScheduled = false;
                closeFuture = null;
                if (retainCount > 0
                    || file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == Boolean.TRUE
                    || FileEditorManager.getInstance(project).isFileOpen(file)) {
                    return;
                }
            }
            disposeSession();
        }

        private void disposeSession() {
            synchronized (this) {
                if (disposed) return;
                disposed = true;
                if (closeFuture != null) {
                    closeFuture.cancel(false);
                    closeFuture = null;
                }
            }
            file.clearSharedSession(this);
            try {
                connector.close();
            } catch (Exception ignored) {
            }
        }

        private void startExitWatcher() {
            Thread watcher = new Thread(() -> {
                try {
                    connector.waitFor();
                } catch (InterruptedException ignored) {
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()
                        && !isDisposed()
                        && file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) != Boolean.TRUE) {
                        FileEditorManager.getInstance(project).closeFile(file);
                    }
                });
            }, "TermLab-exit-watcher-" + file.getSessionId());
            watcher.setDaemon(true);
            watcher.start();
        }

        private static void updateTabTitle(@NotNull Project project,
                                           @NotNull TermLabTerminalVirtualFile file,
                                           @Nullable String newTitle) {
            if (file.hasManualTitleOverride()) return;
            if (newTitle == null || newTitle.isBlank()) return;

            file.setTerminalTitle(newTitle);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    FileEditorManager.getInstance(project).updateFilePresentation(file);
                }
            });
        }
    }
}
