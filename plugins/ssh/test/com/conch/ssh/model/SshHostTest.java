package com.conch.ssh.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SshHostTest {

    @Test
    void create_populatesIdAndTimestamps() {
        SshHost host = SshHost.create("prod", "example.com", 22, "root", null);
        assertNotNull(host.id());
        assertEquals("prod", host.label());
        assertEquals("example.com", host.host());
        assertEquals(22, host.port());
        assertEquals("root", host.username());
        assertNull(host.credentialId());
        assertNotNull(host.createdAt());
        assertEquals(host.createdAt(), host.updatedAt());
    }

    @Test
    void withLabel_preservesIdentityBumpsUpdatedAt() throws InterruptedException {
        SshHost original = SshHost.create("prod", "example.com", 22, "root", null);
        Thread.sleep(5);
        SshHost renamed = original.withLabel("production-db");
        assertEquals(original.id(), renamed.id());
        assertEquals(original.createdAt(), renamed.createdAt());
        assertEquals("production-db", renamed.label());
        assertTrue(renamed.updatedAt().isAfter(original.updatedAt()));
    }

    @Test
    void withCredentialId_preservesIdentity() {
        SshHost original = SshHost.create("prod", "example.com", 22, "root", null);
        UUID credentialId = UUID.randomUUID();
        SshHost linked = original.withCredentialId(credentialId);
        assertEquals(original.id(), linked.id());
        assertEquals(credentialId, linked.credentialId());
    }

    @Test
    void withCredentialId_nullClearsLink() {
        UUID initialCred = UUID.randomUUID();
        SshHost original = SshHost.create("prod", "example.com", 22, "root", initialCred);
        SshHost unlinked = original.withCredentialId(null);
        assertNull(unlinked.credentialId());
    }

    @Test
    void withEdited_replacesAllEditableFields() {
        SshHost original = SshHost.create("prod", "example.com", 22, "root", null);
        UUID newCredentialId = UUID.randomUUID();
        SshHost edited = original.withEdited("staging", "stage.example.com", 2222, "deploy", newCredentialId);

        assertEquals(original.id(), edited.id());
        assertEquals(original.createdAt(), edited.createdAt());
        assertEquals("staging", edited.label());
        assertEquals("stage.example.com", edited.host());
        assertEquals(2222, edited.port());
        assertEquals("deploy", edited.username());
        assertEquals(newCredentialId, edited.credentialId());
    }

    @Test
    void defaultPortMatchesOpenSsh() {
        assertEquals(22, SshHost.DEFAULT_PORT);
    }
}
