package com.conch.minecraftadmin.persistence;

import com.conch.minecraftadmin.model.ServerProfile;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Atomic JSON I/O for the Minecraft Admin server profile list.
 *
 * <p>Versioned envelope:
 * <pre>
 *   {
 *     "version": 1,
 *     "servers": [ {...}, ... ]
 *   }
 * </pre>
 *
 * <p>Atomic write: serialize → write to a sibling temp file → rename.
 * A crash mid-write can never corrupt the existing file. Same pattern
 * as {@code HostsFile} in the SSH plugin.
 */
public final class ServersFile {

    public static final int VERSION = 1;

    private ServersFile() {}

    public static void save(@NotNull Path target, @NotNull List<ServerProfile> profiles) throws IOException {
        Envelope envelope = new Envelope(VERSION, new ArrayList<>(profiles));
        String json = McGson.GSON.toJson(envelope);

        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);

        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, json);
        try {
            Files.move(temp, target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            // Some filesystems (notably FAT) don't support atomic replace —
            // fall back to a non-atomic copy so saving still works on
            // esoteric setups.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static @NotNull List<ServerProfile> load(@NotNull Path source) throws IOException {
        if (!Files.exists(source)) return Collections.emptyList();
        String json = Files.readString(source);
        Envelope envelope;
        try {
            Type type = new TypeToken<Envelope>(){}.getType();
            envelope = McGson.GSON.fromJson(json, type);
        } catch (JsonSyntaxException e) {
            throw new IOException("minecraft-servers.json is corrupt: " + e.getMessage(), e);
        }
        if (envelope == null) return Collections.emptyList();
        if (envelope.version() != VERSION) {
            throw new IOException("unsupported minecraft-servers.json version "
                + envelope.version() + " (expected " + VERSION + ")");
        }
        return envelope.servers() == null ? Collections.emptyList() : envelope.servers();
    }

    private record Envelope(int version, List<ServerProfile> servers) {}
}
