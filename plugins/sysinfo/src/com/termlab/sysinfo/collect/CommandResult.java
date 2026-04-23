package com.termlab.sysinfo.collect;

import org.jetbrains.annotations.NotNull;

public record CommandResult(
    int exitCode,
    @NotNull String stdout,
    @NotNull String stderr
) {
}
