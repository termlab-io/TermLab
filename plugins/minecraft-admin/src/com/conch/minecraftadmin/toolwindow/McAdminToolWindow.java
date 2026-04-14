package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.client.AmpClient;
import com.conch.minecraftadmin.client.ServerPoller;
import com.conch.minecraftadmin.model.ProfileStore;
import com.conch.minecraftadmin.model.ServerProfile;
import com.conch.minecraftadmin.ui.ServerEditDialog;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.ui.Splitter;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Root panel of the Minecraft Admin tool window. Holds one
 * {@link ProfileController} for the currently-selected profile and
 * rewires it when the user switches profiles.
 *
 * <p>Layout optimized for a bottom-anchored tool window:
 * <ul>
 *   <li>NORTH: a single-row toolbar with the server switcher, status
 *       strip, and lifecycle buttons laid out left-to-right.</li>
 *   <li>CENTER: a horizontal {@link Splitter} with the players table on
 *       the left (30%) and the console panel on the right (70%).</li>
 * </ul>
 * No special docked/undocked code paths — IntelliJ's ToolWindow framework
 * handles mode transitions, and BorderLayout + Splitter reflow naturally
 * when the user undocks or resizes the window.
 */
public final class McAdminToolWindow extends JPanel {

    private static final Logger LOG = Logger.getInstance(McAdminToolWindow.class);

    private static final float DEFAULT_SPLIT_PROPORTION = 0.30f;

    private final Project project;
    private final ProfileStore profileStore;
    private final StatusStripPanel statusStrip = new StatusStripPanel();
    private final LifecycleButtons lifecycleButtons;
    private final PlayersPanel playersPanel;
    private final ConsolePanel consolePanel;
    private final ServerSwitcher switcher;

    private @Nullable ProfileController current;

    public McAdminToolWindow(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.profileStore = ProfileStore.getInstance();
        LOG.info("Conch Minecraft: tool window initializing project=" + project.getName());

        this.lifecycleButtons = new LifecycleButtons(
            () -> invokeOnPoller(ServerPoller::sendStart),
            () -> invokeOnPoller(ServerPoller::sendStop),
            () -> invokeOnPoller(ServerPoller::sendRestart),
            () -> invokeOnPoller(ServerPoller::sendBackup));

        this.playersPanel = new PlayersPanel(
            name -> sendRcon("kick " + name),
            name -> sendRcon("ban " + name),
            name -> sendRcon("op " + name));

        this.consolePanel = new ConsolePanel(
            cmd -> sendRconSync(cmd),
            msg -> sendRcon("say " + msg));

        // Top toolbar — one horizontal row: switcher + status strip + lifecycle buttons
        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));
        this.switcher = new ServerSwitcher(
            this::switchTo,
            this::addProfile,
            this::editProfile,
            this::duplicateProfile,
            this::deleteProfile
        );
        toolbar.add(switcher);
        toolbar.add(javax.swing.Box.createHorizontalStrut(12));
        toolbar.add(statusStrip);
        toolbar.add(javax.swing.Box.createHorizontalGlue());
        toolbar.add(lifecycleButtons);
        toolbar.add(javax.swing.Box.createHorizontalStrut(6));
        javax.swing.JButton refreshButton = new javax.swing.JButton("Refresh");
        refreshButton.setToolTipText("Reconnect to AMP and RCON for the current server");
        refreshButton.addActionListener(e -> {
            if (current != null) current.poller().reconnect();
        });
        toolbar.add(refreshButton);
        add(toolbar, BorderLayout.NORTH);

        // Main area — horizontal splitter: players on the left, console on the right
        Splitter mainSplit = new Splitter(false, DEFAULT_SPLIT_PROPORTION);
        mainSplit.setFirstComponent(playersPanel);
        mainSplit.setSecondComponent(consolePanel);
        add(mainSplit, BorderLayout.CENTER);

        reloadProfiles();
        profileStore.addListener(profiles -> reloadProfiles());
    }

    private void reloadProfiles() {
        List<ServerProfile> profiles = profileStore.getProfiles();
        LOG.info("Conch Minecraft: reloadProfiles profiles=" + profiles.size()
            + " currentSelected=" + (current == null ? "none" : current.profile().label()));
        ServerProfile toSelect = null;
        if (current != null) {
            UUID currentId = current.profile().id();
            toSelect = profiles.stream()
                .filter(p -> p.id().equals(currentId))
                .findFirst()
                .orElse(null);
        }
        if (toSelect == null && !profiles.isEmpty()) {
            toSelect = profiles.get(0);
        }
        switcher.setProfiles(profiles, toSelect);
        if (toSelect != null) {
            switchTo(toSelect);
        } else if (current != null) {
            // All profiles deleted — shut down the poller and clear UI state.
            current.close();
            current = null;
            com.conch.minecraftadmin.model.ServerState empty =
                com.conch.minecraftadmin.model.ServerState.unknown(java.time.Instant.now());
            statusStrip.update(empty);
            lifecycleButtons.update(empty);
            playersPanel.update(empty);
        }
    }

    private void switchTo(ServerProfile profile) {
        if (current != null) {
            if (current.profile().id().equals(profile.id())) return;
            current.close();
        }
        LOG.info("Conch Minecraft: switching to profile=" + profile.label() + " id=" + profile.id());
        current = new ProfileController(profile, statusStrip, lifecycleButtons, playersPanel, consolePanel);
        current.start();
    }

    private void addProfile() {
        LOG.info("Conch Minecraft: addProfile clicked for profile=new");
        Optional<ServerProfile> created = new ServerEditDialog(project, null).showAndGetResult();
        created.ifPresent(profileStore::add);
    }

    private void editProfile(ServerProfile existing) {
        LOG.info("Conch Minecraft: editProfile clicked for profile=" + existing.label());
        Optional<ServerProfile> edited = new ServerEditDialog(project, existing).showAndGetResult();
        edited.ifPresent(profileStore::update);
    }

    private void duplicateProfile(ServerProfile source) {
        LOG.info("Conch Minecraft: duplicateProfile clicked for profile=" + source.label());
        Optional<ServerProfile> created =
            ServerEditDialog.forCopyOf(project, source).showAndGetResult();
        created.ifPresent(profileStore::add);
    }

    private void deleteProfile(ServerProfile profile) {
        LOG.info("Conch Minecraft: deleteProfile clicked for profile=" + profile.label());
        int choice = Messages.showYesNoDialog(
            project,
            "Delete server profile '" + profile.label() + "'? This cannot be undone.",
            "Delete Minecraft Server",
            "Delete", "Cancel",
            Messages.getWarningIcon());
        if (choice != Messages.YES) return;
        profileStore.remove(profile.id());
    }

    private void invokeOnPoller(@NotNull java.util.function.Consumer<ServerPoller> action) {
        if (current == null) return;
        try {
            action.accept(current.poller());
        } catch (Exception e) {
            Messages.showErrorDialog(project, e.getMessage(), "Minecraft Admin");
        }
    }

    private void sendRcon(@NotNull String command) {
        if (current == null) return;
        current.poller().sendCommand(command).whenComplete((reply, err) -> {
            if (err != null) consolePanel.appendLines(List.of("[error] " + err.getMessage()));
            else consolePanel.appendLines(List.of("> " + command, reply));
        });
    }

    private String sendRconSync(@NotNull String command) {
        if (current == null) return "(no server selected)";
        try {
            return current.poller().sendCommand(command).get();
        } catch (Exception e) {
            return "[error] " + e.getMessage();
        }
    }
}
