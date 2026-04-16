package com.termlab.runner.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A named script execution configuration.
 */
public record RunConfig(
    @NotNull UUID id,
    @NotNull String name,
    @Nullable UUID hostId,
    @NotNull String interpreter,
    @NotNull List<String> args,
    @Nullable String workingDirectory,
    @NotNull Map<String, String> envVars,
    @NotNull List<String> scriptArgs
) {

    public RunConfig {
        args = List.copyOf(args);
        envVars = Map.copyOf(envVars);
        scriptArgs = List.copyOf(scriptArgs);
    }

    public boolean isLocal() {
        return hostId == null;
    }

    public @NotNull RunConfig withName(@NotNull String newName) {
        return new RunConfig(
            id,
            newName,
            hostId,
            interpreter,
            args,
            workingDirectory,
            envVars,
            scriptArgs
        );
    }

    public @NotNull RunConfig withEdited(
        @NotNull String newName,
        @Nullable UUID newHostId,
        @NotNull String newInterpreter,
        @NotNull List<String> newArgs,
        @Nullable String newWorkingDirectory,
        @NotNull Map<String, String> newEnvVars,
        @NotNull List<String> newScriptArgs
    ) {
        return new RunConfig(
            id,
            newName,
            newHostId,
            newInterpreter,
            newArgs,
            newWorkingDirectory,
            newEnvVars,
            newScriptArgs
        );
    }

    public static @NotNull RunConfig create(
        @NotNull String name,
        @Nullable UUID hostId,
        @NotNull String interpreter,
        @NotNull List<String> args,
        @Nullable String workingDirectory,
        @NotNull Map<String, String> envVars,
        @NotNull List<String> scriptArgs
    ) {
        return new RunConfig(
            UUID.randomUUID(),
            name,
            hostId,
            interpreter,
            args,
            workingDirectory,
            envVars,
            scriptArgs
        );
    }
}
