package com.conch.ssh.model;

import com.conch.ssh.persistence.HostsFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HostStoreTest {

    @Test
    void newStore_isEmpty(@TempDir Path tmp) {
        HostStore store = new HostStore(tmp.resolve("ssh-hosts.json"), List.of());
        assertEquals(0, store.size());
        assertTrue(store.getHosts().isEmpty());
    }

    @Test
    void addHost_appendsToList(@TempDir Path tmp) {
        HostStore store = new HostStore(tmp.resolve("ssh-hosts.json"), List.of());
        SshHost a = SshHost.create("a", "host-a", 22, "u", null);
        SshHost b = SshHost.create("b", "host-b", 22, "u", null);
        store.addHost(a);
        store.addHost(b);
        assertEquals(2, store.size());
        assertEquals(a.id(), store.getHosts().get(0).id());
        assertEquals(b.id(), store.getHosts().get(1).id());
    }

    @Test
    void removeHost_returnsTrueOnHit(@TempDir Path tmp) {
        HostStore store = new HostStore(tmp.resolve("ssh-hosts.json"), List.of());
        SshHost host = SshHost.create("a", "host", 22, "u", null);
        store.addHost(host);
        assertTrue(store.removeHost(host.id()));
        assertEquals(0, store.size());
    }

    @Test
    void removeHost_returnsFalseOnMiss(@TempDir Path tmp) {
        HostStore store = new HostStore(tmp.resolve("ssh-hosts.json"), List.of());
        assertFalse(store.removeHost(UUID.randomUUID()));
    }

    @Test
    void updateHost_replacesInPlace(@TempDir Path tmp) {
        HostStore store = new HostStore(tmp.resolve("ssh-hosts.json"), List.of());
        SshHost original = SshHost.create("old-label", "host", 22, "u", null);
        store.addHost(original);

        SshHost renamed = original.withLabel("new-label");
        assertTrue(store.updateHost(renamed));
        assertEquals("new-label", store.getHosts().get(0).label());
    }

    @Test
    void updateHost_missingIdReturnsFalse(@TempDir Path tmp) {
        HostStore store = new HostStore(tmp.resolve("ssh-hosts.json"), List.of());
        SshHost stranger = SshHost.create("nope", "host", 22, "u", null);
        assertFalse(store.updateHost(stranger));
    }

    @Test
    void findById_matches(@TempDir Path tmp) {
        HostStore store = new HostStore(tmp.resolve("ssh-hosts.json"), List.of());
        SshHost host = SshHost.create("a", "host", 22, "u", null);
        store.addHost(host);
        assertSame(host, store.findById(host.id()));
        assertNull(store.findById(UUID.randomUUID()));
    }

    @Test
    void getHosts_returnsDefensiveSnapshot(@TempDir Path tmp) {
        HostStore store = new HostStore(tmp.resolve("ssh-hosts.json"), List.of());
        store.addHost(SshHost.create("a", "host", 22, "u", null));
        List<SshHost> snapshot = store.getHosts();
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.add(SshHost.create("b", "host2", 22, "u", null)));
    }

    @Test
    void constructor_acceptsInitialList(@TempDir Path tmp) {
        SshHost a = SshHost.create("a", "h-a", 22, "u", null);
        SshHost b = SshHost.create("b", "h-b", 22, "u", null);
        HostStore store = new HostStore(tmp.resolve("ssh-hosts.json"), List.of(a, b));
        assertEquals(2, store.size());
    }

    @Test
    void clear_emptiesStore(@TempDir Path tmp) {
        HostStore store = new HostStore(tmp.resolve("ssh-hosts.json"), List.of());
        store.addHost(SshHost.create("a", "h", 22, "u", null));
        store.clear();
        assertEquals(0, store.size());
    }

    @Test
    void save_persistsToDisk(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        HostStore store = new HostStore(file, List.of());
        SshHost host = SshHost.create("prod", "example.com", 22, "admin", null);
        store.addHost(host);
        store.save();

        assertTrue(Files.exists(file));
        List<SshHost> reloaded = HostsFile.load(file);
        assertEquals(1, reloaded.size());
        assertEquals(host.id(), reloaded.get(0).id());
    }

    @Test
    void reload_readsFromDisk(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        SshHost diskHost = SshHost.create("on-disk", "example.com", 22, "admin", null);
        HostsFile.save(file, List.of(diskHost));

        HostStore store = new HostStore(file, List.of());
        assertEquals(0, store.size());
        store.reload();
        assertEquals(1, store.size());
        assertEquals(diskHost.id(), store.getHosts().get(0).id());
    }

    @Test
    void reload_discardsUnsavedState(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        HostsFile.save(file, List.of());

        HostStore store = new HostStore(file, List.of());
        store.addHost(SshHost.create("unsaved", "example.com", 22, "admin", null));
        assertEquals(1, store.size());

        store.reload();
        assertEquals(0, store.size());
    }
}
