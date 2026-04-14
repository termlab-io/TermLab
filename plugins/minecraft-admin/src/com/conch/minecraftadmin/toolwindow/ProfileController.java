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
    private final McCredentialResolver resolver;
    private final ServerPoller poller;

    public ProfileController(
        @NotNull ServerProfile profile,
        @NotNull StatusStripPanel statusStrip,
        @NotNull LifecycleButtons lifecycleButtons,
        @NotNull PlayersPanel playersPanel,
        @NotNull ConsolePanel consolePanel
    ) {
        LOG.info("Conch Minecraft: ProfileController init profile=" + profile.label());
        this.profile = profile;
        this.statusStrip = statusStrip;
        this.lifecycleButtons = lifecycleButtons;
        this.playersPanel = playersPanel;
        this.consolePanel = consolePanel;

        this.resolver = new McCredentialResolver();
        AmpClient amp = new AmpClient(baseUrl -> {
            McCredential cred = resolver.resolve(profile.ampCredentialId(), profile.ampUsername());
            if (cred == null) {
                LOG.warn("Conch Minecraft: AMP credential " + profile.ampCredentialId()
                    + " not found in vault for profile=" + profile.label()
                    + " — vault may be locked; click Refresh in the Minecraft Admin tool window to unlock it");
                throw new IllegalStateException("AMP credential not found for " + profile.label());
            }
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
                    LOG.info("Conch Minecraft: RCON credential is null for profile=" + profile.label()
                        + " — using empty passwordless auth");
                    return new char[0];  // passwordless RCON — empty auth body
                }
                McCredential cred = resolver.resolve(rconId, "");
                if (cred == null) {
                    LOG.warn("Conch Minecraft: RCON credential " + rconId
                        + " not found in vault for profile=" + profile.label()
                        + " — vault may be locked; click Refresh in the Minecraft Admin tool window to unlock it");
                    throw new IllegalStateException("RCON credential not found for " + profile.label());
                }
                return cred.password();
            });
    }

    public void start() {
        LOG.info("Conch Minecraft: ProfileController starting poller for profile=" + profile.label());
        boolean vaultAvailable = resolver.ensureAnyProviderAvailable();
        LOG.info("Conch Minecraft: vault available after ensureAnyProviderAvailable=" + vaultAvailable);
        poller.start();
        // Render an immediate empty snapshot so the UI isn't blank while the first tick runs.
        ServerState initial = ServerState.unknown(Instant.now());
        statusStrip.update(initial);
        lifecycleButtons.update(initial);
        playersPanel.update(initial);
    }

    /**
     * User-initiated refresh: re-attempt to unlock the vault if it's
     * locked, then invalidate AMP/RCON sessions and fire an immediate
     * poll tick via {@link ServerPoller#reconnect()}.
     *
     * <p>Called from the tool window's Refresh button — must run on the
     * EDT because {@link McCredentialResolver#ensureAnyProviderAvailable()}
     * pops a modal dialog.
     */
    public void refresh() {
        LOG.info("Conch Minecraft: ProfileController refresh requested for profile=" + profile.label());
        boolean vaultAvailable = resolver.ensureAnyProviderAvailable();
        LOG.info("Conch Minecraft: refresh vault available=" + vaultAvailable);
        poller.reconnect();
    }

    public @NotNull ServerProfile profile() { return profile; }

    public @NotNull ServerPoller poller() { return poller; }

    @Override
    public void close() {
        LOG.info("Conch Minecraft: ProfileController closing for profile=" + profile.label());
        poller.stop();
    }
}
