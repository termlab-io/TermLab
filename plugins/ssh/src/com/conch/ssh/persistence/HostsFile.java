package com.conch.ssh.persistence;

import com.conch.ssh.model.SshHost;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Atomic JSON I/O for the SSH host list.
 *
 * <p>The file is wrapped in a versioned envelope so we have room to evolve
 * the format later without breaking existing vaults:
 * <pre>
 *   {
 *     "version": 1,
 *     "hosts": [ { "id": "…", "label": "…", ... } ]
 *   }
 * </pre>
 *
 * <p>Atomic write: serialize → write to temp → rename. A crash mid-write
 * can never corrupt the existing file.
 */
public final class HostsFile {

    public static final int VERSION = 1;

    private HostsFile() {}

    public static void save(@NotNull Path target, @NotNull List<SshHost> hosts) throws IOException {
        Envelope envelope = new Envelope(VERSION, new ArrayList<>(hosts));
        String json = SshGson.GSON.toJson(envelope);

        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Load the host list from disk. Returns an empty list if the file
     * doesn't exist. Returns an empty list (and ignores the error) if the
     * file is unreadable or structurally invalid, because a corrupted
     * host list is recoverable — the user just re-adds their hosts, they
     * lose nothing secret.
     */
    public static @NotNull List<SshHost> load(@NotNull Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            return Collections.emptyList();
        }
        String json = Files.readString(source);
        try {
            Envelope envelope = SshGson.GSON.fromJson(json, Envelope.class);
            if (envelope == null || envelope.hosts == null) {
                return Collections.emptyList();
            }
            return envelope.hosts;
        } catch (Exception malformed) {
            return Collections.emptyList();
        }
    }

    public static boolean exists(@NotNull Path target) {
        return Files.isRegularFile(target);
    }

    /**
     * Gson-friendly envelope type. Public fields so Gson can read/write
     * directly without reflection into private members — same pattern as
     * ConchTerminalConfig.State and the vault settings.
     */
    static final class Envelope {
        int version;
        List<SshHost> hosts;

        Envelope() {}

        Envelope(int version, List<SshHost> hosts) {
            this.version = version;
            this.hosts = hosts;
        }
    }
}
