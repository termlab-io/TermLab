package com.conch.ssh.persistence;

import com.conch.ssh.model.SshHost;
import com.conch.ssh.model.VaultAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HostsFileTest {

    @Test
    void load_missingFileReturnsEmpty(@TempDir Path tmp) throws Exception {
        List<SshHost> loaded = HostsFile.load(tmp.resolve("nothing-here.json"));
        assertTrue(loaded.isEmpty());
    }

    @Test
    void saveEmpty_thenLoad_roundTrips(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        HostsFile.save(file, List.of());
        assertTrue(Files.exists(file));
        List<SshHost> loaded = HostsFile.load(file);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void saveSingleHost_thenLoad_preservesFields(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        UUID credId = UUID.randomUUID();
        SshHost original = SshHost.create("prod", "db.example.com", 2222, "dbadmin", new VaultAuth(credId));

        HostsFile.save(file, List.of(original));
        List<SshHost> loaded = HostsFile.load(file);

        assertEquals(1, loaded.size());
        SshHost restored = loaded.get(0);
        assertEquals(original.id(), restored.id());
        assertEquals("prod", restored.label());
        assertEquals("db.example.com", restored.host());
        assertEquals(2222, restored.port());
        assertEquals("dbadmin", restored.username());
        assertEquals(credId, ((VaultAuth) restored.auth()).credentialId());
        assertEquals(original.createdAt(), restored.createdAt());
    }

    @Test
    void saveMultipleHosts_preservesOrder(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        SshHost a = SshHost.create("a", "host-a", 22, "u", new VaultAuth(null));
        SshHost b = SshHost.create("b", "host-b", 22, "u", new VaultAuth(null));
        SshHost c = SshHost.create("c", "host-c", 22, "u", new VaultAuth(null));
        HostsFile.save(file, List.of(a, b, c));

        List<SshHost> loaded = HostsFile.load(file);
        assertEquals(3, loaded.size());
        assertEquals("a", loaded.get(0).label());
        assertEquals("b", loaded.get(1).label());
        assertEquals("c", loaded.get(2).label());
    }

    @Test
    void save_vaultAuthWithNullId_roundTrips(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        SshHost host = SshHost.create("no-cred", "example.com", 22, "user", new VaultAuth(null));
        HostsFile.save(file, List.of(host));
        List<SshHost> loaded = HostsFile.load(file);
        assertNull(((VaultAuth) loaded.get(0).auth()).credentialId());
    }

    @Test
    void save_isAtomic_noTempFileRemains(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        HostsFile.save(file, List.of(SshHost.create("a", "h", 22, "u", new VaultAuth(null))));
        assertFalse(Files.exists(tmp.resolve("ssh-hosts.json.tmp")));
        assertTrue(Files.exists(file));
    }

    @Test
    void save_createsParentDirectory(@TempDir Path tmp) throws Exception {
        Path nested = tmp.resolve("a/b/c/ssh-hosts.json");
        HostsFile.save(nested, List.of());
        assertTrue(Files.exists(nested));
    }

    @Test
    void load_malformedJsonReturnsEmpty(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        Files.writeString(file, "{ not valid json");
        assertTrue(HostsFile.load(file).isEmpty());
    }

    @Test
    void load_wrongShapeReturnsEmpty(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        Files.writeString(file, "{ \"version\": 1 }");  // no "hosts" field
        assertTrue(HostsFile.load(file).isEmpty());
    }

    @Test
    void exists_reportsCorrectly(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        assertFalse(HostsFile.exists(file));
        HostsFile.save(file, List.of());
        assertTrue(HostsFile.exists(file));
    }

    @Test
    void save_fileIsPrettyPrinted(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        HostsFile.save(file, List.of(SshHost.create("a", "h", 22, "u", new VaultAuth(null))));
        String contents = Files.readString(file);
        // Pretty-printed JSON has newlines between fields — trivial smoke check.
        assertTrue(contents.contains("\n"),
            "expected pretty-printed output, got compact JSON");
    }

    @Test
    void save_proxySettings_roundTrip(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        SshHost host = SshHost.create(
            "through-bastion",
            "db.internal",
            22,
            "deploy",
            new VaultAuth(null),
            null,
            "jump-host");

        HostsFile.save(file, List.of(host));
        List<SshHost> loaded = HostsFile.load(file);

        assertEquals(1, loaded.size());
        assertNull(loaded.get(0).proxyCommand());
        assertEquals("jump-host", loaded.get(0).proxyJump());
    }
}
