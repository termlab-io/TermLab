package com.conch.sftp.transfer;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.List;

/**
 * Copies files and directories between the local filesystem and a
 * remote SFTP host. Both directions share the same collision /
 * progress plumbing; the only per-direction difference is which
 * side is the source and which is the sink.
 *
 * <p>Directory sources recurse automatically — the engine walks the
 * tree on the source side, mirrors intermediate directories on the
 * destination side (ignoring collisions there), and prompts on
 * per-file conflicts through the supplied {@link CollisionResolver}.
 *
 * <p>Before any bytes move, the engine walks the source tree once
 * to sum up total bytes so the {@link ProgressIndicator} can run
 * in determinate mode. During the copy loop the indicator's
 * fraction, label, and "sub-label" are updated on a throttled
 * schedule (at most once every {@value #UI_UPDATE_INTERVAL_NANOS}
 * ns) so UI refresh doesn't become the bottleneck on fast links.
 *
 * <p>Cancellation is cooperative: every IO loop checks
 * {@link ProgressIndicator#checkCanceled()}, and the engine stops
 * at the next safe point if the user cancels the modal task.
 */
public final class TransferEngine {

    private static final Logger LOG = Logger.getInstance(TransferEngine.class);
    private static final int BUFFER_BYTES = 64 * 1024;
    /** Minimum interval between status-bar updates — ~60 fps is way more than humans can follow. */
    private static final long UI_UPDATE_INTERVAL_NANOS = 100_000_000L;

    private final SftpClient sftp;
    private final CollisionResolver collisions;
    private final ProgressIndicator indicator;

    // Mutable progress state. Set at the start of each batch by
    // upload() / download() and read / updated from the copy loop.
    private long totalBytes;
    /** Drives the status-bar fraction; includes "synthetic" advances on SKIP. */
    private long progressBytes;
    /** Bytes actually copied by the IO loop. Used for the final notification. */
    private long bytesCopied;
    /** Files actually copied (excludes skipped). Used for the final notification. */
    private int filesCopied;
    private long startNanos;
    private long lastUiUpdateNanos;
    private @NotNull String currentLabel = "";

    public TransferEngine(
        @NotNull SftpClient sftp,
        @NotNull CollisionResolver collisions,
        @NotNull ProgressIndicator indicator
    ) {
        this.sftp = sftp;
        this.collisions = collisions;
        this.indicator = indicator;
    }

    // -- Upload (local → remote) ---------------------------------------------

    /**
     * Copy every {@code source} (file or directory) into
     * {@code remoteDestDir} on the remote host. Returns a summary
     * covering the entire batch, including counters for files that
     * were actually copied (skipped files are not counted).
     */
    public @NotNull TransferResult upload(@NotNull List<Path> sources, @NotNull String remoteDestDir) throws IOException {
        beginBatch("Calculating size…");
        totalBytes = sumLocalSizes(sources);
        armDeterminate();

        for (Path source : sources) {
            if (collisions.isCancelled() || indicator.isCanceled()) break;
            uploadOne(source, remoteDestDir);
        }
        return finishBatch();
    }

