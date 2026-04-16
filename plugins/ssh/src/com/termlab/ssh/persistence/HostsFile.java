package com.termlab.ssh.persistence;

import com.termlab.ssh.model.SshHost;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * the format later without breaking existing users:
 * <pre>
 *   {
 *     "version": 1,
 *     "hosts": [ { "id": "…", "label": "…", ... } ]
 *   }
 * </pre>
 *
 * <p>Atomic write: serialize → write to temp → rename. A crash mid-write
 * can never corrupt the existing file.
 *
 * <p><b>Legacy migration.</b> Before {@link com.termlab.ssh.model.SshAuth}
 * existed, host entries carried a bare top-level {@code credentialId}
 * field. On load, {@link #load(Path)} rewrites any such entry into the
 * new {@code "auth": {"type": "vault", ...}} shape before handing the
 * JSON to Gson. The next {@link #save} drops the legacy field for good.
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
        String raw = Files.readString(source);
        try {
            JsonElement rootEl = JsonParser.parseString(raw);
            if (rootEl == null || !rootEl.isJsonObject()) return Collections.emptyList();
            JsonObject root = rootEl.getAsJsonObject();
            if (!root.has("hosts") || !root.get("hosts").isJsonArray()) {
                return Collections.emptyList();
            }
            JsonArray hostsArray = root.getAsJsonArray("hosts");
            for (JsonElement element : hostsArray) {
                if (element.isJsonObject()) migrateLegacyHostEntry(element.getAsJsonObject());
            }
            Envelope envelope = SshGson.GSON.fromJson(root, Envelope.class);
            if (envelope == null || envelope.hosts == null) {
                return Collections.emptyList();
            }
            return envelope.hosts;
        } catch (Exception malformed) {
            return Collections.emptyList();
        }
    }

    /**
     * In-place rewrite of a single host JSON object: if it has a bare
     * top-level {@code credentialId} field (the pre-SshAuth shape) and no
     * {@code auth} field, synthesize the new
     * {@code "auth": {"type": "vault", ...}} sub-object and strip the
     * legacy field so Gson sees only the current schema.
     */
    private static void migrateLegacyHostEntry(@NotNull JsonObject hostObj) {
        if (hostObj.has("auth")) return;                    // already new-shape
        if (!hostObj.has("credentialId")) return;           // nothing to migrate

        JsonElement legacy = hostObj.remove("credentialId");
        JsonObject auth = new JsonObject();
        auth.addProperty("type", "vault");
        if (legacy != null && !legacy.isJsonNull()) {
            auth.addProperty("credentialId", legacy.getAsString());
        }
        hostObj.add("auth", auth);
    }

    public static boolean exists(@NotNull Path target) {
        return Files.isRegularFile(target);
    }

    /**
     * Gson-friendly envelope type. Public fields so Gson can read/write
     * directly without reflection into private members — same pattern as
     * TermLabTerminalConfig.State and the vault settings.
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
