package com.termlab.proxmox.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PveClusterStoreTest {
    @TempDir Path tempDir;

    @Test
    void sortsPersistsAndNotifies() throws Exception {
        PveClusterStore store = new PveClusterStore(tempDir.resolve("clusters.json"), List.of());
        AtomicInteger changes = new AtomicInteger();
        store.addChangeListener(changes::incrementAndGet);

        PveCluster zed = cluster("Zed");
        PveCluster alpha = cluster("Alpha");
        store.addCluster(zed);
        store.addCluster(alpha);
        store.save();

        assertEquals("Alpha", store.getClusters().get(0).label());
        assertEquals(2, changes.get());

        PveClusterStore reloaded = new PveClusterStore(tempDir.resolve("clusters.json"), List.of());
        reloaded.reload();
        assertEquals(2, reloaded.getClusters().size());
    }

    private static PveCluster cluster(String label) {
        return new PveCluster(UUID.randomUUID(), label, "https://" + label.toLowerCase() + ":8006", UUID.randomUUID(), null);
    }
}
