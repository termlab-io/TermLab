package com.termlab.sftp.vfs;

import com.termlab.sftp.client.SshSftpSession;
import com.termlab.sftp.session.SftpSessionManager;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.SshHost;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Virtual file system for SFTP-hosted files. Single instance, registered
 * under protocol {@code sftp}, multi-host routing via {@link SftpUrl}.
 *
 * <p>Extends {@link DeprecatedVirtualFileSystem} (the supported base for
 * non-{@code ManagingFS}-integrated VFS implementations — the class name is
 * misleading; the class is not actually marked deprecated).
 */
public final class SftpVirtualFileSystem extends DeprecatedVirtualFileSystem {

    /** Hash-cons cache: same (hostId, remotePath) → same SftpVirtualFile instance. */
    private final Map<String, SftpVirtualFile> instances = new ConcurrentHashMap<>();

    public static @NotNull SftpVirtualFileSystem getInstance() {
        return (SftpVirtualFileSystem) VirtualFileManager.getInstance().getFileSystem(SftpUrl.PROTOCOL);
    }

    public SftpVirtualFileSystem() {
        startEventPropagation();
    }

    @Override
    public @NotNull String getProtocol() {
        return SftpUrl.PROTOCOL;
    }

    /**
     * Resolves a VFS path to an {@link SftpVirtualFile}. This method is
     * strictly passive: it only succeeds when some other consumer (the SFTP
     * tool window or the save action) has already acquired a session for the
     * target host via {@link SftpSessionManager#acquire}. If no session is
     * currently held, this method returns {@code null}.
     *
     * <p>This design prevents spurious auto-connections at startup (e.g. when
     * {@code EditorHistoryManager.loadState} tries to resolve stale
     * {@code sftp://} history entries), which would otherwise trigger
     * credential dialogs from a worker thread and crash with
     * "Access is allowed from Event Dispatch Thread (EDT) only".
     */
    @Override
    public @Nullable VirtualFile findFileByPath(@NotNull String path) {
        // path here is the URL-without-protocol form: "<hostId>//<absolute-remote-path>"
        // VirtualFileManager.findFileByUrl strips the "sftp://" prefix before calling us.
        SftpUrl parsed = SftpUrl.parse(SftpUrl.PROTOCOL + "://" + path);
        if (parsed == null) return null;

        String key = parsed.hostId() + parsed.remotePath();
        SftpVirtualFile cached = instances.get(key);
        if (cached != null && cached.isValid()) return cached;

        SshSftpSession session = SftpSessionManager.getInstance().peek(parsed.hostId());
        if (session == null) {
            // Don't auto-connect from findFileByPath. The VFS is passive:
            // it only works when some other consumer (the SFTP tool window
            // or the save action) has already acquired a session for this
            // host. Returning null here means stale editor-history entries
            // for disconnected hosts are silently dropped — the platform
            // handles null gracefully.
            return null;
        }

        SftpVirtualFile vf = new SftpVirtualFile(this, parsed.hostId(), parsed.remotePath(), session, /*isDirectory=*/false);
        if (!vf.statAndUpdate()) {
            return null;
        }
        instances.putIfAbsent(key, vf);
        return instances.get(key);
    }

    @Override
    public @Nullable VirtualFile refreshAndFindFileByPath(@NotNull String path) {
        // Invalidate any cached entry for this path, then re-resolve.
        SftpUrl parsed = SftpUrl.parse(SftpUrl.PROTOCOL + "://" + path);
        if (parsed != null) {
            String key = parsed.hostId() + parsed.remotePath();
            SftpVirtualFile cached = instances.remove(key);
            if (cached != null) cached.invalidate();
        }
        return findFileByPath(path);
    }

    @Override
    public void refresh(boolean asynchronous) {
        Runnable work = () -> {
            for (SftpVirtualFile vf : instances.values()) {
                vf.clearChildrenCache();
                vf.statAndUpdate();
            }
        };
        if (asynchronous) {
            com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(work);
        } else {
            work.run();
        }
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public @NotNull String extractPresentableUrl(@NotNull String path) {
        SftpUrl parsed = SftpUrl.parse(SftpUrl.PROTOCOL + "://" + path);
        if (parsed == null) return path;
        SshHost host = lookupHost(parsed.hostId());
        String label = host != null ? host.label() : parsed.hostId().toString();
        return label + ":" + parsed.remotePath();
    }

    /**
     * Internal: invoked by SftpVirtualFile when it discovers a file no
     * longer exists during refresh.
     */
    void evict(@NotNull SftpVirtualFile vf) {
        instances.remove(vf.hostId() + vf.remotePath());
    }

    /**
     * Internal: invoked by SftpVirtualFile to install newly-discovered
     * children in the hash-cons cache.
     */
    @NotNull SftpVirtualFile interned(@NotNull SftpVirtualFile fresh) {
        String key = fresh.hostId() + fresh.remotePath();
        SftpVirtualFile existing = instances.putIfAbsent(key, fresh);
        return existing != null ? existing : fresh;
    }

    void fireContentsChangedExternally(@NotNull SftpVirtualFile file) {
        // Use the inherited helper from DeprecatedVirtualFileSystem.
        // Note: we pass 0 for the old timestamp because we don't track
        // pre-change values; consumers shouldn't rely on it.
        // The EventDispatcher requires write access; wrap in a write action.
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .runWriteAction(() -> fireContentsChanged(/*requestor=*/null, file, 0));
    }

    private static @Nullable SshHost lookupHost(@NotNull UUID hostId) {
        HostStore store = ApplicationManager.getApplication().getService(HostStore.class);
        if (store == null) return null;
        for (SshHost host : store.getHosts()) {
            if (host.id().equals(hostId)) return host;
        }
        return null;
    }
}
