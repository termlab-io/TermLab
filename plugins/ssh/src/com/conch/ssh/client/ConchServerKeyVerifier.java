package com.conch.ssh.client;

import com.conch.ssh.persistence.HostPaths;
import com.conch.ssh.persistence.KnownHostsFile;
import com.conch.ssh.ui.HostKeyPromptDialog;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.ClientSessionCreator;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.security.PublicKey;

/**
 * The {@link ServerKeyVerifier} the SSH plugin actually ships. Replaces
 * the placeholder {@code AcceptAllServerKeyVerifier} that Phase 4 was
 * using, and closes the MITM window:
 *
 * <ul>
 *   <li>{@link KnownHostsFile.Match#MATCH} → proceed silently</li>
 *   <li>{@link KnownHostsFile.Match#MISMATCH} → show a scary error
 *       dialog and return {@code false}. There is deliberately no
 *       "accept anyway" path — if the key legitimately changed, the
 *       user has to remove the entry from {@code known_hosts} by hand.</li>
 *   <li>{@link KnownHostsFile.Match#UNKNOWN} → prompt the user via
 *       {@link HostKeyPromptDialog}. On {@code ACCEPT_AND_SAVE} we
 *       append to {@code known_hosts}. On {@code ACCEPT_ONCE} we proceed
 *       for this session only. On {@code REJECT} we refuse.</li>
 * </ul>
 *
 * <p>The verifier runs on MINA's IO thread (called mid-handshake), but
 * Swing dialogs have to run on the EDT. All UI touches use
 * {@code ApplicationManager.invokeAndWait} so the MINA call blocks
 * until the user has chosen, then the decision is returned upstream.
 *
 * <p>The known-hosts file path is injectable (not pulled from
 * {@link HostPaths} directly) so unit tests can point at a
 * {@code @TempDir}.
 */
public final class ConchServerKeyVerifier implements ServerKeyVerifier {

    private static final Logger LOG = Logger.getInstance(ConchServerKeyVerifier.class);

    private final @NotNull Path knownHostsPath;
    private final @NotNull PromptUi promptUi;

    /** Production constructor — uses {@link HostPaths#knownHostsFile()} and real dialogs. */
    public ConchServerKeyVerifier() {
        this(HostPaths.knownHostsFile(), new SwingPromptUi());
    }

    /** Test constructor — explicit path and UI. */
    public ConchServerKeyVerifier(@NotNull Path knownHostsPath, @NotNull PromptUi promptUi) {
        this.knownHostsPath = knownHostsPath;
        this.promptUi = promptUi;
    }

    @Override
    public boolean verifyServerKey(
        ClientSession session,
        SocketAddress remoteAddress,
        PublicKey serverKey
    ) {
        // Diagnostic: the verifier is called from MINA's IO thread, and
        // on a proxy-jumped connection it is called TWICE — once for the
        // bastion session and once for the inner target session. These
        // logs let us tell the two apart and catch cases where the
        // TARGET_SERVER attribute isn't being propagated (which would
        // mean we silently trust the wrong host).
        SshdSocketAddress targetServer = session.getAttribute(ClientSessionCreator.TARGET_SERVER);
        LOG.info("Conch SSH: verifyServerKey entry"
            + " thread=" + Thread.currentThread().getName()
            + " session=" + System.identityHashCode(session)
            + " connectAddress=" + session.getConnectAddress()
            + " remoteAddress=" + remoteAddress
            + " remoteAddressClass=" + (remoteAddress == null ? "null" : remoteAddress.getClass().getSimpleName())
            + " TARGET_SERVER=" + (targetServer == null
                ? "<none:bastion-hop>"
                : targetServer.getHostName() + ":" + targetServer.getPort())
            + " keyAlgo=" + serverKey.getAlgorithm()
            + " keyFingerprint=" + KnownHostsFile.fingerprint(serverKey));

        VerificationTarget target = resolveVerificationTarget(session, remoteAddress);
        String host = target.host();
        int port = target.port();
        LOG.info("Conch SSH: host key verify target=" + host + ":" + port + " source=" + target.source());

        KnownHostsFile.Match match;
        try {
            match = KnownHostsFile.match(knownHostsPath, host, port, serverKey);
        } catch (IOException e) {
            LOG.warn("Conch: failed to read known_hosts at " + knownHostsPath, e);
            return false;
        }
        LOG.info("Conch SSH: host key verify result target=" + host + ":" + port + " match=" + match);

        return switch (match) {
            case MATCH -> {
                LOG.info("Conch SSH: host key MATCH, proceeding silently target=" + host + ":" + port);
                yield true;
            }
            case MISMATCH -> {
                LOG.warn("Conch SSH: host key MISMATCH, refusing target=" + host + ":" + port);
                promptUi.showMismatch(host, port);
                yield false;
            }
            case UNKNOWN -> handleUnknown(host, port, serverKey);
        };
    }

