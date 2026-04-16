package com.termlab.runner.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Built-in mapping from file extension to default interpreter.
 */
public final class InterpreterRegistry {

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
        Map.entry("py", "python3"),
        Map.entry("sh", "bash"),
        Map.entry("js", "node"),
        Map.entry("rb", "ruby"),
        Map.entry("pl", "perl"),
        Map.entry("go", "go run"),
        Map.entry("java", "java"),
        Map.entry("lua", "lua"),
        Map.entry("php", "php")
    );

    private InterpreterRegistry() {}

    public static @Nullable String interpreterFor(@NotNull String extension) {
        return DEFAULTS.get(extension.toLowerCase());
    }

    public static @Nullable String extractExtension(@NotNull String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot + 1);
    }

    public static @Nullable String interpreterForFile(@NotNull String filename) {
        String extension = extractExtension(filename);
        if (extension == null) {
            return null;
        }
        return interpreterFor(extension);
    }
}
