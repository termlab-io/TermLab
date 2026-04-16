package com.termlab.ssh.persistence;

import org.apache.sshd.client.config.hosts.KnownHostEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.PublicKey;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Thin wrapper around MINA SSHD's {@link KnownHostEntry} parser. Provides
 * a three-state match result ({@link Match#MATCH}, {@link Match#MISMATCH},
 * {@link Match#UNKNOWN}) and an append helper that writes OpenSSH-format
 * {@code known_hosts} lines so external {@code ssh} stays cross-compatible
 * with anything TermLab appends.
 *
 * <p>Intentionally uses plain (non-hashed) entries. OpenSSH's hashed form
 * ({@code |1|salt|host}) obscures hostnames in the file, which matters for
 * shared machines; TermLab's file lives in each user's private config
 * directory so obfuscation buys nothing.
 */
public final class KnownHostsFile {

    /** Result of {@link #match(Path, String, int, PublicKey)}. */
    public enum Match {
        /** An entry exists for this host and its key matches the presented one. */
        MATCH,
        /** An entry exists for this host but its key is different. Hard reject. */
        MISMATCH,
        /** No entry for this host — first contact. Prompt the user. */
        UNKNOWN
    }

    private KnownHostsFile() {}

    public static @NotNull Match match(
        @NotNull Path knownHostsPath,
        @NotNull String host,
        int port,
        @NotNull PublicKey presentedKey
    ) throws IOException {
        if (!Files.isRegularFile(knownHostsPath)) {
            return Match.UNKNOWN;
        }
        List<KnownHostEntry> entries = KnownHostEntry.readKnownHostEntries(knownHostsPath);
        if (entries == null || entries.isEmpty()) {
            return Match.UNKNOWN;
        }

        String presentedFingerprint = KeyUtils.getFingerPrint(presentedKey);

        boolean anyHostMatch = false;
        for (KnownHostEntry entry : entries) {
            if (!entry.isHostMatch(host, port)) continue;
            anyHostMatch = true;

            PublicKey storedKey = loadKey(entry);
            if (storedKey == null) continue;
            String storedFingerprint = KeyUtils.getFingerPrint(storedKey);
            if (presentedFingerprint.equals(storedFingerprint)) {
                return Match.MATCH;
            }
        }

        return anyHostMatch ? Match.MISMATCH : Match.UNKNOWN;
    }

    /**
     * Append a new OpenSSH-format {@code known_hosts} line for this host.
     * Creates the parent directory and the file itself if they don't
     * exist. Sets POSIX mode 0600 on first create so the file is
     * owner-only on macOS and Linux. A no-op on Windows.
     */
    public static void append(
        @NotNull Path knownHostsPath,
        @NotNull String host,
        int port,
        @NotNull PublicKey key
    ) throws IOException {
        Path parent = knownHostsPath.getParent();
        if (parent != null) Files.createDirectories(parent);

        boolean created = !Files.exists(knownHostsPath);
        if (created) {
            Files.createFile(knownHostsPath);
            setOwnerOnlyIfPosix(knownHostsPath);
        }

        String hostSpec = formatHostSpec(host, port);
        StringBuilder sb = new StringBuilder();
        sb.append(hostSpec).append(' ');
        try {
            PublicKeyEntry.appendPublicKeyEntry(sb, key);
        } catch (IOException e) {
            throw new IOException("failed to encode public key for known_hosts append", e);
        }
        sb.append('\n');

        Files.writeString(knownHostsPath, sb.toString(),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Compute the fingerprint of a key in OpenSSH's default format
     * ({@code SHA256:<base64>} — no padding). Used by the host-key prompt
     * dialog so the user sees the same string {@code ssh-keygen -l} shows.
     */
    public static @NotNull String fingerprint(@NotNull PublicKey key) {
        return KeyUtils.getFingerPrint(key);
    }

    // -- internals ------------------------------------------------------------

    private static PublicKey loadKey(KnownHostEntry entry) {
        try {
            if (entry.getKeyEntry() == null) return null;
            return entry.getKeyEntry().resolvePublicKey(null, null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String formatHostSpec(String host, int port) {
        if (port == 22) return host;
        return "[" + host + "]:" + port;
    }

    private static void setOwnerOnlyIfPosix(Path file) {
        if (!supportsPosix()) return;
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private static boolean supportsPosix() {
        return java.nio.file.FileSystems.getDefault()
            .supportedFileAttributeViews().contains("posix");
    }
}
