package com.termlab.vault.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Vault plugin configuration with direct JSON persistence. Follows the same
 * pattern as {@code TermLabTerminalConfig} in the TermLab core module — bypasses
 * {@code PersistentStateComponent} because it doesn't reliably flush in the
 * Bazel dev setup.
 *
 * <p>Stored at {@code ~/.config/termlab/vault-settings.json}. Separate from the
 * vault file itself (never touch each other), and separate from the terminal
 * settings so the vault plugin is self-contained.
 */
public final class TermLabVaultConfig {

    private static final Logger LOG = Logger.getInstance(TermLabVaultConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE = Paths.get(
        System.getProperty("user.home"), ".config", "termlab", "vault-settings.json");

    private static TermLabVaultConfig INSTANCE;

    private State state;

    private TermLabVaultConfig() {
        this.state = load();
    }

    public static synchronized TermLabVaultConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new TermLabVaultConfig();
        }
        return INSTANCE;
    }

    public @NotNull State getState() {
        return state;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            String json = GSON.toJson(state);
            Path tmp = CONFIG_FILE.resolveSibling(CONFIG_FILE.getFileName() + ".tmp");
            Files.writeString(tmp, json,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, CONFIG_FILE,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Vault settings saved to " + CONFIG_FILE);
        } catch (IOException e) {
            LOG.error("Failed to save vault settings", e);
        }
    }

    private static @NotNull State load() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                String json = Files.readString(CONFIG_FILE);
                State loaded = GSON.fromJson(json, State.class);
                if (loaded != null) {
                    LOG.info("Vault settings loaded from " + CONFIG_FILE);
                    return loaded;
                }
            } catch (Exception e) {
                LOG.error("Failed to load vault settings", e);
            }
        }
        return new State();
    }

    /**
     * Mutable state object. Public fields so Gson can read/write directly
     * without reflection into private members (same style as
     * {@code TermLabTerminalConfig.State}).
     */
    public static final class State {
        /**
         * Absolute path to the vault file. When null or blank, the vault
         * plugin uses the default location ({@code ~/.config/termlab/vault.enc}).
         */
        public @Nullable String vaultFilePath = null;
    }
}
