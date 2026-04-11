package com.conch.ssh.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * In-memory holder for the saved host list.
 *
 * <p>Phase 1 intentionally keeps this minimal — just mutable state plus
 * the CRUD operations the tool window and edit dialog need. Phase 4.2
 * promotes this to an IntelliJ {@code <applicationService>} so every
 * caller (tool window, palette contributor, provider) shares the same
 * instance; at that point a no-arg constructor will load from
 * {@code HostsFile} on the first access.
 *
 * <p>Not thread-safe on its own. Mutations should happen on the EDT.
 */
public final class HostStore {

    private final List<SshHost> hosts = new ArrayList<>();

    public HostStore() {
    }

    public HostStore(@NotNull List<SshHost> initial) {
        hosts.addAll(initial);
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
}
