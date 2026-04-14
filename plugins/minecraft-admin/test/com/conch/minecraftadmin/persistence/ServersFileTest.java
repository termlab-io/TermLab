package com.conch.minecraftadmin.persistence;

import com.conch.minecraftadmin.model.ServerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ServersFileTest {

    @Test
    void save_then_load_roundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("minecraft-servers.json");
        ServerProfile p1 = ServerProfile.create(
            "Survival", "https://amp:8080", "survival", "admin",
            UUID.randomUUID(), "mc.example.com", 25575, UUID.randomUUID());
        ServerProfile p2 = ServerProfile.create(
            "Creative", "https://amp:8080", "creative", "admin",
            UUID.randomUUID(), "mc.example.com", 25576, UUID.randomUUID());

        ServersFile.save(file, List.of(p1, p2));
        List<ServerProfile> loaded = ServersFile.load(file);

        assertEquals(2, loaded.size());
        assertEquals(p1, loaded.get(0));
        assertEquals(p2, loaded.get(1));
    }

    @Test
    void load_missingFile_returnsEmptyList(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("does-not-exist.json");
        assertEquals(List.of(), ServersFile.load(file));
    }

    @Test
    void load_corruptJson_throwsIOException(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("minecraft-servers.json");
        Files.writeString(file, "{\"version\": 1, \"servers\":");  // truncated
        assertThrows(IOException.class, () -> ServersFile.load(file));
    }

    @Test
    void load_wrongVersion_throwsIOException(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("minecraft-servers.json");
        Files.writeString(file, "{\"version\": 99, \"servers\": []}");
        assertThrows(IOException.class, () -> ServersFile.load(file));
    }

    @Test
    void save_writesAtomically(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("minecraft-servers.json");
        ServersFile.save(file, List.of());
        // File must exist and no temp files should remain.
        assertTrue(Files.exists(file));
        try (var stream = Files.list(dir)) {
            long nonTarget = stream.filter(p -> !p.equals(file)).count();
            assertEquals(0, nonTarget, "temp files must not be left behind");
        }
    }
}
