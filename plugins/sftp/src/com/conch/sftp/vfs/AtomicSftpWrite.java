package com.conch.sftp.vfs;

import com.intellij.openapi.diagnostic.Logger;
import org.apache.sshd.sftp.client.SftpClient;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Atomic write helper for SFTP: writes to a sibling temp file, then
 * renames into place (POSIX fast path). On non-POSIX servers that
 * reject rename-over-existing, falls back to a backup-then-rename
 * pattern that restores the original on failure.
 */
public final class AtomicSftpWrite {

    private static final Logger LOG = Logger.getInstance(AtomicSftpWrite.class);

    private AtomicSftpWrite() {}

    /**
     * Write {@code content} to {@code remotePath} atomically via the
     * given {@link SftpClient}. On POSIX servers, this is a
     * straightforward .tmp-file + rename. On non-POSIX servers that
     * reject rename-over-existing, falls back to a backup-then-rename
     * that restores the original if the final rename fails.
     *
     * @throws IOException if the write fails. On failure, any
     *   orphaned temp file is best-effort-cleaned up, and if a backup
     *   was made and the restore succeeded, the original file is
     *   preserved. If the restore itself fails, a CRITICAL log entry
     *   records the temp paths for manual recovery.
     */
    public static void writeAtomically(
        @NotNull SftpClient client,
        @NotNull String remotePath,
        byte @NotNull [] content
    ) throws IOException {
        String randomSuffix = Long.toHexString(ThreadLocalRandom.current().nextLong());
        String writeTmp = remotePath + "." + randomSuffix + ".tmp";
        String backupTmp = remotePath + "." + randomSuffix + ".bak";

        // Step 1: write the new content to a sibling temp file.
        try {
            try (OutputStream out = client.write(writeTmp)) {
                out.write(content);
            }
        } catch (IOException e) {
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

        // Step 3: fallback for non-POSIX servers.
        boolean backedUp = false;
        try {
            try {
                client.rename(remotePath, backupTmp);
                backedUp = true;
            } catch (IOException backupErr) {
                LOG.warn("Backup rename failed for " + remotePath
                    + " (" + backupErr.getMessage() + "); proceeding without backup");
            }
            try {
                client.rename(writeTmp, remotePath);
            } catch (IOException finalRenameErr) {
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
            if (backedUp) {
                try { client.remove(backupTmp); } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            try { client.remove(writeTmp); } catch (IOException ignored) {}
            throw e;
        }
    }
}
