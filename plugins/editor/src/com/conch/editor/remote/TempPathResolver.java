package com.conch.editor.remote;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic temp-path layout for SFTP-edited files.
 *
 * <pre>
 * {root}/
 *   {hash8(host)}/
 *     {hash8(remotePath)}/
 *       {basename}
 * </pre>
 *
 * The host prefix isolates per-host; the remote-path prefix
 * prevents collisions between files that happen to share a
 * basename across different remote directories. The basename is
 * preserved so TextMate can pick a grammar by extension.
 */
public final class TempPathResolver {

    private TempPathResolver() {}

    public static @NotNull Path resolve(
        @NotNull Path root,
        @NotNull String hostConnectionString,
        @NotNull String absoluteRemotePath
    ) {
        String hostHash = sha1Prefix(hostConnectionString, 8);
        String pathHash = sha1Prefix(absoluteRemotePath, 8);
        String basename = basenameOf(absoluteRemotePath);
        return root.resolve(hostHash).resolve(pathHash).resolve(basename);
    }

    private static @NotNull String basenameOf(@NotNull String remotePath) {
        int slash = remotePath.lastIndexOf('/');
        if (slash < 0 || slash == remotePath.length() - 1) return remotePath;
        return remotePath.substring(slash + 1);
    }

    private static @NotNull String sha1Prefix(@NotNull String input, int hexChars) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hexChars);
            for (int i = 0; i < (hexChars + 1) / 2; i++) {
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.substring(0, hexChars);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
