package com.termlab.ssh.client;

import com.termlab.ssh.persistence.KnownHostsFile;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.ClientSessionCreator;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Security;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TermLabServerKeyVerifierProxyJumpTest {

    @BeforeAll
    static void registerBouncyCastle() {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    @Test
    void verifyServerKey_usesTargetServerAttributeForProxyJumpSession(@TempDir Path tmp) throws Exception {
        Path knownHosts = tmp.resolve("known_hosts");
        PublicKey key = freshEd25519Key();
        KnownHostsFile.append(knownHosts, "192.168.1.40", 22, key);

        TermLabServerKeyVerifier.PromptUi promptUi = (TermLabServerKeyVerifier.PromptUi) Proxy.newProxyInstance(
            TermLabServerKeyVerifierProxyJumpTest.class.getClassLoader(),
            new Class<?>[]{TermLabServerKeyVerifier.PromptUi.class},
            (proxy, method, args) -> {
                throw new AssertionError("Prompt UI should not be called for known-hosts MATCH: " + method.getName());
            });
        TermLabServerKeyVerifier verifier = new TermLabServerKeyVerifier(knownHosts, promptUi);

        InetSocketAddress loopbackForwardSocket = new InetSocketAddress("127.0.0.1", 49123);
        ClientSession session = fakeSession(
            loopbackForwardSocket,
            new SshdSocketAddress("192.168.1.40", 22));

        boolean accepted = verifier.verifyServerKey(session, loopbackForwardSocket, key);
        assertTrue(accepted, "Verifier should match against proxy target host, not loopback forward socket");
    }

    private static ClientSession fakeSession(
        InetSocketAddress connectAddress,
        SshdSocketAddress targetServer
    ) {
        Map<AttributeRepository.AttributeKey<?>, Object> attrs = new HashMap<>();
        attrs.put(ClientSessionCreator.TARGET_SERVER, targetServer);

        return (ClientSession) Proxy.newProxyInstance(
            TermLabServerKeyVerifierProxyJumpTest.class.getClassLoader(),
            new Class<?>[]{ClientSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getConnectAddress" -> connectAddress;
                case "getAttribute" -> attrs.get(args[0]);
                case "toString" -> "FakeClientSession";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(
                    "Unhandled ClientSession method in test stub: " + method.getName());
            });
    }

    private static PublicKey freshEd25519Key() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519", "BC");
        KeyPair kp = gen.generateKeyPair();
        return kp.getPublic();
    }
}
