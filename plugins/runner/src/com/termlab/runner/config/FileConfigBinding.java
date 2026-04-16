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
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks which run configuration a file path is bound to.
 */
public final class FileConfigBinding {

    private static final Logger LOG = Logger.getInstance(FileConfigBinding.class);
    private static final int VERSION = 2;

    private final Path path;
    private final Map<String, BindingEntry> bindings = new HashMap<>();

    public FileConfigBinding() {
        this(RunnerPaths.bindingsFile());
    }

    public FileConfigBinding(@NotNull Path path) {
        this.path = path;
        bindings.putAll(loadSilently(path));
    }

    public @Nullable UUID getConfigId(@NotNull String filePath) {
        BindingEntry entry = bindings.get(filePath);
        return entry != null ? entry.activeConfigId : null;
    }

    public @NotNull List<UUID> getConfigIds(@NotNull String filePath) {
        BindingEntry entry = bindings.get(filePath);
        return entry == null ? List.of() : List.copyOf(entry.configIds);
    }

    public void bind(@NotNull String filePath, @NotNull UUID configId) {
        BindingEntry entry = bindings.computeIfAbsent(filePath, unused -> new BindingEntry());
        if (!entry.configIds.contains(configId)) {
            entry.configIds.add(configId);
        }
        entry.activeConfigId = configId;
    }

    public void setActive(@NotNull String filePath, @NotNull UUID configId) {
        bind(filePath, configId);
    }

    public void unbind(@NotNull String filePath) {
        bindings.remove(filePath);
    }

    public void save() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);

        JsonObject bindingsObject = new JsonObject();
        for (Map.Entry<String, BindingEntry> entry : bindings.entrySet()) {
            JsonObject bindingObject = new JsonObject();
            if (entry.getValue().activeConfigId != null) {
                bindingObject.addProperty("active", entry.getValue().activeConfigId.toString());
            }
            com.google.gson.JsonArray configArray = new com.google.gson.JsonArray();
            for (UUID configId : entry.getValue().configIds) {
                configArray.add(configId.toString());
            }
            bindingObject.add("configs", configArray);
            bindingsObject.add(entry.getKey(), bindingObject);
        }
        root.add("bindings", bindingsObject);

        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, RunnerGson.GSON.toJson(root));
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static @NotNull Map<String, BindingEntry> loadSilently(@NotNull Path path) {
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            String raw = Files.readString(path);
            JsonElement rootEl = JsonParser.parseString(raw);
            if (rootEl == null || !rootEl.isJsonObject()) {
                return Map.of();
            }

            JsonObject root = rootEl.getAsJsonObject();
            if (!root.has("bindings") || !root.get("bindings").isJsonObject()) {
                return Map.of();
            }

            Map<String, BindingEntry> result = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("bindings").entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive()) {
                    // Backward compatibility with the original file-path -> UUID format.
                    UUID configId = UUID.fromString(value.getAsString());
                    BindingEntry bindingEntry = new BindingEntry();
                    bindingEntry.activeConfigId = configId;
                    bindingEntry.configIds.add(configId);
                    result.put(entry.getKey(), bindingEntry);
                    continue;
                }
                if (!value.isJsonObject()) {
                    continue;
                }

                JsonObject bindingObject = value.getAsJsonObject();
                BindingEntry bindingEntry = new BindingEntry();
                if (bindingObject.has("configs") && bindingObject.get("configs").isJsonArray()) {
                    for (JsonElement configElement : bindingObject.getAsJsonArray("configs")) {
                        bindingEntry.configIds.add(UUID.fromString(configElement.getAsString()));
                    }
                }
                if (bindingObject.has("active") && !bindingObject.get("active").isJsonNull()) {
                    bindingEntry.activeConfigId = UUID.fromString(bindingObject.get("active").getAsString());
                }
                if (bindingEntry.activeConfigId == null && !bindingEntry.configIds.isEmpty()) {
                    bindingEntry.activeConfigId = bindingEntry.configIds.get(0);
                }
                if (bindingEntry.activeConfigId != null && !bindingEntry.configIds.contains(bindingEntry.activeConfigId)) {
                    bindingEntry.configIds.add(0, bindingEntry.activeConfigId);
                }
                result.put(entry.getKey(), bindingEntry);
            }
            return result;
        } catch (Exception e) {
            LOG.warn("TermLab Runner: could not load bindings from " + path + ": " + e.getMessage());
            return Map.of();
        }
    }

    private static final class BindingEntry {
        private @Nullable UUID activeConfigId;
        private final List<UUID> configIds = new ArrayList<>();
    }
}
