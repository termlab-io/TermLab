package com.termlab.runner.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunConfigTest {

    @Test
    void create_generatesUniqueId() {
        RunConfig a = RunConfig.create("Test", null, "python3", List.of(), null, Map.of(), List.of());
        RunConfig b = RunConfig.create("Test", null, "python3", List.of(), null, Map.of(), List.of());
        assertNotEquals(a.id(), b.id());
    }

    @Test
    void create_nullHostId_meansLocal() {
        RunConfig config = RunConfig.create("Local Python", null, "python3", List.of(), null, Map.of(), List.of());
        assertNull(config.hostId());
        assertTrue(config.isLocal());
    }

    @Test
    void create_withHostId_meansRemote() {
        UUID hostId = UUID.randomUUID();
        RunConfig config = RunConfig.create("Remote", hostId, "bash", List.of(), "/home/user", Map.of(), List.of());
        assertEquals(hostId, config.hostId());
        assertFalse(config.isLocal());
    }

    @Test
    void withName_returnsNewInstance() {
        RunConfig original = RunConfig.create("Old", null, "python3", List.of(), null, Map.of(), List.of());
        RunConfig renamed = original.withName("New");
        assertEquals("New", renamed.name());
        assertEquals(original.id(), renamed.id());
        assertEquals("Old", original.name());
    }

    @Test
    void roundTrip_throughGson_preservesAllFields() {
        UUID hostId = UUID.randomUUID();
        RunConfig original = RunConfig.create(
            "Full Config",
            hostId,
            "python3.11",
            List.of("-u"),
            "/home/deploy",
            Map.of("DEBUG", "1", "PORT", "8080"),
            List.of("--verbose", "input.csv")
        );

        String json = RunnerGson.GSON.toJson(original);
        RunConfig restored = RunnerGson.GSON.fromJson(json, RunConfig.class);

        assertEquals(original.id(), restored.id());
        assertEquals(original.name(), restored.name());
        assertEquals(original.hostId(), restored.hostId());
        assertEquals(original.interpreter(), restored.interpreter());
        assertEquals(original.args(), restored.args());
        assertEquals(original.workingDirectory(), restored.workingDirectory());
        assertEquals(original.envVars(), restored.envVars());
        assertEquals(original.scriptArgs(), restored.scriptArgs());
    }

    @Test
    void roundTrip_nullableFieldsAsNull() {
        RunConfig original = RunConfig.create("Minimal", null, "bash", List.of(), null, Map.of(), List.of());

        String json = RunnerGson.GSON.toJson(original);
        RunConfig restored = RunnerGson.GSON.fromJson(json, RunConfig.class);

        assertNull(restored.hostId());
        assertNull(restored.workingDirectory());
    }
}
