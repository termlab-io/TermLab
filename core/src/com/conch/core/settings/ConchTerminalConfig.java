package com.conch.core.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Terminal configuration with direct JSON persistence.
 * Bypasses IntelliJ's PersistentStateComponent framework which doesn't
 * reliably flush state in our Bazel dev setup.
 */
public final class ConchTerminalConfig {
    private static final Logger LOG = Logger.getInstance(ConchTerminalConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE = Path.of(
        System.getProperty("user.home"), ".config", "conch", "terminal-settings.json"
    );

    private static ConchTerminalConfig INSTANCE;

    private State state;

    private ConchTerminalConfig() {
        state = load();
    }

    public static synchronized ConchTerminalConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ConchTerminalConfig();
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
            Files.writeString(tmp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, CONFIG_FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Terminal settings saved to " + CONFIG_FILE);
        } catch (IOException e) {
            LOG.error("Failed to save terminal settings", e);
        }
    }

    private static @NotNull State load() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                String json = Files.readString(CONFIG_FILE);
                State loaded = GSON.fromJson(json, State.class);
                if (loaded != null) {
                    LOG.info("Terminal settings loaded from " + CONFIG_FILE);
                    return loaded;
                }
            } catch (Exception e) {
                LOG.error("Failed to load terminal settings", e);
            }
        }
        return new State();
    }

    public static class State {
        // Font
        public String fontFamily = "";
        public int fontSize = 14;
        public float lineSpacing = 1.0f;
        public float characterSpacing = 0.0f;

        // Colors (hex)
        public String foreground = "#BBBBBB";
        public String background = "#2B2B2B";
        public String selectionForeground = "#FFFFFF";
        public String selectionBackground = "#214283";

        // Cursor
        public String cursorShape = "BLOCK";

        // Layout
        public boolean projectViewVisible = false;

        // Behavior
        public int scrollbackLines = 10000;
        public boolean copyOnSelect = false;
        public boolean audibleBell = false;
        public boolean enableMouseReporting = true;
    }
}
