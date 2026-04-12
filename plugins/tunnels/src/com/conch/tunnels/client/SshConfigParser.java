package com.conch.tunnels.client;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Lightweight parser for {@code ~/.ssh/config} that extracts
 * {@code Host} aliases for the tunnel edit dialog's host picker.
 * Does NOT parse connection details — MINA handles that at connect
 * time via {@code HostConfigEntryResolver}.
 */
public final class SshConfigParser {

    private SshConfigParser() {}

    public static @NotNull List<String> parseHostAliases() {
        return parseHostAliases(
            Paths.get(System.getProperty("user.home"), ".ssh", "config"));
    }

    public static @NotNull List<String> parseHostAliases(@NotNull Path configPath) {
        if (!Files.isRegularFile(configPath)) return List.of();
        List<String> lines;
        try {
            lines = Files.readAllLines(configPath);
        } catch (IOException e) {
            return List.of();
        }

        TreeSet<String> aliases = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.toLowerCase().startsWith("host ")) continue;
            if (trimmed.toLowerCase().startsWith("hostname ")) continue;

            String rest = trimmed.substring(5).trim();
            for (String token : rest.split("\\s+")) {
                token = token.trim();
                if (token.isEmpty()) continue;
                if (token.startsWith("!")) continue;
                if (token.contains("*") || token.contains("?")) continue;
                aliases.add(token);
            }
        }
        return new ArrayList<>(aliases);
    }
}
