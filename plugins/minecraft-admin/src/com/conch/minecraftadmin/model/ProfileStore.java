package com.conch.minecraftadmin.model;

import com.conch.minecraftadmin.persistence.McPaths;
import com.conch.minecraftadmin.persistence.ServersFile;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Application-level store for Minecraft server profiles. Wraps
 * {@link ServersFile} with an in-memory list, a listener list, and
 * the usual CRUD helpers the tool window and edit dialog need.
 *
 * <p>Thread-safety: mutations happen on the EDT (from dialogs and
 * button clicks). The snapshot accessor {@link #getProfiles} is safe
 * to call off the EDT because it returns an unmodifiable copy.
 */
@Service(Service.Level.APP)
public final class ProfileStore {

    private static final Logger LOG = Logger.getInstance(ProfileStore.class);

    public interface Listener {
        void onProfilesChanged(@NotNull List<ServerProfile> profiles);
    }

    private final Path file;
    private final List<ServerProfile> profiles = new ArrayList<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private boolean loaded;

    /** Production constructor used by the IntelliJ service framework. */
    public ProfileStore() {
        this(McPaths.serversFile());
    }

    /** Test constructor — points at a {@code @TempDir}. */
    ProfileStore(@NotNull Path file) {
        this.file = file;
    }

    public static @NotNull ProfileStore getInstance() {
        return ApplicationManager.getApplication().getService(ProfileStore.class);
    }

    public synchronized @NotNull List<ServerProfile> getProfiles() {
        ensureLoaded();
        return List.copyOf(profiles);
    }

    public synchronized @NotNull Optional<ServerProfile> find(@NotNull UUID id) {
        ensureLoaded();
        return profiles.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public synchronized void add(@NotNull ServerProfile profile) {
        ensureLoaded();
        profiles.add(profile);
        persistAndFire();
    }

    public synchronized void update(@NotNull ServerProfile profile) {
        ensureLoaded();
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id().equals(profile.id())) {
                profiles.set(i, profile);
                persistAndFire();
                return;
            }
        }
        throw new IllegalArgumentException("no profile with id " + profile.id());
    }

    public synchronized void remove(@NotNull UUID id) {
        ensureLoaded();
        profiles.removeIf(p -> p.id().equals(id));
        persistAndFire();
    }

    public void addListener(@NotNull Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull Listener listener) {
        listeners.remove(listener);
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            profiles.addAll(ServersFile.load(file));
        } catch (IOException e) {
            LOG.warn("Conch Minecraft: could not load profiles from " + file, e);
        }
    }

    private void persistAndFire() {
        try {
            ServersFile.save(file, profiles);
        } catch (IOException e) {
            LOG.warn("Conch Minecraft: could not save profiles to " + file, e);
        }
        List<ServerProfile> snapshot = List.copyOf(profiles);
        for (Listener l : listeners) {
            try {
                l.onProfilesChanged(snapshot);
            } catch (Exception e) {
                LOG.warn("Conch Minecraft: profile listener threw", e);
            }
        }
    }
}
