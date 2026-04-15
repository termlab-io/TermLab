package com.conch.sftp.vfs;

import com.conch.sftp.client.SshSftpSession;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileSystem;
import org.apache.sshd.sftp.client.SftpClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Single VFS file backed by a remote path on an SFTP host. Owns its own
 * per-instance attribute and listing caches; the platform's
 * {@code ManagingFS} is not involved.
 */
public final class SftpVirtualFile extends VirtualFile {

    private static final Logger LOG = Logger.getInstance(SftpVirtualFile.class);

    private final SftpVirtualFileSystem fs;
    private final UUID hostId;
    private final String remotePath;
    private final SshSftpSession session;

    private volatile boolean isDirectory;
    private volatile long length = -1;
    private volatile long timestamp = 0;
    private final java.util.concurrent.atomic.AtomicLong modificationStamp =
        new java.util.concurrent.atomic.AtomicLong(0);
    private volatile boolean valid = true;
    private volatile SftpVirtualFile parent;
    private volatile VirtualFile[] cachedChildren;

    public SftpVirtualFile(
        @NotNull SftpVirtualFileSystem fs,
        @NotNull UUID hostId,
        @NotNull String remotePath,
        @NotNull SshSftpSession session,
        boolean isDirectoryHint
    ) {
        this.fs = fs;
        this.hostId = hostId;
        this.remotePath = remotePath;
        this.session = session;
        this.isDirectory = isDirectoryHint;
    }

    public @NotNull UUID hostId() { return hostId; }
    @NotNull String remotePath() { return remotePath; }

    /**
     * Stat the remote file and update cached attributes. Returns false if
     * the file does not exist (and evicts the instance from the VFS cache).
     */
    boolean statAndUpdate() {
        try {
            SftpClient.Attributes attrs = session.client().stat(remotePath);
            if (attrs == null) {
                fs.evict(this);
                valid = false;
                return false;
            }
            this.isDirectory = attrs.isDirectory();
            this.length = attrs.getSize();
            if (attrs.getModifyTime() != null) {
                this.timestamp = attrs.getModifyTime().toMillis();
            }
            this.modificationStamp.incrementAndGet();
            return true;
        } catch (IOException e) {
            LOG.warn("Stat failed for " + remotePath + ": " + e.getMessage());
            fs.evict(this);
            valid = false;
            return false;
        }
    }

    void invalidate() {
        valid = false;
        cachedChildren = null;
    }

    void clearChildrenCache() {
        cachedChildren = null;
    }

    // ------------ Identity / metadata --------------------------------------

    @Override
    public @NotNull String getName() {
        int slash = remotePath.lastIndexOf('/');
        if (slash < 0 || slash == remotePath.length() - 1) {
            return remotePath;
        }
        return remotePath.substring(slash + 1);
    }

    @Override
    public @NotNull String getPath() {
        return hostId + "/" + remotePath;
    }

    @Override
    public @NotNull String getUrl() {
        return SftpUrl.compose(hostId, remotePath);
    }

    @Override
    public @NotNull VirtualFileSystem getFileSystem() {
        return fs;
    }

    @Override
    public boolean isWritable() {
        return true;
    }

