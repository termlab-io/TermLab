package com.termlab.tunnels.model;

import com.termlab.tunnels.persistence.TunnelPaths;
import com.termlab.tunnels.persistence.TunnelsFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Application service holding the saved tunnel list. Mirrors
 * {@code com.termlab.ssh.model.HostStore}.
 */
public final class TunnelStore {

    private final Path filePath;
    private final List<SshTunnel> tunnels;

    /** Framework constructor — loads from default path. */
    public TunnelStore() {
        this(TunnelPaths.tunnelsFile());
    }

    /** Loads from the given path. */
    public TunnelStore(@NotNull Path filePath) {
        this.filePath = filePath;
        List<SshTunnel> loaded;
        try {
            loaded = new ArrayList<>(TunnelsFile.load(filePath));
        } catch (IOException e) {
            loaded = new ArrayList<>();
        }
        this.tunnels = loaded;
    }

    /** Test constructor with explicit initial list. */
    public TunnelStore(@NotNull Path filePath, @NotNull List<SshTunnel> initial) {
        this.filePath = filePath;
        this.tunnels = new ArrayList<>(initial);
    }

    public @NotNull List<SshTunnel> getTunnels() {
        return Collections.unmodifiableList(tunnels);
    }

    public @Nullable SshTunnel findById(@NotNull UUID id) {
        for (SshTunnel t : tunnels) {
            if (t.id().equals(id)) return t;
        }
        return null;
    }

    public void addTunnel(@NotNull SshTunnel tunnel) {
        tunnels.add(tunnel);
    }

    public boolean removeTunnel(@NotNull UUID id) {
        return tunnels.removeIf(t -> t.id().equals(id));
    }

    public boolean updateTunnel(@NotNull SshTunnel updated) {
        for (int i = 0; i < tunnels.size(); i++) {
            if (tunnels.get(i).id().equals(updated.id())) {
                tunnels.set(i, updated);
                return true;
            }
        }
        return false;
    }

    public void save() throws IOException {
        TunnelsFile.save(filePath, tunnels);
    }

    public void reload() throws IOException {
        tunnels.clear();
        tunnels.addAll(TunnelsFile.load(filePath));
    }
}
