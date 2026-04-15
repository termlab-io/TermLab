package com.conch.sftp.filepicker;

import com.conch.core.filepicker.FileEntry;
import com.conch.core.filepicker.FileSource;
import com.conch.sftp.client.SshSftpSession;
import com.conch.sftp.model.RemoteFileEntry;
import com.conch.sftp.session.SftpSessionManager;
import com.conch.sftp.vfs.AtomicSftpWrite;
import com.conch.ssh.client.SshConnectException;
import com.conch.ssh.model.SshHost;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import org.apache.sshd.sftp.client.SftpClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link FileSource} backed by a single SFTP host. One instance per
 * configured {@link SshHost}. Sessions are owned by
 * {@link SftpSessionManager}; this source acquires/releases them.
 */
public final class SftpFileSource implements FileSource {

    private final SshHost host;
    private volatile SshSftpSession session;
    private volatile String cachedInitialPath;

    public SftpFileSource(@NotNull SshHost host) {
        this.host = host;
    }

    @Override
    public @NotNull String id() {
        return "sftp:" + host.id();
    }

    @Override
    public @NotNull String label() {
        return host.label();
    }

    @Override
    public @NotNull Icon icon() {
        return AllIcons.Nodes.WebFolder;
    }

    @Override
    public @NotNull String initialPath() {
        String cached = cachedInitialPath;
        if (cached != null) return cached;
        if (session == null) {
            throw new IllegalStateException("initialPath() called before open() on " + id());
        }
        try {
            String canonical = session.client().canonicalPath(".");
            cachedInitialPath = (canonical == null || canonical.isBlank()) ? "/" : canonical;
        } catch (IOException e) {
            cachedInitialPath = "/";
        }
        return cachedInitialPath;
    }

    @Override
    public void open(@NotNull Project project, @NotNull Object owner) throws IOException {
        try {
            session = SftpSessionManager.getInstance().acquire(host, owner);
        } catch (SshConnectException e) {
            throw new IOException("Could not connect to " + host.label() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close(@NotNull Object owner) {
        SftpSessionManager.getInstance().release(host.id(), owner);
        session = null;
    }

    @Override
    public @NotNull List<FileEntry> list(@NotNull String absolutePath) throws IOException {
        SshSftpSession s = requireSession();
        List<FileEntry> result = new ArrayList<>();
        for (SftpClient.DirEntry entry : s.client().readDir(absolutePath)) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            result.add(RemoteFileEntry.of(entry));
        }
        return result;
    }

    @Override
    public boolean isDirectory(@NotNull String absolutePath) throws IOException {
        try {
            return requireSession().client().stat(absolutePath).isDirectory();
        } catch (IOException e) {
            if (isNotFound(e)) return false;
            throw e;
        }
    }

    @Override
    public boolean exists(@NotNull String absolutePath) throws IOException {
        try {
            requireSession().client().stat(absolutePath);
            return true;
        } catch (IOException e) {
            if (isNotFound(e)) return false;
            throw e;
        }
    }

    @Override
    public @Nullable String parentOf(@NotNull String absolutePath) {
        if ("/".equals(absolutePath) || absolutePath.isEmpty()) return null;
        String trimmed = absolutePath.endsWith("/")
            ? absolutePath.substring(0, absolutePath.length() - 1)
            : absolutePath;
        int slash = trimmed.lastIndexOf('/');
        if (slash <= 0) return "/";
        return trimmed.substring(0, slash);
    }

    @Override
    public @NotNull String resolve(@NotNull String directoryPath, @NotNull String childName) {
        if (directoryPath.endsWith("/")) return directoryPath + childName;
        return directoryPath + "/" + childName;
    }

    @Override
    public void writeFile(@NotNull String absolutePath, byte @NotNull [] content) throws IOException {
        AtomicSftpWrite.writeAtomically(requireSession().client(), absolutePath, content);
    }

    @Override
    public @NotNull InputStream readFile(@NotNull String absolutePath) throws IOException {
        return requireSession().client().read(absolutePath);
    }

    private @NotNull SshSftpSession requireSession() {
        SshSftpSession s = session;
        if (s == null) {
            throw new IllegalStateException(
                "SftpFileSource operation called before open() on " + id());
        }
        return s;
    }

    private static boolean isNotFound(@NotNull IOException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("no such file") || msg.contains("not found");
    }
}
