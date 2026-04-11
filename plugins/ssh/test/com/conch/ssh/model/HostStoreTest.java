package com.conch.ssh.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HostStoreTest {

    @Test
    void newStore_isEmpty() {
        HostStore store = new HostStore();
        assertEquals(0, store.size());
        assertTrue(store.getHosts().isEmpty());
    }

    @Test
    void addHost_appendsToList() {
        HostStore store = new HostStore();
        SshHost a = SshHost.create("a", "host-a", 22, "u", null);
        SshHost b = SshHost.create("b", "host-b", 22, "u", null);
        store.addHost(a);
        store.addHost(b);
        assertEquals(2, store.size());
        assertEquals(a.id(), store.getHosts().get(0).id());
        assertEquals(b.id(), store.getHosts().get(1).id());
    }

    @Test
    void removeHost_returnsTrueOnHit() {
        HostStore store = new HostStore();
        SshHost host = SshHost.create("a", "host", 22, "u", null);
        store.addHost(host);
        assertTrue(store.removeHost(host.id()));
        assertEquals(0, store.size());
    }

    @Test
    void removeHost_returnsFalseOnMiss() {
        HostStore store = new HostStore();
        assertFalse(store.removeHost(UUID.randomUUID()));
    }

    @Test
    void updateHost_replacesInPlace() {
        HostStore store = new HostStore();
        SshHost original = SshHost.create("old-label", "host", 22, "u", null);
        store.addHost(original);

        SshHost renamed = original.withLabel("new-label");
        assertTrue(store.updateHost(renamed));
        assertEquals("new-label", store.getHosts().get(0).label());
    }

    @Test
    void updateHost_missingIdReturnsFalse() {
        HostStore store = new HostStore();
        SshHost stranger = SshHost.create("nope", "host", 22, "u", null);
        assertFalse(store.updateHost(stranger));
    }

    @Test
    void findById_matches() {
        HostStore store = new HostStore();
        SshHost host = SshHost.create("a", "host", 22, "u", null);
        store.addHost(host);
        assertSame(host, store.findById(host.id()));
        assertNull(store.findById(UUID.randomUUID()));
    }

    @Test
    void getHosts_returnsDefensiveSnapshot() {
        HostStore store = new HostStore();
        store.addHost(SshHost.create("a", "host", 22, "u", null));
        List<SshHost> snapshot = store.getHosts();
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.add(SshHost.create("b", "host2", 22, "u", null)));
    }

    @Test
    void constructor_acceptsInitialList() {
        SshHost a = SshHost.create("a", "h-a", 22, "u", null);
        SshHost b = SshHost.create("b", "h-b", 22, "u", null);
        HostStore store = new HostStore(List.of(a, b));
        assertEquals(2, store.size());
    }

    @Test
    void clear_emptiesStore() {
        HostStore store = new HostStore();
        store.addHost(SshHost.create("a", "h", 22, "u", null));
        store.clear();
        assertEquals(0, store.size());
    }
}
