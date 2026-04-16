package com.termlab.runner.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory holder for named run configurations with atomic JSON persistence.
 */
public final class RunConfigStore {

    private static final Logger LOG = Logger.getInstance(RunConfigStore.class);
    private static final int VERSION = 1;

    private final Path path;
    private final List<RunConfig> configs = new ArrayList<>();
    private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public RunConfigStore() {
        this(RunnerPaths.configsFile());
    }

    public RunConfigStore(@NotNull Path path) {
        this.path = path;
        configs.addAll(loadSilently(path));
    }

    public @NotNull List<RunConfig> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(configs));
    }

    public @Nullable RunConfig getById(@NotNull UUID id) {
        for (RunConfig config : configs) {
            if (config.id().equals(id)) {
                return config;
            }
        }
        return null;
    }

    public void add(@NotNull RunConfig config) {
        configs.add(config);
        fireChanged();
    }

    public boolean remove(@NotNull UUID id) {
        boolean removed = configs.removeIf(config -> config.id().equals(id));
        if (removed) {
            fireChanged();
        }
        return removed;
    }

    public boolean update(@NotNull RunConfig updated) {
        for (int i = 0; i < configs.size(); i++) {
            if (configs.get(i).id().equals(updated.id())) {
                configs.set(i, updated);
                fireChanged();
                return true;
            }
        }
        return false;
    }

    public void save() throws IOException {
        Envelope envelope = new Envelope(VERSION, new ArrayList<>(configs));
        String json = RunnerGson.GSON.toJson(envelope);

        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public void addChangeListener(@NotNull Runnable listener) {
        changeListeners.add(listener);
    }

    public void removeChangeListener(@NotNull Runnable listener) {
        changeListeners.remove(listener);
    }

    private void fireChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    private static @NotNull List<RunConfig> loadSilently(@NotNull Path path) {
        if (!Files.isRegularFile(path)) {
            return Collections.emptyList();
        }
        try {
            return load(path);
        } catch (Exception e) {
            LOG.warn("TermLab Runner: could not load configs from " + path + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    static @NotNull List<RunConfig> load(@NotNull Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            return Collections.emptyList();
        }

        String raw = Files.readString(source);
        JsonElement rootEl = JsonParser.parseString(raw);
        if (rootEl == null || !rootEl.isJsonObject()) {
            return Collections.emptyList();
        }

        JsonObject root = rootEl.getAsJsonObject();
        if (!root.has("configs") || !root.get("configs").isJsonArray()) {
            return Collections.emptyList();
        }

        Envelope envelope = RunnerGson.GSON.fromJson(root, Envelope.class);
        if (envelope == null || envelope.configs == null) {
            return Collections.emptyList();
        }
        return envelope.configs;
    }

    static final class Envelope {
        int version;
        List<RunConfig> configs;

        Envelope() {}

        Envelope(int version, List<RunConfig> configs) {
            this.version = version;
            this.configs = configs;
        }
    }
}