    private boolean handleUnknown(String host, int port, PublicKey serverKey) {
        String hostSpec = port == 22 ? host : host + ":" + port;
        String keyType = serverKey.getAlgorithm();
        String fingerprint = KnownHostsFile.fingerprint(serverKey);

        LOG.info("Conch SSH: host key UNKNOWN, dispatching prompt target=" + hostSpec
            + " keyType=" + keyType + " fingerprint=" + fingerprint
            + " callerThread=" + Thread.currentThread().getName());
        long startNs = System.nanoTime();
        HostKeyPromptDialog.Decision decision;
        try {
            decision = promptUi.prompt(hostSpec, keyType, fingerprint);
        } catch (Throwable t) {
            LOG.warn("Conch SSH: host key prompt threw for target=" + hostSpec
                + " after " + ((System.nanoTime() - startNs) / 1_000_000) + "ms", t);
            return false;
        }
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
        LOG.info("Conch SSH: host key prompt returned target=" + hostSpec
            + " decision=" + decision + " elapsedMs=" + elapsedMs);

        return switch (decision) {
            case ACCEPT_AND_SAVE -> {
                try {
                    KnownHostsFile.append(knownHostsPath, host, port, serverKey);
                    LOG.info("Conch SSH: host key appended to known_hosts target=" + hostSpec);
                } catch (IOException e) {
                    // Log and proceed — an I/O failure here doesn't compromise
                    // the current session; it just means next connect re-prompts.
                    LOG.warn("Conch: failed to append " + host + ":" + port
                        + " to known_hosts at " + knownHostsPath, e);
                }
                yield true;
            }
            case ACCEPT_ONCE -> true;
            case REJECT -> false;
        };
    }

    private static @NotNull VerificationTarget resolveVerificationTarget(
        @NotNull ClientSession session,
        @NotNull SocketAddress remoteAddress
    ) {
        SshdSocketAddress targetServer = session.getAttribute(ClientSessionCreator.TARGET_SERVER);
        if (targetServer != null) {
            return new VerificationTarget(targetServer.getHostName(), targetServer.getPort(), "proxy-jump-target");
        }

        SocketAddress connectAddress = session.getConnectAddress();
        if (connectAddress instanceof InetSocketAddress inet) {
            return new VerificationTarget(inet.getHostString(), inet.getPort(), "session-connect-address");
        }
        if (connectAddress instanceof SshdSocketAddress sshd) {
            return new VerificationTarget(sshd.getHostName(), sshd.getPort(), "session-connect-address");
        }
        if (remoteAddress instanceof InetSocketAddress inet) {
            return new VerificationTarget(inet.getHostString(), inet.getPort(), "remote-address");
        }
        if (remoteAddress instanceof SshdSocketAddress sshd) {
            return new VerificationTarget(sshd.getHostName(), sshd.getPort(), "remote-address");
        }

        return new VerificationTarget(String.valueOf(remoteAddress), 22, "remote-address-string");
    }

    private record VerificationTarget(@NotNull String host, int port, @NotNull String source) {
    }

    /**
     * Seam so tests can assert on verifier behavior without spinning up
     * a real {@link javax.swing.JDialog} or requiring an IDE application.
     */
    public interface PromptUi {
        @NotNull HostKeyPromptDialog.Decision prompt(
            @NotNull String hostSpec,
            @NotNull String keyType,
            @NotNull String fingerprint);

        void showMismatch(@NotNull String host, int port);
    }

    /** Default implementation — real Swing dialogs dispatched to the EDT. */
    private static final class SwingPromptUi implements PromptUi {
        private static final Logger LOG = Logger.getInstance(SwingPromptUi.class);

        @Override
        public @NotNull HostKeyPromptDialog.Decision prompt(
            @NotNull String hostSpec,
            @NotNull String keyType,
            @NotNull String fingerprint
        ) {
            // MINA calls the verifier from its IO thread; dialogs must
            // run on the EDT. invokeAndWait blocks the caller (MINA's IO
            // thread) until the user chooses, which is the correct
            // semantics — we want the handshake to block here.
            //
            // ModalityState.any() is critical: the connect runs inside a
            // Task.Modal whose nested event loop only processes runnables
            // that match its modality context. Without .any(), the prompt
            // is queued but never shown, causing the handshake to time out.
            HostKeyPromptDialog.Decision[] holder = { HostKeyPromptDialog.Decision.REJECT };
            long enqueueNs = System.nanoTime();
            LOG.info("Conch SSH: SwingPromptUi.prompt enqueue target=" + hostSpec
                + " callerThread=" + Thread.currentThread().getName());

            ApplicationManager.getApplication().invokeAndWait(() -> {
                long dispatchMs = (System.nanoTime() - enqueueNs) / 1_000_000;
                LOG.info("Conch SSH: SwingPromptUi.prompt EDT dispatch target=" + hostSpec
                    + " edtThread=" + Thread.currentThread().getName()
                    + " queuedMs=" + dispatchMs);
                try {
                    HostKeyPromptDialog dlg = new HostKeyPromptDialog(
                        null, hostSpec, keyType, fingerprint);
                    holder[0] = dlg.showAndDecide();
                    LOG.info("Conch SSH: SwingPromptUi.prompt showAndDecide returned target=" + hostSpec
                        + " decision=" + holder[0]);
                } catch (Throwable t) {
                    LOG.warn("Conch SSH: SwingPromptUi.prompt dialog construction/show failed target="
                        + hostSpec, t);
                    holder[0] = HostKeyPromptDialog.Decision.REJECT;
                }
            }, ModalityState.any());

            long totalMs = (System.nanoTime() - enqueueNs) / 1_000_000;
            LOG.info("Conch SSH: SwingPromptUi.prompt invokeAndWait returned target=" + hostSpec
                + " totalMs=" + totalMs + " decision=" + holder[0]);
            return holder[0];
        }

        @Override
        public void showMismatch(@NotNull String host, int port) {
            ApplicationManager.getApplication().invokeAndWait(() -> Messages.showErrorDialog(
                "The host key presented by " + host + ":" + port + " does not match the "
                    + "one Conch has on file. Someone may be intercepting your connection "
                    + "(man-in-the-middle attack).\n\n"
                    + "If the key legitimately changed, remove the entry from "
                    + "~/.config/conch/known_hosts manually and try again.",
                "Host Key Mismatch"), ModalityState.any());
        }
    }
}
