package com.termlab.ssh.persistence;

import com.termlab.ssh.model.SshHost;
import com.termlab.ssh.model.VaultAuth;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HostsFileLegacyMigrationTest {

    @Test
    void legacyCredentialIdWithValue_migratesToVaultAuthWithId(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        UUID credId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Files.writeString(file, """
            {
              "version": 1,
              "hosts": [
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "label": "legacy",
                  "host": "example.com",
                  "port": 22,
                  "username": "alice",
                  "credentialId": "%s",
                  "createdAt": "2026-01-01T00:00:00Z",
                  "updatedAt": "2026-01-01T00:00:00Z"
                }
              ]
            }
            """.formatted(credId));

        List<SshHost> loaded = HostsFile.load(file);
        assertEquals(1, loaded.size());
        SshHost host = loaded.get(0);
        assertInstanceOf(VaultAuth.class, host.auth());
        assertEquals(credId, ((VaultAuth) host.auth()).credentialId());
    }

    @Test
    void legacyCredentialIdNull_migratesToVaultAuthWithNullId(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        Files.writeString(file, """
            {
              "version": 1,
              "hosts": [
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "label": "legacy",
                  "host": "example.com",
                  "port": 22,
                  "username": "alice",
                  "credentialId": null,
                  "createdAt": "2026-01-01T00:00:00Z",
                  "updatedAt": "2026-01-01T00:00:00Z"
                }
              ]
            }
            """);

        List<SshHost> loaded = HostsFile.load(file);
        assertEquals(1, loaded.size());
        assertInstanceOf(VaultAuth.class, loaded.get(0).auth());
        assertNull(((VaultAuth) loaded.get(0).auth()).credentialId());
    }

    @Test
    void saveAfterLoad_writesNewShapeAndDropsLegacyField(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        UUID credId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Files.writeString(file, """
            {
              "version": 1,
              "hosts": [
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "label": "legacy",
                  "host": "example.com",
                  "port": 22,
                  "username": "alice",
                  "credentialId": "%s",
                  "createdAt": "2026-01-01T00:00:00Z",
                  "updatedAt": "2026-01-01T00:00:00Z"
                }
              ]
            }
            """.formatted(credId));

        List<SshHost> loaded = HostsFile.load(file);
        HostsFile.save(file, loaded);

        // Structural check: the host object must carry an "auth" object
        // and must NOT carry a top-level "credentialId" field (the
        // latter would be a regression where the record somehow
        // re-gained the legacy property).
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject hostObj = root.getAsJsonArray("hosts").get(0).getAsJsonObject();
        assertTrue(hostObj.has("auth"), "host object should carry 'auth' after save");
        assertFalse(hostObj.has("credentialId"),
            "legacy top-level credentialId should be dropped after save");

        JsonObject authObj = hostObj.getAsJsonObject("auth");
        assertEquals("vault", authObj.get("type").getAsString());
        assertEquals(credId.toString(), authObj.get("credentialId").getAsString());

        // Functional check: reloading the rewritten file still yields VaultAuth.
        List<SshHost> reloaded = HostsFile.load(file);
        assertInstanceOf(VaultAuth.class, reloaded.get(0).auth());
        assertEquals(credId, ((VaultAuth) reloaded.get(0).auth()).credentialId());
    }
}
