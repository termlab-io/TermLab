package com.termlab.sysinfo.collect;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class LocalCommandRunner implements CommandRunner {

    @Override
    public @NotNull CommandResult run(@NotNull String command, @NotNull Duration timeout)
        throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/bin/sh", "-lc", command).start();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread outThread = copyThread(process.getInputStream(), stdout, "stdout");
        Thread errThread = copyThread(process.getErrorStream(), stderr, "stderr");
        outThread.start();
        errThread.start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out after " + timeout.toSeconds() + "s");
        }
        outThread.join(TimeUnit.SECONDS.toMillis(1));
        errThread.join(TimeUnit.SECONDS.toMillis(1));
        return new CommandResult(
            process.exitValue(),
            stdout.toString(StandardCharsets.UTF_8),
            stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private static @NotNull Thread copyThread(
        @NotNull java.io.InputStream input,
        @NotNull ByteArrayOutputStream output,
        @NotNull String streamName
    ) {
        Thread thread = new Thread(() -> {
            try (input) {
                input.transferTo(output);
            } catch (IOException ignored) {
            }
        }, "TermLabSysInfo-local-" + streamName);
        thread.setDaemon(true);
        return thread;
    }
}
