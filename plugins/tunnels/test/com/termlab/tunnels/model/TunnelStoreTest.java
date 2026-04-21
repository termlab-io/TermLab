package com.termlab.tunnels.model;

import com.termlab.tunnels.persistence.TunnelsFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
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

    @Test
    void listeners_fireOnMutationsAndReload(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("tunnels.json");
        TunnelStore store = new TunnelStore(file, List.of());
        List<Integer> observedSizes = new ArrayList<>();
        Runnable listener = () -> observedSizes.add(store.getTunnels().size());
        store.addChangeListener(listener);

        SshTunnel tunnel = SshTunnel.create("a", TunnelType.LOCAL, new SshConfigHost("h"),
            3307, "localhost", "target", 3306);
        store.addTunnel(tunnel);
        store.updateTunnel(tunnel.withLabel("b"));
        store.removeTunnel(tunnel.id());
        TunnelsFile.save(file, List.of(tunnel));
        store.reload();

        assertEquals(List.of(1, 1, 0, 1), observedSizes);
    }

    @Test
    void removeChangeListener_stopsNotifications(@TempDir Path tmp) {
        TunnelStore store = new TunnelStore(tmp.resolve("tunnels.json"), List.of());
        List<Integer> observedSizes = new ArrayList<>();
        Runnable listener = () -> observedSizes.add(store.getTunnels().size());
        store.addChangeListener(listener);
        store.removeChangeListener(listener);

        store.addTunnel(SshTunnel.create("a", TunnelType.LOCAL, new SshConfigHost("h"),
            3307, "localhost", "target", 3306));

        assertTrue(observedSizes.isEmpty());
    }
}