    @Override
    public boolean isDirectory() {
        return isDirectory;
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    @Override
    public @Nullable VirtualFile getParent() {
        if (parent != null) return parent;
        if ("/".equals(remotePath)) return null;
        int slash = remotePath.lastIndexOf('/');
        String parentPath = (slash <= 0) ? "/" : remotePath.substring(0, slash);
        SftpVirtualFile freshParent = new SftpVirtualFile(fs, hostId, parentPath, session, /*isDirectoryHint=*/true);
        // Don't stat — assume the parent exists if this file does.
        SftpVirtualFile interned = fs.interned(freshParent);
        this.parent = interned;
        return interned;
    }

    @Override
    public long getTimeStamp() {
        return timestamp;
    }

    @Override
    public long getModificationStamp() {
        return modificationStamp.get();
    }

    @Override
    public long getLength() {
        if (length < 0) {
            statAndUpdate();
        }
        return Math.max(0, length);
    }

    // ------------ Children -------------------------------------------------

    @Override
    public VirtualFile[] getChildren() {
        if (!isDirectory) return VirtualFile.EMPTY_ARRAY;
        VirtualFile[] cached = cachedChildren;
        if (cached != null) return cached;

        List<VirtualFile> result = new ArrayList<>();
        try {
            for (SftpClient.DirEntry entry : session.client().readDir(remotePath)) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) continue;
                String childPath = remotePath.equals("/") ? "/" + name : remotePath + "/" + name;
                boolean dir = entry.getAttributes().isDirectory();
                SftpVirtualFile fresh = new SftpVirtualFile(fs, hostId, childPath, session, dir);
                long size = entry.getAttributes().getSize();
                fresh.length = size;
                if (entry.getAttributes().getModifyTime() != null) {
                    fresh.timestamp = entry.getAttributes().getModifyTime().toMillis();
                }
                SftpVirtualFile interned = fs.interned(fresh);
                result.add(interned);
            }
        } catch (IOException e) {
            LOG.warn("readDir failed for " + remotePath + ": " + e.getMessage());
            return VirtualFile.EMPTY_ARRAY;
        }
        VirtualFile[] arr = result.toArray(VirtualFile.EMPTY_ARRAY);
        cachedChildren = arr;
        return arr;
    }

    @Override
    public @Nullable VirtualFile findChild(@NotNull String name) {
        for (VirtualFile child : getChildren()) {
            if (child.getName().equals(name)) return child;
        }
        return null;
    }

    // ------------ Read -----------------------------------------------------

    @Override
    public byte @NotNull [] contentsToByteArray() throws IOException {
        try (InputStream in = session.client().read(remotePath);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    @Override
    public @NotNull InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(contentsToByteArray());
    }

    // ------------ Write (atomic .tmp + rename) -----------------------------

    @Override
    public @NotNull OutputStream getOutputStream(
        Object requestor, long newModificationStamp, long newTimeStamp
    ) throws IOException {
        return new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                super.close();
                byte[] bytes = this.toByteArray();
                writeAtomically(bytes);
                if (newModificationStamp >= 0) {
                    modificationStamp.set(newModificationStamp);
                } else {
                    modificationStamp.incrementAndGet();
                }
                if (newTimeStamp >= 0) {
                    timestamp = newTimeStamp;
                }
                length = bytes.length;
                // Notify VFS listeners that contents changed.
                fs.fireContentsChangedExternally(SftpVirtualFile.this);
            }
        };
    }

    private void writeAtomically(byte @NotNull [] content) throws IOException {
        SftpClient client = session.client();
        String randomSuffix = Long.toHexString(ThreadLocalRandom.current().nextLong());
        String writeTmp = remotePath + "." + randomSuffix + ".tmp";
        String backupTmp = remotePath + "." + randomSuffix + ".bak";

        // Step 1: write the new content to a sibling temp file.
        try {
            try (OutputStream out = client.write(writeTmp)) {
                out.write(content);
            }
        } catch (IOException e) {
            // Write failed; remove the partial temp and propagate.
            try { client.remove(writeTmp); } catch (IOException ignored) {}
            throw e;
        }

        // Step 2: try the simple atomic rename. POSIX SFTP servers
        // succeed even if the target exists.
        try {
            client.rename(writeTmp, remotePath);
            return;
        } catch (IOException renameErr) {
            LOG.warn("Atomic rename failed for " + remotePath
                + " (" + renameErr.getMessage() + "), falling back to backup+rename");
        }

        // Step 3: fallback for non-POSIX servers — back up the original,
        // then rename the new content into place. If anything fails,
        // restore from backup so the user never loses data.
        boolean backedUp = false;
        try {
            // 3a. Move original to a backup temp (atomic on the server).
            //     If the original doesn't exist, this fails; that's fine —
            //     we can skip straight to the final rename below.
            try {
                client.rename(remotePath, backupTmp);
                backedUp = true;
            } catch (IOException backupErr) {
                LOG.warn("Backup rename failed for " + remotePath
                    + " (" + backupErr.getMessage() + "); proceeding without backup");
            }
            // 3b. Move the new content into place.
            try {
                client.rename(writeTmp, remotePath);
            } catch (IOException finalRenameErr) {
                // Restore from backup if we made one.
                if (backedUp) {
                    try {
                        client.rename(backupTmp, remotePath);
                    } catch (IOException restoreErr) {
                        LOG.error("CRITICAL: failed to restore " + remotePath
                            + " from backup " + backupTmp
                            + ". Original content is at " + backupTmp
                            + ". New content is at " + writeTmp, restoreErr);
                    }
                }
                throw finalRenameErr;
            }
            // 3c. Success — remove the backup.
            if (backedUp) {
                try { client.remove(backupTmp); } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            // Clean up the write-tmp if it's still there.
            try { client.remove(writeTmp); } catch (IOException ignored) {}
            throw e;
        }
    }

    // ------------ Refresh --------------------------------------------------

    @Override
    public void refresh(boolean asynchronous, boolean recursive, @Nullable Runnable postRunnable) {
        Runnable work = () -> {
            cachedChildren = null;
            statAndUpdate();
            if (postRunnable != null) postRunnable.run();
        };
        if (asynchronous) {
            com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(work);
        } else {
            work.run();
        }
    }

    // ------------ equals / hashCode ----------------------------------------

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SftpVirtualFile other)) return false;
        return hostId.equals(other.hostId) && remotePath.equals(other.remotePath);
    }

    @Override
    public int hashCode() {
        return hostId.hashCode() * 31 + remotePath.hashCode();
    }

    @Override
    public String toString() {
        return "SftpVirtualFile{" + hostId + "//" + remotePath + "}";
    }
}
