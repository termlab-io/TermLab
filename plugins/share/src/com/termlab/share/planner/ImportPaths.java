package com.termlab.share.planner;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public record ImportPaths(
    @NotNull Path hostsFile,
    @NotNull Path tunnelsFile,
    @NotNull Path vaultFile,
    @NotNull Path importedKeysDir
) {}
