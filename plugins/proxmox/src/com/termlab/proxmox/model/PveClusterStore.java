package com.termlab.proxmox.model;

import com.intellij.openapi.diagnostic.Logger;
import com.termlab.proxmox.persistence.PveClustersFile;
import com.termlab.proxmox.persistence.PvePaths;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PveClusterStore {
    private static final Logger LOG = Logger.getInstance(PveClusterStore.class);

    private final Path path;
    private final List<PveCluster> clusters = new ArrayList<>();
    private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public PveClusterStore() {
        this(PvePaths.clustersFile(), loadSilently(PvePaths.clustersFile()));
    }

    public PveClusterStore(@NotNull Path path, @NotNull List<PveCluster> initial) {
        this.path = path;
        clusters.addAll(initial);
        sort();
    }

    public @NotNull List<PveCluster> getClusters() {
        return Collections.unmodifiableList(new ArrayList<>(clusters));
    }

    public void addCluster(@NotNull PveCluster cluster) {
        clusters.add(cluster);
        sort();
        fireChanged();
    }

    public boolean updateCluster(@NotNull PveCluster updated) {
        for (int i = 0; i < clusters.size(); i++) {
            if (clusters.get(i).id().equals(updated.id())) {
                clusters.set(i, updated);
                sort();
                fireChanged();
                return true;
            }
        }
        return false;
    }

    public boolean removeCluster(@NotNull UUID id) {
        boolean removed = clusters.removeIf(cluster -> cluster.id().equals(id));
        if (removed) fireChanged();
        return removed;
    }

    public @Nullable PveCluster findById(@NotNull UUID id) {
        for (PveCluster cluster : clusters) {
            if (cluster.id().equals(id)) return cluster;
        }
        return null;
    }

    public void save() throws IOException {
        PveClustersFile.save(path, getClusters());
    }

    public void reload() throws IOException {
        clusters.clear();
        clusters.addAll(PveClustersFile.load(path));
        sort();
        fireChanged();
    }

    public void addChangeListener(@NotNull Runnable listener) {
        changeListeners.add(listener);
    }

    public void removeChangeListener(@NotNull Runnable listener) {
        changeListeners.remove(listener);
    }

    private void sort() {
        clusters.sort(Comparator.comparing(PveCluster::label, String.CASE_INSENSITIVE_ORDER));
    }

    private void fireChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    private static @NotNull List<PveCluster> loadSilently(@NotNull Path path) {
        try {
            return PveClustersFile.load(path);
        } catch (IOException e) {
            LOG.warn("TermLab: could not load Proxmox clusters from " + path + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