    private void uploadOne(@NotNull Path source, @NotNull String remoteDestDir) throws IOException {
        String destPath = joinRemote(remoteDestDir, source.getFileName().toString());
        BasicFileAttributes attrs = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isDirectory()) {
            ensureRemoteDir(destPath);
            try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
                for (Path child : children) {
                    if (collisions.isCancelled() || indicator.isCanceled()) break;
                    uploadOne(child, destPath);
                }
            }
            return;
        }
        if (attrs.isSymbolicLink()) {
            // Phase 2 skips symlinks — don't dereference, don't copy.
            LOG.info("SFTP upload: skipping symlink " + source);
            return;
        }
        uploadFile(source, destPath, attrs.size());
    }

    private void uploadFile(@NotNull Path source, @NotNull String destPath, long sourceSize) throws IOException {
        indicator.checkCanceled();
        currentLabel = "Uploading " + source.getFileName();
        indicator.setText(currentLabel);
        updateProgress(/* force = */ true);

        Long existing = remoteSizeIfExists(destPath);
        EnumSet<SftpClient.OpenMode> modes;
        if (existing == null) {
            modes = EnumSet.of(
                SftpClient.OpenMode.Write,
                SftpClient.OpenMode.Create,
                SftpClient.OpenMode.Truncate);
        } else {
            CollisionDecision decision = collisions.resolve(destPath, sourceSize, existing);
            switch (decision) {
                case OVERWRITE -> modes = EnumSet.of(
                    SftpClient.OpenMode.Write,
                    SftpClient.OpenMode.Create,
                    SftpClient.OpenMode.Truncate);
                case RENAME -> {
                    String renamed = nextRemoteAvailable(destPath);
                    uploadFile(source, renamed, sourceSize);
                    return;
                }
                case SKIP -> {
                    LOG.info("SFTP upload: skipping " + destPath + " (user declined overwrite)");
                    // Advance the bar so the skipped file doesn't
                    // leave a gap at the end of the batch.
                    progressBytes += sourceSize;
                    updateProgress(/* force = */ true);
                    return;
                }
                case CANCEL -> throw new ProcessCanceledException();
                default -> throw new IllegalStateException("unexpected decision " + decision);
            }
        }

        try (InputStream in = Files.newInputStream(source, StandardOpenOption.READ);
             OutputStream out = sftp.write(destPath, BUFFER_BYTES, modes)) {
            copy(in, out);
        }
        filesCopied++;
    }

    // -- Download (remote → local) -------------------------------------------

    public @NotNull TransferResult download(@NotNull List<String> remoteSources, @NotNull Path localDestDir) throws IOException {
        Files.createDirectories(localDestDir);
        beginBatch("Calculating size…");
        totalBytes = sumRemoteSizes(remoteSources);
        armDeterminate();

        for (String source : remoteSources) {
            if (collisions.isCancelled() || indicator.isCanceled()) break;
            downloadOne(source, localDestDir);
        }
        return finishBatch();
    }

    private void downloadOne(@NotNull String remoteSource, @NotNull Path localDestDir) throws IOException {
        String name = baseName(remoteSource);
        Path destPath = localDestDir.resolve(name);
        SftpClient.Attributes attrs = sftp.stat(remoteSource);
        if (attrs.isDirectory()) {
            Files.createDirectories(destPath);
            for (SftpClient.DirEntry entry : sftp.readDir(remoteSource)) {
                if (collisions.isCancelled() || indicator.isCanceled()) break;
                String childName = entry.getFilename();
                if (".".equals(childName) || "..".equals(childName)) continue;
                downloadOne(joinRemote(remoteSource, childName), destPath);
            }
            return;
        }
        if ((attrs.getPermissions() & SftpConstants.S_IFMT) == SftpConstants.S_IFLNK) {
            LOG.info("SFTP download: skipping symlink " + remoteSource);
            return;
        }
        downloadFile(remoteSource, destPath, attrs.getSize());
    }

    private void downloadFile(@NotNull String remoteSource, @NotNull Path destPath, long sourceSize) throws IOException {
        indicator.checkCanceled();
        currentLabel = "Downloading " + destPath.getFileName();
        indicator.setText(currentLabel);
        updateProgress(/* force = */ true);

        if (Files.exists(destPath, LinkOption.NOFOLLOW_LINKS)) {
            long existingSize = Files.isRegularFile(destPath, LinkOption.NOFOLLOW_LINKS)
                ? Files.size(destPath)
                : 0L;
            CollisionDecision decision = collisions.resolve(destPath.toString(), sourceSize, existingSize);
            switch (decision) {
                case OVERWRITE -> { /* fall through to copy with REPLACE_EXISTING */ }
                case RENAME -> {
                    Path renamed = nextLocalAvailable(destPath);
                    downloadFile(remoteSource, renamed, sourceSize);
                    return;
                }
                case SKIP -> {
                    LOG.info("SFTP download: skipping " + destPath + " (user declined overwrite)");
                    progressBytes += sourceSize;
                    updateProgress(/* force = */ true);
                    return;
                }
                case CANCEL -> throw new ProcessCanceledException();
                default -> throw new IllegalStateException("unexpected decision " + decision);
            }
        }

        try (InputStream in = sftp.read(remoteSource);
             OutputStream out = Files.newOutputStream(destPath,
                 StandardOpenOption.CREATE,
                 StandardOpenOption.TRUNCATE_EXISTING,
                 StandardOpenOption.WRITE)) {
            copy(in, out);
        }
        filesCopied++;
    }

    // -- progress bookkeeping -------------------------------------------------

    private void beginBatch(@NotNull String label) {
        indicator.setIndeterminate(true);
        indicator.setText(label);
        indicator.setText2("");
        totalBytes = 0L;
        progressBytes = 0L;
        bytesCopied = 0L;
        filesCopied = 0;
        startNanos = System.nanoTime();
        lastUiUpdateNanos = 0L;
        currentLabel = label;
    }

    private @NotNull TransferResult finishBatch() {
        return new TransferResult(
            filesCopied,
            bytesCopied,
            totalBytes,
            System.nanoTime() - startNanos
        );
    }

    private void armDeterminate() {
        indicator.setIndeterminate(totalBytes <= 0);
        if (totalBytes > 0) {
            indicator.setFraction(0.0);
        }
        // Reset the clock so the speed readout measures the copy
        // phase, not the pre-walk that just finished.
        startNanos = System.nanoTime();
        lastUiUpdateNanos = 0L;
        updateProgress(/* force = */ true);
    }

    private void updateProgress(boolean force) {
        long now = System.nanoTime();
        if (!force && (now - lastUiUpdateNanos) < UI_UPDATE_INTERVAL_NANOS) return;
        lastUiUpdateNanos = now;

        if (!currentLabel.isEmpty()) {
            indicator.setText(currentLabel);
        }

        if (totalBytes > 0) {
            double fraction = (double) progressBytes / (double) totalBytes;
            indicator.setFraction(Math.min(1.0, Math.max(0.0, fraction)));
        }

        long elapsedNanos = Math.max(1L, now - startNanos);
        double seconds = elapsedNanos / 1_000_000_000.0;
        // Use bytesCopied for the speed readout — it reflects real wire
        // throughput, whereas progressBytes is inflated by skip advances.
        long speedBytesPerSec = seconds > 0 ? (long) (bytesCopied / seconds) : 0L;

        StringBuilder sub = new StringBuilder();
        sub.append(formatBytes(progressBytes));
        if (totalBytes > 0) {
            sub.append(" / ").append(formatBytes(totalBytes));
            int percent = (int) Math.round(100.0 * progressBytes / totalBytes);
            sub.append("  (").append(Math.min(100, Math.max(0, percent))).append("%)");
        }
        sub.append("  •  ").append(formatBytes(speedBytesPerSec)).append("/s");
        if (totalBytes > 0 && speedBytesPerSec > 0 && progressBytes < totalBytes) {
            long remaining = (totalBytes - progressBytes) / speedBytesPerSec;
            sub.append("  •  ETA ").append(formatDuration(remaining));
        }
        indicator.setText2(sub.toString());
    }

    // -- size walkers (pre-pass) ---------------------------------------------

    private long sumLocalSizes(@NotNull List<Path> sources) throws IOException {
        long total = 0L;
        for (Path source : sources) {
            indicator.checkCanceled();
            total += sumLocalSize(source);
        }
        return total;
    }

    private long sumLocalSize(@NotNull Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink()) return 0L;
        if (!attrs.isDirectory()) return attrs.size();
        long total = 0L;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
            for (Path child : children) {
                indicator.checkCanceled();
                total += sumLocalSize(child);
            }
        }
        return total;
    }

    private long sumRemoteSizes(@NotNull List<String> remoteSources) throws IOException {
        long total = 0L;
        for (String source : remoteSources) {
            indicator.checkCanceled();
            total += sumRemoteSize(source);
        }
        return total;
    }

    private long sumRemoteSize(@NotNull String remotePath) throws IOException {
        SftpClient.Attributes attrs = sftp.stat(remotePath);
        if ((attrs.getPermissions() & SftpConstants.S_IFMT) == SftpConstants.S_IFLNK) return 0L;
        if (!attrs.isDirectory()) return attrs.getSize();
        long total = 0L;
        for (SftpClient.DirEntry entry : sftp.readDir(remotePath)) {
            indicator.checkCanceled();
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            total += sumRemoteSize(joinRemote(remotePath, name));
        }
        return total;
    }

    // -- copy + helpers -------------------------------------------------------

    private void copy(@NotNull InputStream in, @NotNull OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        int read;
        while ((read = in.read(buffer)) != -1) {
            indicator.checkCanceled();
            out.write(buffer, 0, read);
            progressBytes += read;
            bytesCopied += read;
            updateProgress(/* force = */ false);
        }
    }

    private void ensureRemoteDir(@NotNull String path) throws IOException {
        Long size = remoteSizeIfExists(path);
        if (size != null) {
            // Already exists — assume it's a directory. If it's a file
            // the mkdir below would fail, which we'd want to know about.
            SftpClient.Attributes attrs = sftp.stat(path);
            if (!attrs.isDirectory()) {
                throw new IOException("Remote destination exists and is not a directory: " + path);
            }
            return;
        }
        sftp.mkdir(path);
    }

    private @Nullable Long remoteSizeIfExists(@NotNull String path) throws IOException {
        try {
            return sftp.stat(path).getSize();
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) return null;
            throw e;
        }
    }

    private @NotNull String nextRemoteAvailable(@NotNull String basePath) throws IOException {
        int suffix = 1;
        while (true) {
            String candidate = appendSuffix(basePath, suffix);
            if (remoteSizeIfExists(candidate) == null) return candidate;
            suffix++;
        }
    }

    private static @NotNull Path nextLocalAvailable(@NotNull Path basePath) {
        int suffix = 1;
        while (true) {
            Path candidate = basePath.resolveSibling(appendSuffix(basePath.getFileName().toString(), suffix));
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate;
            suffix++;
        }
    }

    private static @NotNull String appendSuffix(@NotNull String name, int suffix) {
        int dot = name.lastIndexOf('.');
        // Only treat it as an extension if the dot isn't the first char
        // (hidden files) and leaves a non-empty extension.
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(0, dot) + " (" + suffix + ")" + name.substring(dot);
        }
        return name + " (" + suffix + ")";
    }

    private static @NotNull String joinRemote(@NotNull String base, @NotNull String child) {
        if (base.endsWith("/")) return base + child;
        return base + "/" + child;
    }

    private static @NotNull String baseName(@NotNull String remotePath) {
        int slash = remotePath.lastIndexOf('/');
        if (slash < 0 || slash == remotePath.length() - 1) return remotePath;
        return remotePath.substring(slash + 1);
    }

    private static @NotNull String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static @NotNull String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }
}
