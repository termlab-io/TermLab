package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.client.AmpClient;
import com.conch.minecraftadmin.client.RconClient;
import com.conch.minecraftadmin.client.ServerPoller;
import com.conch.minecraftadmin.client.StateListener;
import com.conch.minecraftadmin.credentials.McCredential;
import com.conch.minecraftadmin.credentials.McCredentialResolver;
import com.conch.minecraftadmin.model.ServerProfile;
import com.conch.minecraftadmin.model.ServerState;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wires one {@link ServerPoller} to the panels for one profile. */
public final class ProfileController implements AutoCloseable {

    private static final Logger LOG = Logger.getInstance(ProfileController.class);

    private final ServerProfile profile;
    private final StatusStripPanel statusStrip;
    private final LifecycleButtons lifecycleButtons;
    private final PlayersPanel playersPanel;
    private final ConsolePanel consolePanel;
    private final ServerPoller poller;

    public ProfileController(
        @NotNull ServerProfile profile,
        @NotNull StatusStripPanel statusStrip,
        @NotNull LifecycleButtons lifecycleButtons,
        @NotNull PlayersPanel playersPanel,
        @NotNull ConsolePanel consolePanel
    ) {
        this.profile = profile;
        this.statusStrip = statusStrip;
        this.lifecycleButtons = lifecycleButtons;
        this.playersPanel = playersPanel;
        this.consolePanel = consolePanel;

        McCredentialResolver resolver = new McCredentialResolver();
        AmpClient amp = new AmpClient(baseUrl -> {
            McCredential cred = resolver.resolve(profile.ampCredentialId(), profile.ampUsername());
            if (cred == null) throw new IllegalStateException("AMP credential not found for " + profile.label());
            return new AmpClient.LoginPair(cred.username(), cred.password());
        });
        RconClient rconClient = new RconClient();

        StateListener listener = new StateListener() {
            @Override public void onStateUpdate(@NotNull ServerState state) {
                statusStrip.update(state);
                lifecycleButtons.update(state);
                playersPanel.update(state);
            }
            @Override public void onConsoleLines(@NotNull List<String> lines) {
                consolePanel.appendLines(lines);
            }
            @Override public void onCrashDetected(@NotNull ServerState state) {
                Notifications.Bus.notify(new Notification(
                    "Conch Minecraft",
                    "Minecraft server '" + profile.label() + "' crashed",
                    state.ampError().orElse("Check AMP for details"),
                    NotificationType.ERROR));
            }
        };

        this.poller = new ServerPoller(
            profile, amp, rconClient, listener,
            AppExecutorUtil.getAppScheduledExecutorService(),
            r -> ApplicationManager.getApplication().invokeLater(r, ModalityState.any()),
            () -> {
                UUID rconId = profile.rconCredentialId();
                if (rconId == null) {
                    return new char[0];  // passwordless RCON — empty auth body
                }
                McCredential cred = resolver.resolve(rconId, "");
                if (cred == null) throw new IllegalStateException("RCON credential not found for " + profile.label());
                return cred.password();
            });
    }

    public void start() {
        poller.start();
        // Render an immediate empty snapshot so the UI isn't blank while the first tick runs.
        ServerState initial = ServerState.unknown(Instant.now());
        statusStrip.update(initial);
        lifecycleButtons.update(initial);
        playersPanel.update(initial);
    }

    public @NotNull ServerProfile profile() { return profile; }

    public @NotNull ServerPoller poller() { return poller; }

    @Override
    public void close() {
        poller.stop();
    }
}
