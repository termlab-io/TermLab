package com.termlab.proxmox.persistence;

import com.termlab.proxmox.model.PveCluster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PveClustersFileTest {
    @TempDir Path tempDir;

    @Test
    void roundTripsClusterList() throws Exception {
        Path file = tempDir.resolve("clusters.json");
        UUID credentialId = UUID.randomUUID();
        PveCluster cluster = new PveCluster(UUID.randomUUID(), "Lab", "https://pve:8006", credentialId, "AA:BB");

        PveClustersFile.save(file, List.of(cluster));
        List<PveCluster> loaded = PveClustersFile.load(file);

        assertEquals(1, loaded.size());
        assertEquals("Lab", loaded.get(0).label());
        assertEquals(credentialId, loaded.get(0).credentialId());
        assertEquals("AA:BB", loaded.get(0).trustedCertificateSha256());
    }

    @Test
    void malformedFileReturnsEmptyList() throws Exception {
        Path file = tempDir.resolve("clusters.json");
        java.nio.file.Files.writeString(file, "{bad");

        assertTrue(PveClustersFile.load(file).isEmpty());
    }
}
