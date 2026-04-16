package com.termlab.runner.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Canonical paths for runner plugin state on disk.
 */
public final class RunnerPaths {

    private RunnerPaths() {}

    public static Path configsFile() {
        return Paths.get(System.getProperty("user.home"), ".config", "termlab", "run-configs.json");
    }

    public static Path bindingsFile() {
        return Paths.get(System.getProperty("user.home"), ".config", "termlab", "run-bindings.json");
    }
}
