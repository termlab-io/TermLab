package com.conch.tunnels.persistence;

import com.conch.tunnels.model.SshTunnel;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TunnelsFile {

    public static final int VERSION = 1;

    private TunnelsFile() {}

    public static void save(@NotNull Path target, @NotNull List<SshTunnel> tunnels) throws IOException {
        Envelope envelope = new Envelope(VERSION, new ArrayList<>(tunnels));
        String json = TunnelGson.GSON.toJson(envelope);
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static @NotNull List<SshTunnel> load(@NotNull Path source) throws IOException {
        if (!Files.isRegularFile(source)) return Collections.emptyList();
        String json = Files.readString(source);
        try {
            Envelope envelope = TunnelGson.GSON.fromJson(json, Envelope.class);
            if (envelope == null || envelope.tunnels == null) return Collections.emptyList();
            return envelope.tunnels;
        } catch (Exception malformed) {
            return Collections.emptyList();
        }
    }

    static final class Envelope {
        int version;
        List<SshTunnel> tunnels;

        Envelope() {}
        Envelope(int version, List<SshTunnel> tunnels) {
            this.version = version;
            this.tunnels = tunnels;
        }
    }
}
