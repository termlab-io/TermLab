package com.termlab.runner.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FileConfigBindingTest {

    @Test
    void newBinding_isEmpty(@TempDir Path tmp) {
        Path file = tmp.resolve("run-bindings.json");
        FileConfigBinding binding = new FileConfigBinding(file);
        assertNull(binding.getConfigId("/some/path.py"));
    }

    @Test
    void bind_thenGet(@TempDir Path tmp) {
        Path file = tmp.resolve("run-bindings.json");
        FileConfigBinding binding = new FileConfigBinding(file);

        UUID configId = UUID.randomUUID();
        binding.bind("/home/user/script.py", configId);
        assertEquals(configId, binding.getConfigId("/home/user/script.py"));
    }

    @Test
    void unbind_removesMapping(@TempDir Path tmp) {
        Path file = tmp.resolve("run-bindings.json");
        FileConfigBinding binding = new FileConfigBinding(file);

        UUID configId = UUID.randomUUID();
        binding.bind("/path.py", configId);
        binding.unbind("/path.py");
        assertNull(binding.getConfigId("/path.py"));
    }

    @Test
    void save_thenLoad_roundTrips(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("run-bindings.json");
        FileConfigBinding binding = new FileConfigBinding(file);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        binding.bind("/a.py", id1);
        binding.bind("/b.sh", id2);
        binding.save();

        FileConfigBinding reloaded = new FileConfigBinding(file);
        assertEquals(id1, reloaded.getConfigId("/a.py"));
        assertEquals(id2, reloaded.getConfigId("/b.sh"));
    }

    @Test
    void bind_multipleConfigs_keepsAllAndMarksLatestActive(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("run-bindings.json");
        FileConfigBinding binding = new FileConfigBinding(file);

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        binding.bind("/a.py", first);
        binding.bind("/a.py", second);
        binding.save();

        FileConfigBinding reloaded = new FileConfigBinding(file);
        assertEquals(second, reloaded.getConfigId("/a.py"));
        assertIterableEquals(java.util.List.of(first, second), reloaded.getConfigIds("/a.py"));
    }

    @Test
    void load_legacySingleUuidFormat_stillWorks(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("run-bindings.json");
        UUID id = UUID.randomUUID();
        Files.writeString(file, """
            {
              "version": 1,
              "bindings": {
                "/legacy.py": "%s"
              }
            }
            """.formatted(id));

        FileConfigBinding binding = new FileConfigBinding(file);
        assertEquals(id, binding.getConfigId("/legacy.py"));
        assertIterableEquals(java.util.List.of(id), binding.getConfigIds("/legacy.py"));
    }

    @Test
    void load_missingFile_isEmpty(@TempDir Path tmp) {
        Path file = tmp.resolve("nonexistent.json");
        FileConfigBinding binding = new FileConfigBinding(file);
        assertNull(binding.getConfigId("/any"));
    }

    @Test
    void load_corruptedFile_isEmpty(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("run-bindings.json");
        Files.writeString(file, "broken {{{}");
        FileConfigBinding binding = new FileConfigBinding(file);
        assertNull(binding.getConfigId("/any"));
    }
}
