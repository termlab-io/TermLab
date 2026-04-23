package com.termlab.sysinfo.collect;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Duration;

public interface CommandRunner {
    @NotNull CommandResult run(@NotNull String command, @NotNull Duration timeout) throws IOException, InterruptedException;
}
