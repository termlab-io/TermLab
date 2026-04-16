package com.termlab.runner.execution;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local ProcessBuilder-backed execution.
 */
public final class LocalExecution implements ScriptExecution {

    private static final Logger LOG = Logger.getInstance(LocalExecution.class);

    private final Process process;
    private final CopyOnWriteArrayList<Runnable> terminationListeners = new CopyOnWriteArrayList<>();

    private LocalExecution(@NotNull Process process) {
        this.process = process;
        Thread waiter = new Thread(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (Runnable listener : terminationListeners) {
                listener.run();
            }
        }, "TermLabRunner-local-waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    public static @NotNull LocalExecution start(
        @NotNull List<String> command,
        @Nullable String workingDirectory,
        @NotNull Map<String, String> envVars,
        @NotNull String scriptPath
    ) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        if (workingDirectory != null) {
            processBuilder.directory(new File(workingDirectory));
        } else {
            File parent = new File(scriptPath).getParentFile();
            if (parent != null) {
                processBuilder.directory(parent);
            }
        }

        if (!envVars.isEmpty()) {
            processBuilder.environment().putAll(envVars);
        }

        LOG.info("TermLab Runner: starting local execution: " + command);
        return new LocalExecution(processBuilder.start());
    }

    @Override
    public @NotNull InputStream getOutputStream() {
        return process.getInputStream();
    }

    @Override
    public void sendInterrupt() {
        LOG.info("TermLab Runner: sending interrupt to local process");
        process.destroy();
    }

    @Override
    public void kill() {
        LOG.info("TermLab Runner: force-killing local process");
        process.destroyForcibly();
    }

    @Override
    public @Nullable Integer getExitCode() {
        if (process.isAlive()) {
            return null;
        }
        return process.exitValue();
    }

    @Override
    public void addTerminationListener(@NotNull Runnable listener) {
        terminationListeners.add(listener);
        if (!process.isAlive()) {
            listener.run();
        }
    }

    @Override
    public boolean isRunning() {
        return process.isAlive();
    }
}
