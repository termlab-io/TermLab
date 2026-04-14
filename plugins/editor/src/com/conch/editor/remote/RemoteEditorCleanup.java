package com.conch.editor.remote;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Cleanup helpers for SFTP temp files. Each call tolerates
 * missing files and partial failures — cleanup is best-effort.
 */
public final class RemoteEditorCleanup {

    private static final Logger LOG = Logger.getInstance(RemoteEditorCleanup.class);

    private RemoteEditorCleanup() {}

    /**
     * Delete a single temp file and walk up its parent dirs,
     * deleting any that became empty as a result.
     */
    public static void deleteTempFileAndEmptyParents(@NotNull Path tempFile, @NotNull Path stopAt) {
        try { Files.deleteIfExists(tempFile); }
        catch (IOException e) { LOG.warn("Failed to delete temp file: " + tempFile, e); }
        Path parent = tempFile.getParent();
        while (parent != null && !parent.equals(stopAt) && parent.startsWith(stopAt)) {
            if (!isEmptyDirectory(parent)) break;
            try { Files.delete(parent); }
            catch (IOException e) { LOG.warn("Failed to delete empty dir: " + parent, e); break; }
            parent = parent.getParent();
        }
    }

    /**
     * Recursively delete the entire temp root. Used on shutdown
     * and as the startup orphan sweep.
     */
    public static void purgeRoot(@NotNull Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException e) { LOG.warn("Failed to delete " + p, e); }
            });
        } catch (IOException e) {
            LOG.warn("Failed to walk temp root " + root, e);
        }
    }

    private static boolean isEmptyDirectory(@NotNull Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }
}
