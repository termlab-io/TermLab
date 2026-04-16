package com.termlab.ssh.model;

import com.termlab.ssh.persistence.HostPaths;
import com.termlab.ssh.persistence.HostsFile;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * In-memory holder for the saved host list, registered as an IntelliJ
 * application service so every caller (tool window, palette contributor,
 * provider) shares the same instance. Persistence is delegated to
 * {@link HostsFile} — the store's {@link #save()} method flushes the
 * current list back to {@code ~/.config/termlab/ssh-hosts.json}.
 *
 * <p>Not thread-safe. Mutations should happen on the EDT; the tool
 * window runs all its CRUD there.
 */
public final class HostStore {

    private static final Logger LOG = Logger.getInstance(HostStore.class);

    private final Path path;
    private final List<SshHost> hosts = new ArrayList<>();

    /**
     * No-arg constructor used by the IntelliJ application-service
     * framework. Resolves the default path and loads existing hosts
     * from disk. If the load fails (missing file, malformed JSON),
     * starts with an empty list and the problem is logged.
     */
    public HostStore() {
        this(HostPaths.hostsFile(), loadSilently(HostPaths.hostsFile()));
    }

    /** Explicit constructor — used by tests with {@code @TempDir}. */
    public HostStore(@NotNull Path path, @NotNull List<SshHost> initial) {
        this.path = path;
        hosts.addAll(initial);
    }

    /**
     * In-memory-only constructor — no persistence. Older tests written
     * before {@link #save()} existed can keep using this; new call sites
     * should prefer the path-aware constructor.
     */
    public HostStore(@NotNull List<SshHost> initial) {
        this(HostPaths.hostsFile(), initial);
    }

    /** @return an unmodifiable snapshot of the current hosts. */
    public @NotNull List<SshHost> getHosts() {
        return Collections.unmodifiableList(new ArrayList<>(hosts));
    }

    public void addHost(@NotNull SshHost host) {
        hosts.add(host);
    }

    public boolean removeHost(@NotNull UUID id) {
        return hosts.removeIf(h -> h.id().equals(id));
    }

    /**
     * Replace an existing host in place. Matched by {@link SshHost#id()}.
     *
     * @return {@code true} if a host was found and replaced
     */
    public boolean updateHost(@NotNull SshHost updated) {
        for (int i = 0; i < hosts.size(); i++) {
            if (hosts.get(i).id().equals(updated.id())) {
                hosts.set(i, updated);
                return true;
            }
        }
        return false;
    }

    public @Nullable SshHost findById(@NotNull UUID id) {
        for (SshHost host : hosts) {
            if (host.id().equals(id)) return host;
        }
        return null;
    }

    public int size() {
        return hosts.size();
    }

    public void clear() {
        hosts.clear();
    }

    /**
     * Flush the current host list to disk via {@link HostsFile#save}.
     * Call after any mutation that should survive a restart.
     *
     * @throws IOException if the write fails
     */
    public void save() throws IOException {
        HostsFile.save(path, getHosts());
    }

    /**
     * Reload the host list from disk, discarding any unsaved in-memory
     * state. Useful when the user edits the JSON by hand and hits the
     * "refresh" button in the tool window.
     *
     * @throws IOException if the read fails
     */
    public void reload() throws IOException {
        hosts.clear();
        hosts.addAll(HostsFile.load(path));
    }

    private static @NotNull List<SshHost> loadSilently(@NotNull Path path) {
        try {
            return HostsFile.load(path);
        } catch (IOException e) {
            LOG.warn("TermLab: could not load ssh hosts from " + path + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
