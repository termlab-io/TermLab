package com.conch.tunnels.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TunnelStoreTest {

    @Test
    void addAndGet(@TempDir Path tmp) {
        TunnelStore store = new TunnelStore(tmp.resolve("tunnels.json"), List.of());
        SshTunnel t = SshTunnel.create("a", TunnelType.LOCAL, new SshConfigHost("h"),
            3307, "localhost", "target", 3306);
        store.addTunnel(t);
        assertEquals(1, store.getTunnels().size());
        assertEquals(t.id(), store.findById(t.id()).id());
    }

    @Test
    void remove(@TempDir Path tmp) {
        TunnelStore store = new TunnelStore(tmp.resolve("tunnels.json"), List.of());
        SshTunnel t = SshTunnel.create("a", TunnelType.LOCAL, new SshConfigHost("h"),
            3307, "localhost", "target", 3306);
        store.addTunnel(t);
        assertTrue(store.removeTunnel(t.id()));
        assertEquals(0, store.getTunnels().size());
    }

    @Test
    void update(@TempDir Path tmp) {
        TunnelStore store = new TunnelStore(tmp.resolve("tunnels.json"), List.of());
        SshTunnel t = SshTunnel.create("a", TunnelType.LOCAL, new SshConfigHost("h"),
            3307, "localhost", "target", 3306);
        store.addTunnel(t);
        SshTunnel edited = t.withLabel("b");
        assertTrue(store.updateTunnel(edited));
        assertEquals("b", store.findById(t.id()).label());
    }

    @Test
    void saveAndReload(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("tunnels.json");
        TunnelStore store = new TunnelStore(file, List.of());
        store.addTunnel(SshTunnel.create("a", TunnelType.LOCAL, new SshConfigHost("h"),
            3307, "localhost", "target", 3306));
        store.save();

        TunnelStore reloaded = new TunnelStore(file);
        assertEquals(1, reloaded.getTunnels().size());
        assertEquals("a", reloaded.getTunnels().get(0).label());
    }

    @Test
    void findById_missingReturnsNull(@TempDir Path tmp) {
        TunnelStore store = new TunnelStore(tmp.resolve("tunnels.json"), List.of());
        assertNull(store.findById(UUID.randomUUID()));
    }
}
