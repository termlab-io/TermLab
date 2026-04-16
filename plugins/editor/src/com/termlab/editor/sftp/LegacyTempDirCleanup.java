package com.termlab.editor.sftp;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.util.concurrency.AppExecutorUtil;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * One-shot cleanup of the legacy {@code termlab-sftp-edits/} directory
 * left behind by the old temp-file SFTP edit flow. The directory is no
 * longer used after migration to {@link com.termlab.sftp.vfs.SftpVirtualFileSystem}.
 *
 * <p>Remove this class after a few releases when no users have stale dirs.
 */
public final class LegacyTempDirCleanup implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(LegacyTempDirCleanup.class);
    private static volatile boolean swept = false;

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (swept) return Unit.INSTANCE;
        swept = true;
        AppExecutorUtil.getAppExecutorService().submit(() -> {
            Path root = Paths.get(PathManager.getSystemPath(), "termlab-sftp-edits");
            if (!Files.exists(root)) return;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (IOException e) { LOG.warn("Failed to delete legacy temp file: " + p, e); }
                });
                LOG.info("Cleaned up legacy termlab-sftp-edits directory");
            } catch (IOException e) {
                LOG.warn("Failed to clean up legacy termlab-sftp-edits", e);
            }
        });
        return Unit.INSTANCE;
    }
}
