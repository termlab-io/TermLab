package com.termlab.proxmox.persistence;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termlab.proxmox.model.PveCluster;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PveClustersFile {
    public static final int VERSION = 1;

    private PveClustersFile() {}

    public static void save(@NotNull Path target, @NotNull List<PveCluster> clusters) throws IOException {
        Envelope envelope = new Envelope(VERSION, new ArrayList<>(clusters));
        String json = PveGson.GSON.toJson(envelope);
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static @NotNull List<PveCluster> load(@NotNull Path source) throws IOException {
        if (!Files.isRegularFile(source)) return Collections.emptyList();
        String raw = Files.readString(source);
        try {
            JsonElement rootEl = JsonParser.parseString(raw);
            if (rootEl == null || !rootEl.isJsonObject()) return Collections.emptyList();
            JsonObject root = rootEl.getAsJsonObject();
            if (!root.has("clusters") || !root.get("clusters").isJsonArray()) return Collections.emptyList();
            Envelope envelope = PveGson.GSON.fromJson(root, Envelope.class);
            if (envelope == null || envelope.clusters == null) return Collections.emptyList();
            return envelope.clusters;
        } catch (Exception malformed) {
            return Collections.emptyList();
        }
    }

    static final class Envelope {
        int version;
        List<PveCluster> clusters;

        Envelope() {}

        Envelope(int version, List<PveCluster> clusters) {
            this.version = version;
            this.clusters = clusters;
        }
    }
}
