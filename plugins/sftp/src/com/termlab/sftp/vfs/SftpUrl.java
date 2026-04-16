package com.termlab.sftp.vfs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Parsed form of an SFTP VFS URL. Format:
 *
 * <pre>
 *   sftp://&lt;hostId&gt;//&lt;absolute-remote-path&gt;
 * </pre>
 *
 * The double slash separates the host UUID from the absolute
 * remote path that itself begins with {@code /}. The host UUID is
 * the stable identifier from {@code SshHost.id()}.
 */
public record SftpUrl(@NotNull UUID hostId, @NotNull String remotePath) {

    public static final String PROTOCOL = "sftp";
    private static final String PROTOCOL_PREFIX = PROTOCOL + "://";

    private static boolean containsParentTraversal(@NotNull String remotePath) {
        return remotePath.equals("/..")
            || remotePath.endsWith("/..")
            || remotePath.contains("/../");
    }

    public static @NotNull String compose(@NotNull UUID hostId, @NotNull String remotePath) {
        if (!remotePath.startsWith("/")) {
            throw new IllegalArgumentException("remotePath must be absolute (start with /): " + remotePath);
        }
        if (containsParentTraversal(remotePath)) {
            throw new IllegalArgumentException("remotePath must not contain '..' segments: " + remotePath);
        }
        return PROTOCOL_PREFIX + hostId + "/" + remotePath;
    }

    public static @Nullable SftpUrl parse(@NotNull String url) {
        if (!url.startsWith(PROTOCOL_PREFIX)) return null;
        String afterProtocol = url.substring(PROTOCOL_PREFIX.length());
        // Find the first '/' which separates hostId from the absolute remote path.
        int firstSlash = afterProtocol.indexOf('/');
        if (firstSlash <= 0) return null; // no hostId or empty hostId
        String hostIdString = afterProtocol.substring(0, firstSlash);
        String remotePath = afterProtocol.substring(firstSlash + 1);
        if (remotePath.isEmpty() || !remotePath.startsWith("/")) return null;
        if (containsParentTraversal(remotePath)) return null;
        UUID hostId;
        try {
            hostId = UUID.fromString(hostIdString);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new SftpUrl(hostId, remotePath);
    }
}
