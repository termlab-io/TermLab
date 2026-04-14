package com.conch.minecraftadmin.persistence;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Canonical on-disk paths for the Minecraft Admin plugin's state.
 * Same {@code ~/.config/conch/} convention the SSH plugin uses.
 */
public final class McPaths {

    private McPaths() {}

    /** Plaintext JSON file holding the list of saved server profiles. */
    public static Path serversFile() {
        return Paths.get(System.getProperty("user.home"), ".config", "conch", "minecraft-servers.json");
    }
}
