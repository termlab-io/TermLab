package com.termlab.runner.execution;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;

/**
 * Handle to a running script execution.
 */
public interface ScriptExecution {

    @NotNull InputStream getOutputStream();

    void sendInterrupt();

    void kill();

    @Nullable Integer getExitCode();

    void addTerminationListener(@NotNull Runnable listener);

    boolean isRunning();
}
