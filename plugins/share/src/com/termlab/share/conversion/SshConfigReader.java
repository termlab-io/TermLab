package com.termlab.share.conversion;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class SshConfigReader {

    private static final int MAX_INCLUDE_DEPTH = 5;

    private static final Set<String> KNOWN_DIRECTIVES = Set.of(
        "host", "hostname", "port", "user", "identityfile",
        "proxycommand", "proxyjump", "include", "match"
    );

    private SshConfigReader() {}

    public static Optional<SshConfigEntry> read(@NotNull Path configFile, @NotNull String alias) throws IOException {
        List<String> warnings = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        expand(configFile, lines, 0, warnings, new HashSet<>());

        boolean inTargetBlock = false;
        boolean foundTarget = false;
        String hostName = null;
        int port = 22;
        String user = null;
        String identityFile = null;
        String proxyCommand = null;
        String proxyJump = null;
        boolean sawMatchBlock = false;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            String[] parts = line.split("\\s+", 2);
            String directive = parts[0].toLowerCase(Locale.ROOT);
            String value = parts.length > 1 ? parts[1].trim() : "";

            if (directive.equals("match")) {
                sawMatchBlock = true;
                inTargetBlock = false;
                continue;
            }

            if (directive.equals("host")) {
                inTargetBlock = hostPatternMatchesExactly(value, alias);
                if (inTargetBlock) {
                    foundTarget = true;
                }
                continue;
            }

            if (!inTargetBlock) {
                continue;
            }

            switch (directive) {
                case "hostname" -> hostName = value;
                case "port" -> {
                    try {
                        port = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        warnings.add("Invalid port for alias '" + alias + "': " + value);
                    }
                }
                case "user" -> user = value;
                case "identityfile" -> identityFile = expandTilde(value);
                case "proxycommand" -> proxyCommand = value;
                case "proxyjump" -> proxyJump = value;
                default -> {
                    if (!KNOWN_DIRECTIVES.contains(directive)) {
                        warnings.add("Unsupported directive in alias '" + alias + "': " + parts[0]);
                    }
                }
            }
        }

        if (!foundTarget) {
            return Optional.empty();
        }
        if (sawMatchBlock) {
            warnings.add("File contains Match blocks which are not evaluated in v1");
        }

        return Optional.of(new SshConfigEntry(
            alias, hostName, port, user, identityFile, proxyCommand, proxyJump, List.copyOf(warnings)
        ));
    }

    private static boolean hostPatternMatchesExactly(String patterns, String alias) {
        for (String p : patterns.split("\\s+")) {
            if (p.isEmpty()) {
                continue;
            }
            if (p.contains("*") || p.contains("?")) {
                continue;
            }
            if (p.equals(alias)) {
                return true;
            }
        }
        return false;
    }

    private static void expand(
        Path file,
        List<String> out,
        int depth,
        List<String> warnings,
        Set<Path> visited
    ) throws IOException {
        if (depth >= MAX_INCLUDE_DEPTH) {
            warnings.add("Include depth exceeded at " + file);
            return;
        }
        if (!Files.isRegularFile(file)) {
            return;
        }
        Path normalized = file.toAbsolutePath().normalize();
        if (!visited.add(normalized)) {
            return;
        }
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("include ")) {
                String rest = trimmed.substring("include ".length()).trim();
                for (String target : rest.split("\\s+")) {
                    Path resolved = resolveInclude(file, target);
                    expand(resolved, out, depth + 1, warnings, visited);
                }
            } else {
                out.add(line);
            }
        }
    }

    private static Path resolveInclude(Path parentFile, String target) {
        String expanded = expandTilde(target);
        Path path = Paths.get(expanded);
        if (path.isAbsolute()) {
            return path;
        }
        Path parentDir = parentFile.toAbsolutePath().getParent();
        if (parentDir == null) {
            return path.toAbsolutePath();
        }
        return parentDir.resolve(path).normalize();
    }

    private static String expandTilde(String path) {
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
