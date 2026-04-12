package com.conch.tunnels.persistence;

import com.conch.tunnels.model.InternalHost;
import com.conch.tunnels.model.SshConfigHost;
import com.conch.tunnels.model.SshTunnel;
import com.conch.tunnels.model.TunnelType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TunnelsFileTest {

    @Test
    void load_missingFileReturnsEmpty(@TempDir Path tmp) throws Exception {
        assertTrue(TunnelsFile.load(tmp.resolve("nope.json")).isEmpty());
    }

    @Test
    void saveAndLoad_roundTrips(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("tunnels.json");
        UUID hostId = UUID.randomUUID();
        SshTunnel tunnel = SshTunnel.create(
            "prod-db", TunnelType.LOCAL, new InternalHost(hostId),
            3307, "localhost", "db.internal", 3306);

        TunnelsFile.save(file, List.of(tunnel));
        List<SshTunnel> loaded = TunnelsFile.load(file);

        assertEquals(1, loaded.size());
        SshTunnel restored = loaded.get(0);
        assertEquals(tunnel.id(), restored.id());
        assertEquals("prod-db", restored.label());
        assertEquals(TunnelType.LOCAL, restored.type());
        assertInstanceOf(InternalHost.class, restored.host());
        assertEquals(hostId, ((InternalHost) restored.host()).hostId());
        assertEquals(3307, restored.bindPort());
        assertEquals("db.internal", restored.targetHost());
    }

    @Test
    void saveAndLoad_sshConfigHost(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("tunnels.json");
        SshTunnel tunnel = SshTunnel.create(
            "bastion", TunnelType.REMOTE, new SshConfigHost("bastion"),
            9090, "0.0.0.0", "localhost", 8080);

        TunnelsFile.save(file, List.of(tunnel));
        List<SshTunnel> loaded = TunnelsFile.load(file);

        assertEquals(1, loaded.size());
        assertInstanceOf(SshConfigHost.class, loaded.get(0).host());
        assertEquals("bastion", ((SshConfigHost) loaded.get(0).host()).alias());
    }

    @Test
    void save_isAtomic(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("tunnels.json");
        TunnelsFile.save(file, List.of());
        assertFalse(Files.exists(tmp.resolve("tunnels.json.tmp")));
        assertTrue(Files.exists(file));
    }

    @Test
    void load_malformedReturnsEmpty(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("tunnels.json");
        Files.writeString(file, "{ not valid json");
        assertTrue(TunnelsFile.load(file).isEmpty());
    }
}
