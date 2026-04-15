package com.conch.sftp.vfs;

import com.conch.sftp.client.SshSftpSession;
import com.conch.sftp.session.SftpSessionManager;
import com.conch.ssh.client.SshConnectException;
import com.conch.ssh.model.HostStore;
import com.conch.ssh.model.SshHost;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
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

    private static final Logger LOG = Logger.getInstance(SftpVirtualFileSystem.class);

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

    @Override
    public @Nullable VirtualFile findFileByPath(@NotNull String path) {
        // path here is the URL-without-protocol form: "<hostId>//<absolute-remote-path>"
        // VirtualFileManager.findFileByUrl strips the "sftp://" prefix before calling us.
        SftpUrl parsed = SftpUrl.parse(SftpUrl.PROTOCOL + "://" + path);
        if (parsed == null) return null;

        String key = parsed.hostId() + parsed.remotePath();
        SftpVirtualFile cached = instances.get(key);
        if (cached != null && cached.isValid()) return cached;

        SshHost host = lookupHost(parsed.hostId());
        if (host == null) return null;

        SshSftpSession session;
        try {
            session = SftpSessionManager.getInstance().acquire(host, this);
        } catch (SshConnectException e) {
            LOG.warn("findFileByPath could not acquire session for " + host.label(), e);
            return null;
        }

        // We hold a session reference for the lifetime of the VFS instance.
        // The reference is never released; sessions are torn down by:
        // (a) explicit forceDisconnect from the SFTP tool window
        // (b) IDE shutdown via SftpSessionManager.dispose()

        SftpVirtualFile vf = new SftpVirtualFile(this, parsed.hostId(), parsed.remotePath(), session, /*isDirectory=*/false);
        // Remote-stat to determine isDirectory and existence.
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

    private static @Nullable SshHost lookupHost(@NotNull UUID hostId) {
        HostStore store = ApplicationManager.getApplication().getService(HostStore.class);
        if (store == null) return null;
        for (SshHost host : store.getHosts()) {
            if (host.id().equals(hostId)) return host;
        }
        return null;
    }
}
