package com.termlab.runner.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunConfigStoreTest {

    @Test
    void newStore_isEmpty(@TempDir Path tmp) {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);
        assertTrue(store.getAll().isEmpty());
    }

    @Test
    void addConfig_thenGetAll_returnsIt(@TempDir Path tmp) {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);

        RunConfig config = RunConfig.create("Test", null, "python3", List.of(), null, Map.of(), List.of());
        store.add(config);

        assertEquals(1, store.getAll().size());
        assertEquals("Test", store.getAll().get(0).name());
    }

    @Test
    void save_thenLoad_roundTrips(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);

        RunConfig config = RunConfig.create(
            "Deploy Script",
            null,
            "bash",
            List.of("-x"),
            "/tmp",
            Map.of("ENV", "prod"),
            List.of("--dry-run")
        );
        store.add(config);
        store.save();

        assertTrue(Files.exists(file));

        RunConfigStore reloaded = new RunConfigStore(file);
        assertEquals(1, reloaded.getAll().size());
        RunConfig restored = reloaded.getAll().get(0);
        assertEquals(config.id(), restored.id());
        assertEquals("Deploy Script", restored.name());
        assertEquals("bash", restored.interpreter());
        assertEquals(List.of("-x"), restored.args());
        assertEquals("/tmp", restored.workingDirectory());
        assertEquals(Map.of("ENV", "prod"), restored.envVars());
        assertEquals(List.of("--dry-run"), restored.scriptArgs());
    }

    @Test
    void save_isAtomic_noTmpFileRemains(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);
        store.add(RunConfig.create("A", null, "bash", List.of(), null, Map.of(), List.of()));
        store.save();

        assertFalse(Files.exists(tmp.resolve("run-configs.json.tmp")));
        assertTrue(Files.exists(file));
    }

    @Test
    void remove_byId(@TempDir Path tmp) {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);

        RunConfig config = RunConfig.create("Test", null, "bash", List.of(), null, Map.of(), List.of());
        store.add(config);
        assertTrue(store.remove(config.id()));
        assertTrue(store.getAll().isEmpty());
    }

    @Test
    void update_replacesInPlace(@TempDir Path tmp) {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);

        RunConfig config = RunConfig.create("Old", null, "bash", List.of(), null, Map.of(), List.of());
        store.add(config);

        RunConfig updated = config.withName("New");
        assertTrue(store.update(updated));
        assertEquals("New", store.getAll().get(0).name());
    }

    @Test
    void getById_findsConfig(@TempDir Path tmp) {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);

        RunConfig config = RunConfig.create("Find Me", null, "bash", List.of(), null, Map.of(), List.of());
        store.add(config);

        assertNotNull(store.getById(config.id()));
        assertEquals("Find Me", store.getById(config.id()).name());
    }

    @Test
    void getById_missingId_returnsNull(@TempDir Path tmp) {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);
        assertNull(store.getById(java.util.UUID.randomUUID()));
    }

    @Test
    void changeListener_firesOnAdd(@TempDir Path tmp) {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);

        boolean[] fired = {false};
        store.addChangeListener(() -> fired[0] = true);
        store.add(RunConfig.create("X", null, "bash", List.of(), null, Map.of(), List.of()));
        assertTrue(fired[0]);
    }

    @Test
    void load_corruptedFile_returnsEmpty(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("run-configs.json");
        Files.writeString(file, "not valid json {{{");
        RunConfigStore store = new RunConfigStore(file);
        assertTrue(store.getAll().isEmpty());
    }

    @Test
    void load_missingFile_returnsEmpty(@TempDir Path tmp) {
        Path file = tmp.resolve("nonexistent.json");
        RunConfigStore store = new RunConfigStore(file);
        assertTrue(store.getAll().isEmpty());
    }
}
