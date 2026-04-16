package com.termlab.runner.execution;

import com.termlab.runner.config.RunConfig;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds local and remote execution commands from a run config.
 */
public final class CommandBuilder {

    private CommandBuilder() {}

    public static @NotNull List<String> buildLocalCommand(
        @NotNull RunConfig config,
        @NotNull String scriptPath
    ) {
        List<String> command = new ArrayList<>();
        command.add(config.interpreter());
        command.addAll(config.args());
        command.add(scriptPath);
        command.addAll(config.scriptArgs());
        return command;
    }

    public static @NotNull String buildRemoteCommand(
        @NotNull RunConfig config,
        @NotNull String scriptPath
    ) {
        StringBuilder command = new StringBuilder();

        if (config.workingDirectory() != null) {
            command.append("cd ")
                .append(shellQuoteIfNeeded(config.workingDirectory()))
                .append(" && ");
        }

        if (!config.envVars().isEmpty()) {
            TreeMap<String, String> sorted = new TreeMap<>(config.envVars());
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                command.append(entry.getKey())
                    .append("=")
                    .append(shellQuoteIfNeeded(entry.getValue()))
                    .append(" ");
            }
        }

        command.append(config.interpreter());
        for (String arg : config.args()) {
            command.append(" ").append(shellQuoteIfNeeded(arg));
        }

        command.append(" ").append(shellQuoteIfNeeded(scriptPath));

        for (String arg : config.scriptArgs()) {
            command.append(" ").append(shellQuoteIfNeeded(arg));
        }

        return command.toString();
    }

    public static @NotNull String shellQuoteIfNeeded(@NotNull String value) {
        if (value.isEmpty()) {
            return "''";
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '"' || c == '\'' || c == '\\' ||
                c == '$' || c == '`' || c == '(' || c == ')' || c == '&' ||
                c == '|' || c == ';' || c == '<' || c == '>' || c == '*' ||
                c == '?' || c == '[' || c == ']' || c == '{' || c == '}' ||
                c == '!' || c == '#' || c == '~') {
                return "'" + value.replace("'", "'\\''") + "'";
            }
        }
        return value;
    }
}
