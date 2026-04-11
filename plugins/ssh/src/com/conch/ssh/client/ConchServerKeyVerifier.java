package com.conch.ssh.client;

import com.conch.ssh.persistence.HostPaths;
import com.conch.ssh.persistence.KnownHostsFile;
import com.conch.ssh.ui.HostKeyPromptDialog;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
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
        // MINA gives us the session's connection address, which is always
        // what we asked for (MINA does DNS-resolution internally but still
        // reports the originally-requested host). Fall back to the socket
        // string only if the session's metadata is missing.
        String host = session.getConnectAddress() != null
            ? session.getConnectAddress().toString()
            : String.valueOf(remoteAddress);
        int port = 22;

        // MINA stores the originally-requested "host:port" as the
        // session's connect-address, typed as an InetSocketAddress in
        // practice. Peel the fields back out when we can.
        if (session.getConnectAddress() instanceof java.net.InetSocketAddress inet) {
            host = inet.getHostString();
            port = inet.getPort();
        }

        KnownHostsFile.Match match;
        try {
            match = KnownHostsFile.match(knownHostsPath, host, port, serverKey);
        } catch (IOException e) {
            LOG.warn("Conch: failed to read known_hosts at " + knownHostsPath, e);
            return false;
        }

        return switch (match) {
            case MATCH -> true;
            case MISMATCH -> {
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

        HostKeyPromptDialog.Decision decision = promptUi.prompt(hostSpec, keyType, fingerprint);
        return switch (decision) {
            case ACCEPT_AND_SAVE -> {
                try {
                    KnownHostsFile.append(knownHostsPath, host, port, serverKey);
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
            HostKeyPromptDialog.Decision[] holder = { HostKeyPromptDialog.Decision.REJECT };
            ApplicationManager.getApplication().invokeAndWait(() -> {
                HostKeyPromptDialog dlg = new HostKeyPromptDialog(
                    null, hostSpec, keyType, fingerprint);
                holder[0] = dlg.showAndDecide();
            });
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
                "Host Key Mismatch"));
        }
    }
}
