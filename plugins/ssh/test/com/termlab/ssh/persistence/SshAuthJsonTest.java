package com.termlab.ssh.persistence;

import com.termlab.ssh.model.KeyFileAuth;
import com.termlab.ssh.model.PromptPasswordAuth;
import com.termlab.ssh.model.SshAuth;
import com.termlab.ssh.model.VaultAuth;
import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SshAuthJsonTest {

    @Test
    void vaultAuth_withId_roundTrip() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String json = SshGson.GSON.toJson(new VaultAuth(id), SshAuth.class);
        assertTrue(json.contains("\"type\": \"vault\""), json);
        assertTrue(json.contains("\"credentialId\": \"" + id + "\""), json);

        SshAuth parsed = SshGson.GSON.fromJson(json, SshAuth.class);
        assertInstanceOf(VaultAuth.class, parsed);
        assertEquals(id, ((VaultAuth) parsed).credentialId());
    }

    @Test
    void vaultAuth_withoutId_roundTrip() {
        String json = SshGson.GSON.toJson(new VaultAuth(null), SshAuth.class);
        assertTrue(json.contains("\"type\": \"vault\""));
        assertFalse(json.contains("credentialId"), json);

        SshAuth parsed = SshGson.GSON.fromJson(json, SshAuth.class);
        assertInstanceOf(VaultAuth.class, parsed);
        assertNull(((VaultAuth) parsed).credentialId());
    }

    @Test
    void promptPasswordAuth_roundTrip() {
        String json = SshGson.GSON.toJson(new PromptPasswordAuth(), SshAuth.class);
        assertTrue(json.contains("\"type\": \"prompt_password\""), json);

        SshAuth parsed = SshGson.GSON.fromJson(json, SshAuth.class);
        assertInstanceOf(PromptPasswordAuth.class, parsed);
    }

    @Test
    void keyFileAuth_roundTrip() {
        String json = SshGson.GSON.toJson(
            new KeyFileAuth("/home/alice/.ssh/id_ed25519"), SshAuth.class);
        assertTrue(json.contains("\"type\": \"key_file\""));
        assertTrue(json.contains("\"keyFilePath\": \"/home/alice/.ssh/id_ed25519\""));

        SshAuth parsed = SshGson.GSON.fromJson(json, SshAuth.class);
        assertInstanceOf(KeyFileAuth.class, parsed);
        assertEquals("/home/alice/.ssh/id_ed25519", ((KeyFileAuth) parsed).keyFilePath());
    }

    @Test
    void unknownType_throws() {
        String bad = "{ \"type\": \"bogus\" }";
        assertThrows(JsonParseException.class, () -> SshGson.GSON.fromJson(bad, SshAuth.class));
    }

    @Test
    void keyFileAuth_missingPath_throws() {
        String bad = "{ \"type\": \"key_file\" }";
        assertThrows(JsonParseException.class, () -> SshGson.GSON.fromJson(bad, SshAuth.class));
    }
}
