package com.termlab.sftp.session;

import com.termlab.sftp.client.SshSftpSession;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.credentials.HostCredentialBundle;
import com.termlab.ssh.model.SshHost;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.application.ModalityState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Application service that owns SFTP sessions per host UUID. Reference-counted
 * lifecycle: a session is closed only when its last consumer releases it.
 * Both the SFTP tool window and the SFTP virtual file system register as
 * consumers via {@link #acquire(SshHost, Object)}.
 */
@Service(Service.Level.APP)
public final class SftpSessionManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(SftpSessionManager.class);

    private final SftpConnector connector;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<UUID, SessionEntry> entries = new HashMap<>();

    private static final class SessionEntry {
        final SshSftpSession session;
        final Set<Object> owners = new HashSet<>();

        SessionEntry(SshSftpSession session) {
            this.session = session;
        }
    }

    /** Production constructor used by the IntelliJ service container. */
    @SuppressWarnings("unused")
    public SftpSessionManager() {
        this(new DefaultSftpConnector());
    }

    /** Test-only constructor for injecting a fake connector. */
    public SftpSessionManager(@NotNull SftpConnector connector) {
        this.connector = connector;
    }

    public static @NotNull SftpSessionManager getInstance() {
        return com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(SftpSessionManager.class);
    }

    /** Returns an existing session for the host, or null if none. Non-blocking. */
    public @Nullable SshSftpSession peek(@NotNull UUID hostId) {
        lock.lock();
        try {
            SessionEntry entry = entries.get(hostId);
            return entry == null ? null : entry.session;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an existing session if connected, or opens a new one and
     * registers the owner. Blocks the calling thread on first connect;
     * safe to call from a background executor or under modal progress.
     * Caller MUST eventually call {@link #release(UUID, Object)} with the
     * same owner reference.
     */
    public @NotNull SshSftpSession acquire(@NotNull SshHost host, @NotNull Object owner)
        throws SshConnectException
    {
        // Fast path: already connected.
        lock.lock();
        try {
            SessionEntry entry = entries.get(host.id());
            if (entry != null) {
                entry.owners.add(owner);
                return entry.session;
            }
        } finally {
            lock.unlock();
        }

        // Slow path: open the session OUTSIDE the lock so other threads can
        // acquire/release for different hosts in parallel.
        HostCredentialBundle bundle = resolveCredentialsOnEdt(host);
        if (bundle == null) {
            throw new SshConnectException(
                SshConnectException.Kind.AUTH_FAILED,
                "Could not resolve credentials for " + host.label());
        }
        SshSftpSession session = connector.open(host, bundle);

        lock.lock();
        try {
            // Another thread may have raced us and installed an entry.
            SessionEntry existing = entries.get(host.id());
            if (existing != null) {
                // Discard our duplicate session.
                try { session.close(); } catch (Throwable ignored) {}
                existing.owners.add(owner);
                return existing.session;
            }
            SessionEntry entry = new SessionEntry(session);
            entry.owners.add(owner);
            entries.put(host.id(), entry);
            return session;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Decrements the refcount for {@code (hostId, owner)}. When the last
     * owner releases, the session is closed and removed from the cache.
     */
    public void release(@NotNull UUID hostId, @NotNull Object owner) {
        SshSftpSession toClose = null;
        lock.lock();
        try {
            SessionEntry entry = entries.get(hostId);
            if (entry == null) return;
            entry.owners.remove(owner);
            if (entry.owners.isEmpty()) {
                entries.remove(hostId);
                toClose = entry.session;
            }
        } finally {
            lock.unlock();
        }
        if (toClose != null) {
            try { toClose.close(); }
            catch (Throwable t) { LOG.warn("Failed to close SFTP session", t); }
        }
    }

    /**
     * Force-disconnect a host, ignoring refcounts. Editor tabs holding
     * stale references will fail their next operation.
     */
    public void forceDisconnect(@NotNull UUID hostId) {
        SshSftpSession toClose = null;
        lock.lock();
        try {
            SessionEntry entry = entries.remove(hostId);
            if (entry != null) {
                toClose = entry.session;
            }
        } finally {
            lock.unlock();
        }
        if (toClose != null) {
            try { toClose.close(); }
            catch (Throwable t) { LOG.warn("Failed to force-close SFTP session", t); }
        }
    }

    public @NotNull Set<UUID> connectedHostIds() {
        lock.lock();
        try {
            return new HashSet<>(entries.keySet());
        } finally {
            lock.unlock();
        }
    }

    public record ActiveSession(
        @NotNull com.termlab.ssh.model.SshHost host,
        @NotNull com.termlab.sftp.client.SshSftpSession session,
        @NotNull String currentRemotePath
    ) {}

    /**
     * Returns the SFTP tool window's currently-connected session for the
     * given project, or null if no SFTP tool window is open or no session
     * is connected. Used by SaveScratchToRemoteAction to skip the host
     * picker when the user is already working with a host.
     *
     * <p>NOTE: this method reaches into the SFTP tool window's UI state.
     * It works because there is exactly one SFTP tool window per project.
     * If a future feature lets users have multiple SFTP panes open
     * simultaneously, this needs revisiting.
     */
    public @Nullable ActiveSession getActiveSessionForCurrentProject(
        @NotNull com.intellij.openapi.project.Project project
    ) {
        com.intellij.openapi.wm.ToolWindow tw =
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("SFTP");
        if (tw == null) return null;
        var contents = tw.getContentManager().getContents();
        for (var content : contents) {
            var component = content.getComponent();
            if (component instanceof com.termlab.sftp.toolwindow.SftpToolWindow toolWindow) {
                com.termlab.sftp.toolwindow.RemoteFilePane pane = toolWindow.remotePane();
                com.termlab.sftp.client.SshSftpSession session = pane.activeSession();
                com.termlab.ssh.model.SshHost host = pane.currentHost();
                String path = pane.currentRemotePath();
                if (session != null && host != null && path != null) {
                    return new ActiveSession(host, session, path);
                }
            }
        }
        return null;
    }

    /**
     * Resolve credentials for {@code host} on the EDT. Uses
     * {@link ApplicationManager#invokeAndWait} to hop to the EDT when
     * called from a background thread (e.g., from inside a
     * {@link com.intellij.openapi.progress.Task.Modal#run} body).
     * Credential resolution may show a vault unlock dialog, which
     * requires the EDT.
     */
    private @Nullable HostCredentialBundle resolveCredentialsOnEdt(@NotNull SshHost host) {
        com.intellij.openapi.application.Application app =
            com.intellij.openapi.application.ApplicationManager.getApplication();
        if (app == null || app.isDispatchThread()) {
            // Null-app path: unit tests run without an Application.
            // EDT path: no hop needed.
            return connector.resolveCredentials(host);
        }
        java.util.concurrent.atomic.AtomicReference<HostCredentialBundle> ref =
            new java.util.concurrent.atomic.AtomicReference<>();
        app.invokeAndWait(() -> ref.set(connector.resolveCredentials(host)), ModalityState.any());
        return ref.get();
    }

    @Override
    public void dispose() {
        lock.lock();
        try {
            for (SessionEntry entry : entries.values()) {
                try { entry.session.close(); }
                catch (Throwable t) { LOG.warn("Failed to close SFTP session on dispose", t); }
            }
            entries.clear();
        } finally {
            lock.unlock();
        }
    }
}
