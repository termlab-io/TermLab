package com.conch.tunnels.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SshTunnelTest {

    @Test
    void create_populatesIdAndTimestamps() {
        SshTunnel tunnel = SshTunnel.create(
            "prod-db", TunnelType.LOCAL, new InternalHost(UUID.randomUUID()),
            3307, "localhost", "db.internal", 3306);
        assertNotNull(tunnel.id());
        assertEquals("prod-db", tunnel.label());
        assertEquals(TunnelType.LOCAL, tunnel.type());
        assertEquals(3307, tunnel.bindPort());
        assertEquals("localhost", tunnel.bindAddress());
        assertEquals("db.internal", tunnel.targetHost());
        assertEquals(3306, tunnel.targetPort());
        assertNotNull(tunnel.createdAt());
        assertEquals(tunnel.createdAt(), tunnel.updatedAt());
    }

    @Test
    void create_withSshConfigHost() {
        SshTunnel tunnel = SshTunnel.create(
            "bastion-redis", TunnelType.LOCAL, new SshConfigHost("bastion"),
            6380, "localhost", "redis.internal", 6379);
        assertInstanceOf(SshConfigHost.class, tunnel.host());
        assertEquals("bastion", ((SshConfigHost) tunnel.host()).alias());
    }

    @Test
    void create_remoteTunnel() {
        SshTunnel tunnel = SshTunnel.create(
            "expose-dev", TunnelType.REMOTE, new InternalHost(UUID.randomUUID()),
            9090, "0.0.0.0", "localhost", 8080);
        assertEquals(TunnelType.REMOTE, tunnel.type());
        assertEquals("0.0.0.0", tunnel.bindAddress());
    }

    @Test
    void withLabel_preservesIdentity() {
        SshTunnel original = SshTunnel.create(
            "old", TunnelType.LOCAL, new SshConfigHost("host"),
            3307, "localhost", "target", 3306);
        SshTunnel renamed = original.withLabel("new");
        assertEquals(original.id(), renamed.id());
        assertEquals(original.createdAt(), renamed.createdAt());
        assertEquals("new", renamed.label());
    }

    @Test
    void withEdited_replacesAllFields() {
        UUID hostId = UUID.randomUUID();
        SshTunnel original = SshTunnel.create(
            "old", TunnelType.LOCAL, new InternalHost(hostId),
            3307, "localhost", "old-target", 3306);
        UUID newHostId = UUID.randomUUID();
        SshTunnel edited = original.withEdited(
            "new", TunnelType.REMOTE, new InternalHost(newHostId),
            9090, "0.0.0.0", "new-target", 8080);
        assertEquals(original.id(), edited.id());
        assertEquals(original.createdAt(), edited.createdAt());
        assertEquals("new", edited.label());
        assertEquals(TunnelType.REMOTE, edited.type());
        assertEquals(9090, edited.bindPort());
        assertEquals("0.0.0.0", edited.bindAddress());
        assertEquals("new-target", edited.targetHost());
        assertEquals(8080, edited.targetPort());
    }

    @Test
    void defaultBindAddress() {
        assertEquals("localhost", SshTunnel.DEFAULT_BIND_ADDRESS);
    }
}
