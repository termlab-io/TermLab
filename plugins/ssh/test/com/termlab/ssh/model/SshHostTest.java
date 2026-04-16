package com.termlab.ssh.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SshHostTest {

    @Test
    void create_populatesIdAndTimestamps() {
        SshHost host = SshHost.create("prod", "example.com", 22, "root", new VaultAuth(null));
        assertNotNull(host.id());
        assertEquals("prod", host.label());
        assertEquals("example.com", host.host());
        assertEquals(22, host.port());
        assertEquals("root", host.username());
        assertInstanceOf(VaultAuth.class, host.auth());
        assertNull(((VaultAuth) host.auth()).credentialId());
        assertNotNull(host.createdAt());
        assertEquals(host.createdAt(), host.updatedAt());
    }

    @Test
    void withLabel_preservesIdentityBumpsUpdatedAt() throws InterruptedException {
        SshHost original = SshHost.create("prod", "example.com", 22, "root", new VaultAuth(null));
        Thread.sleep(5);
        SshHost renamed = original.withLabel("production-db");
        assertEquals(original.id(), renamed.id());
        assertEquals(original.createdAt(), renamed.createdAt());
        assertEquals("production-db", renamed.label());
        assertTrue(renamed.updatedAt().isAfter(original.updatedAt()));
    }

    @Test
    void withAuth_replacesAuthPreservingIdentity() {
        SshHost original = SshHost.create("prod", "example.com", 22, "root", new VaultAuth(null));
        UUID credId = UUID.randomUUID();
        SshHost linked = original.withAuth(new VaultAuth(credId));
        assertEquals(original.id(), linked.id());
        assertInstanceOf(VaultAuth.class, linked.auth());
        assertEquals(credId, ((VaultAuth) linked.auth()).credentialId());
    }

    @Test
    void withAuth_promptPasswordVariant() {
        SshHost original = SshHost.create("prod", "example.com", 22, "root", new VaultAuth(null));
        SshHost flipped = original.withAuth(new PromptPasswordAuth());
        assertInstanceOf(PromptPasswordAuth.class, flipped.auth());
    }

    @Test
    void withAuth_keyFileVariant() {
        SshHost original = SshHost.create("prod", "example.com", 22, "root", new VaultAuth(null));
        SshHost flipped = original.withAuth(new KeyFileAuth("/tmp/key"));
        assertInstanceOf(KeyFileAuth.class, flipped.auth());
        assertEquals("/tmp/key", ((KeyFileAuth) flipped.auth()).keyFilePath());
    }

    @Test
    void withEdited_replacesAllEditableFields() {
        SshHost original = SshHost.create("prod", "example.com", 22, "root", new VaultAuth(null));
        UUID newCredentialId = UUID.randomUUID();
        SshHost edited = original.withEdited(
            "staging", "stage.example.com", 2222, "deploy", new VaultAuth(newCredentialId));

        assertEquals(original.id(), edited.id());
        assertEquals(original.createdAt(), edited.createdAt());
        assertEquals("staging", edited.label());
        assertEquals("stage.example.com", edited.host());
        assertEquals(2222, edited.port());
        assertEquals("deploy", edited.username());
        assertEquals(newCredentialId, ((VaultAuth) edited.auth()).credentialId());
    }

    @Test
    void withEdited_includesProxySettings() {
        SshHost original = SshHost.create("prod", "example.com", 22, "root", new VaultAuth(null));
        SshHost edited = original.withEdited(
            "prod",
            "example.com",
            22,
            "root",
            new VaultAuth(null),
            "ssh -W %h:%p bastion",
            null);

        assertEquals("ssh -W %h:%p bastion", edited.proxyCommand());
        assertNull(edited.proxyJump());
    }

    @Test
    void create_trimsBlankProxyValuesToNull() {
        SshHost created = SshHost.create(
            "prod",
            "example.com",
            22,
            "root",
            new VaultAuth(null),
            "   ",
            "   ");

        assertNull(created.proxyCommand());
        assertNull(created.proxyJump());
    }

    @Test
    void defaultPortMatchesOpenSsh() {
        assertEquals(22, SshHost.DEFAULT_PORT);
    }
}
