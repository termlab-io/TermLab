package com.conch.tunnels.persistence;

import com.conch.tunnels.model.InternalHost;
import com.conch.tunnels.model.SshConfigHost;
import com.conch.tunnels.model.TunnelHost;
import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TunnelHostJsonTest {

    @Test
    void internalHost_roundTrip() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String json = TunnelGson.GSON.toJson(new InternalHost(id), TunnelHost.class);
        assertTrue(json.contains("\"type\": \"internal\""), json);
        assertTrue(json.contains(id.toString()), json);

        TunnelHost parsed = TunnelGson.GSON.fromJson(json, TunnelHost.class);
        assertInstanceOf(InternalHost.class, parsed);
        assertEquals(id, ((InternalHost) parsed).hostId());
    }

    @Test
    void sshConfigHost_roundTrip() {
        String json = TunnelGson.GSON.toJson(new SshConfigHost("bastion"), TunnelHost.class);
        assertTrue(json.contains("\"type\": \"ssh_config\""), json);
        assertTrue(json.contains("\"alias\": \"bastion\""), json);

        TunnelHost parsed = TunnelGson.GSON.fromJson(json, TunnelHost.class);
        assertInstanceOf(SshConfigHost.class, parsed);
        assertEquals("bastion", ((SshConfigHost) parsed).alias());
    }

    @Test
    void unknownType_throws() {
        assertThrows(JsonParseException.class,
            () -> TunnelGson.GSON.fromJson("{\"type\":\"bogus\"}", TunnelHost.class));
    }
}
