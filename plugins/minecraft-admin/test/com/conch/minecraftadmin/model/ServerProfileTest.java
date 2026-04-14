package com.conch.minecraftadmin.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ServerProfileTest {

    @Test
    void create_populatesIdAndTimestamps() {
        UUID ampCred = UUID.randomUUID();
        UUID rconCred = UUID.randomUUID();
        ServerProfile profile = ServerProfile.create(
            "Survival",
            "https://amp.example.com:8080",
            "survival",
            "admin",
            ampCred,
            "mc.example.com",
            25575,
            rconCred);
        assertNotNull(profile.id());
        assertEquals("Survival", profile.label());
        assertEquals("https://amp.example.com:8080", profile.ampUrl());
        assertEquals("survival", profile.ampInstanceName());
        assertEquals("admin", profile.ampUsername());
        assertEquals(ampCred, profile.ampCredentialId());
        assertEquals("mc.example.com", profile.rconHost());
        assertEquals(25575, profile.rconPort());
        assertEquals(rconCred, profile.rconCredentialId());
        assertNotNull(profile.createdAt());
        assertEquals(profile.createdAt(), profile.updatedAt());
    }

    @Test
    void withLabel_preservesIdentity() {
        ServerProfile original = ServerProfile.create(
            "old", "url", "inst", "user", UUID.randomUUID(),
            "host", 25575, UUID.randomUUID());
        ServerProfile renamed = original.withLabel("new");
        assertEquals(original.id(), renamed.id());
        assertEquals(original.createdAt(), renamed.createdAt());
        assertEquals("new", renamed.label());
    }

    @Test
    void withEdited_replacesAllEditableFields() {
        UUID oldAmp = UUID.randomUUID();
        UUID oldRcon = UUID.randomUUID();
        ServerProfile original = ServerProfile.create(
            "old", "old-url", "old-inst", "old-user", oldAmp,
            "old-host", 25575, oldRcon);

        UUID newAmp = UUID.randomUUID();
        UUID newRcon = UUID.randomUUID();
        ServerProfile edited = original.withEdited(
            "new", "new-url", "new-inst", "new-user", newAmp,
            "new-host", 25576, newRcon);

        assertEquals(original.id(), edited.id());
        assertEquals(original.createdAt(), edited.createdAt());
        assertEquals("new", edited.label());
        assertEquals("new-url", edited.ampUrl());
        assertEquals("new-inst", edited.ampInstanceName());
        assertEquals("new-user", edited.ampUsername());
        assertEquals(newAmp, edited.ampCredentialId());
        assertEquals("new-host", edited.rconHost());
        assertEquals(25576, edited.rconPort());
        assertEquals(newRcon, edited.rconCredentialId());
    }

    @Test
    void defaultRconPort() {
        assertEquals(25575, ServerProfile.DEFAULT_RCON_PORT);
    }
}
