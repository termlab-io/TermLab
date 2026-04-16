package com.termlab.sftp.session;

import com.termlab.sftp.client.SshSftpSession;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.client.SshResolvedCredential;
import com.termlab.ssh.credentials.HostCredentialBundle;
import com.termlab.ssh.model.PromptPasswordAuth;
import com.termlab.ssh.model.SshHost;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SftpSessionManagerTest {

    // SshHost record: (UUID id, String label, String host, int port,
    //   String username, SshAuth auth, String proxyCommand, String proxyJump,
    //   Instant createdAt, Instant updatedAt)
    private static SshHost makeHost(UUID id) {
        Instant now = Instant.now();
        return new SshHost(id, "label-" + id, "host-" + id, 22, "user",
            new PromptPasswordAuth(), null, null, now, now);
    }

    /**
     * Creates a no-op SshSftpSession suitable for unit tests. Both
     * ClientSession and SftpClient are interfaces in Apache MINA SSHD, so we
     * build proxy instances that return null / default values for all methods.
     * The SshSftpSession.close() method calls sftpClient.close() (an
     * IOException-throwing AutoCloseable) and session.close(boolean) — the
     * proxy swallows both without error.
     */
    private static SshSftpSession noOpSession() {
        ClassLoader cl = SftpSessionManagerTest.class.getClassLoader();

        SftpClient sftpProxy = (SftpClient) Proxy.newProxyInstance(
            cl,
            new Class<?>[]{SftpClient.class},
            (proxy, method, args) -> {
                // Return sensible defaults so close() and any other call succeeds.
                Class<?> ret = method.getReturnType();
                if (ret == boolean.class) return false;
                if (ret == int.class || ret == long.class || ret == short.class
                    || ret == byte.class || ret == char.class) return 0;
                return null;
            });

        ClientSession sessionProxy = (ClientSession) Proxy.newProxyInstance(
            cl,
            new Class<?>[]{ClientSession.class},
            (proxy, method, args) -> {
                Class<?> ret = method.getReturnType();
                if (ret == boolean.class) return false;
                if (ret == int.class || ret == long.class || ret == short.class
                    || ret == byte.class || ret == char.class) return 0;
                return null;
            });

        return new SshSftpSession(sessionProxy, sftpProxy);
    }

    private static final class FakeConnector implements SftpConnector {
        final AtomicInteger openCount = new AtomicInteger();

        @Override
        public @NotNull SshSftpSession open(@NotNull SshHost host, @NotNull HostCredentialBundle bundle) {
            openCount.incrementAndGet();
            return noOpSession();
        }

        @Override
        public @Nullable HostCredentialBundle resolveCredentials(@NotNull SshHost host) {
            // Tests don't need real credentials — FakeConnector.open() ignores the bundle.
            // Build a minimal stub using the public record constructor.
            SshResolvedCredential stub = SshResolvedCredential.password("test-user", new char[0]);
            return new HostCredentialBundle(stub, null);
        }
    }

    @Test
    void acquireOpensNewSessionWhenNonePresent() throws SshConnectException {
        FakeConnector connector = new FakeConnector();
        SftpSessionManager manager = new SftpSessionManager(connector);
        UUID hostId = UUID.randomUUID();
        SshHost host = makeHost(hostId);

        SshSftpSession session = manager.acquire(host, this);

        assertNotNull(session);
        assertEquals(1, connector.openCount.get());
        assertSame(session, manager.peek(hostId));
    }

    @Test
    void acquireReturnsSameSessionForSameHost() throws SshConnectException {
        FakeConnector connector = new FakeConnector();
        SftpSessionManager manager = new SftpSessionManager(connector);
        SshHost host = makeHost(UUID.randomUUID());

        Object owner1 = new Object();
        Object owner2 = new Object();
        SshSftpSession a = manager.acquire(host, owner1);
        SshSftpSession b = manager.acquire(host, owner2);

        assertSame(a, b);
        assertEquals(1, connector.openCount.get());
    }

    @Test
    void releaseDoesNotCloseWhileOtherOwnersPresent() throws SshConnectException {
        FakeConnector connector = new FakeConnector();
        SftpSessionManager manager = new SftpSessionManager(connector);
        SshHost host = makeHost(UUID.randomUUID());

        Object owner1 = new Object();
        Object owner2 = new Object();
        manager.acquire(host, owner1);
        manager.acquire(host, owner2);

        manager.release(host.id(), owner1);

        assertNotNull(manager.peek(host.id()));
    }

    @Test
    void releaseLastOwnerClosesSession() throws SshConnectException {
        FakeConnector connector = new FakeConnector();
        SftpSessionManager manager = new SftpSessionManager(connector);
        SshHost host = makeHost(UUID.randomUUID());

        Object owner = new Object();
        manager.acquire(host, owner);
        manager.release(host.id(), owner);

        assertNull(manager.peek(host.id()));
    }

    @Test
    void acquireAfterReleaseReconnects() throws SshConnectException {
        FakeConnector connector = new FakeConnector();
        SftpSessionManager manager = new SftpSessionManager(connector);
        SshHost host = makeHost(UUID.randomUUID());

        Object owner = new Object();
        manager.acquire(host, owner);
        manager.release(host.id(), owner);
        manager.acquire(host, owner);

        assertEquals(2, connector.openCount.get());
    }

    @Test
    void forceDisconnectClosesEvenWithActiveOwners() throws SshConnectException {
        FakeConnector connector = new FakeConnector();
        SftpSessionManager manager = new SftpSessionManager(connector);
        SshHost host = makeHost(UUID.randomUUID());

        manager.acquire(host, new Object());
        manager.acquire(host, new Object());

        manager.forceDisconnect(host.id());

        assertNull(manager.peek(host.id()));
    }

    @Test
    void connectedHostIdsReflectsState() throws SshConnectException {
        FakeConnector connector = new FakeConnector();
        SftpSessionManager manager = new SftpSessionManager(connector);
        SshHost host1 = makeHost(UUID.randomUUID());
        SshHost host2 = makeHost(UUID.randomUUID());

        manager.acquire(host1, this);
        manager.acquire(host2, this);

        assertTrue(manager.connectedHostIds().contains(host1.id()));
        assertTrue(manager.connectedHostIds().contains(host2.id()));
        assertEquals(2, manager.connectedHostIds().size());
    }
}
