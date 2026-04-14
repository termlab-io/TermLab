package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.ServerProfile;
import com.conch.minecraftadmin.model.ServerState;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Drives one profile's 5-second poll loop. Each tick fires an AMP
 * status call and an RCON batch (list + tps) in parallel, merges the
 * results into a {@link ServerState}, and hands the snapshot to a
 * {@link StateListener}. The console panel additionally calls
 * {@link #pollConsole} on its own cadence (1s when focused).
 *
 * <p>Thread-safety: public methods are safe to call from any thread.
 * The poll loop runs on the application scheduled executor; listener
 * callbacks are dispatched via a caller-supplied {@link Consumer} so
 * UI code can marshal back to the EDT.
 */
public final class ServerPoller implements AutoCloseable {

    private static final Logger LOG = Logger.getInstance(ServerPoller.class);

    public static final Duration TICK = Duration.ofSeconds(5);

    private final ServerProfile profile;
    private final AmpClient ampClient;
    private final RconClient rconClient;
    private final StateListener listener;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService commandExecutor;
    private final Consumer<Runnable> uiDispatcher;
    private final Supplier<char[]> rconPasswordSupplier;
    private final CrashDetector crashDetector = new CrashDetector();

    private volatile AmpSession ampSession;
    private volatile RconSession rconSession;
    private final AtomicReference<ScheduledFuture<?>> scheduledTask = new AtomicReference<>();
    private volatile boolean stopped = false;

    public ServerPoller(
        @NotNull ServerProfile profile,
        @NotNull AmpClient ampClient,
        @NotNull RconClient rconClient,
        @NotNull StateListener listener,
        @NotNull ScheduledExecutorService scheduler,
        @NotNull Consumer<Runnable> uiDispatcher,
        @NotNull Supplier<char[]> rconPasswordSupplier
    ) {
        this.profile = profile;
        this.ampClient = ampClient;
        this.rconClient = rconClient;
        this.listener = listener;
        this.scheduler = scheduler;
        this.commandExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "conch-mc-rcon-" + profile.label());
            t.setDaemon(true);
            return t;
        });
        this.uiDispatcher = uiDispatcher;
        this.rconPasswordSupplier = rconPasswordSupplier;
    }

    public void start() {
        if (scheduledTask.get() != null) return;
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(
            this::safeTick, 0, TICK.toMillis(), TimeUnit.MILLISECONDS);
        scheduledTask.set(task);
    }

    @Override
    public void close() {
        stop();
    }

    public void stop() {
        stopped = true;
        ScheduledFuture<?> task = scheduledTask.getAndSet(null);
        if (task != null) task.cancel(false);
        commandExecutor.shutdownNow();
        RconSession r = rconSession;
        if (r != null) {
            rconClient.close(r);
            rconSession = null;
        }
        ampSession = null;
    }

    /** Drive one tick synchronously. Exposed for tests. */
    public void tickOnce() {
        safeTick();
    }

    /**
     * Force an immediate reconnect: close the RCON socket, drop the AMP
     * session token, and reschedule the poll loop so the next tick fires
     * right away. Safe to call from the EDT — session cleanup runs on the
     * command executor, rescheduling runs on the caller thread.
     */
    public void reconnect() {
        if (stopped) return;
        commandExecutor.submit(() -> {
            closeRcon();
            ampSession = null;
        });
        ScheduledFuture<?> old = scheduledTask.getAndSet(null);
        if (old != null) old.cancel(false);
        ScheduledFuture<?> next = scheduler.scheduleWithFixedDelay(
            this::safeTick, 0, TICK.toMillis(), TimeUnit.MILLISECONDS);
        scheduledTask.set(next);
    }

    /** Record that the user just asked to stop the server; suppresses crash balloon. */
    public void recordUserStop() {
        crashDetector.recordUserStop(Instant.now());
    }

    /** Send an RCON command off the EDT. */
    public @NotNull CompletableFuture<String> sendCommand(@NotNull String cmd) {
        CompletableFuture<String> future = new CompletableFuture<>();
        commandExecutor.submit(() -> {
            try {
                RconSession session = ensureRcon();
                future.complete(rconClient.command(session, cmd));
            } catch (IOException e) {
                closeRcon();
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Request console updates (called by ConsolePanel at its own cadence). */
    public @NotNull CompletableFuture<ConsoleUpdate> pollConsole() {
        CompletableFuture<ConsoleUpdate> future = new CompletableFuture<>();
        commandExecutor.submit(() -> {
            try {
                AmpSession session = ensureAmp();
                future.complete(ampClient.getConsoleUpdates(session, profile.ampInstanceName()));
            } catch (IOException e) {
                ampSession = null;
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private void safeTick() {
        if (stopped) return;
        try {
            tickImpl();
        } catch (Throwable t) {
            LOG.warn("Conch Minecraft: unexpected poll-loop failure", t);
        }
    }

    private void tickImpl() {
        Instant sampledAt = Instant.now();

        AmpTickResult ampResult;
        try {
            AmpSession session = ensureAmp();
            InstanceStatus status = ampClient.getInstanceStatus(session, profile.ampInstanceName());
            ampResult = AmpTickResult.ok(status);
        } catch (IOException e) {
            ampSession = null;
            ampResult = AmpTickResult.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }

        RconTickResult rconResult;
        try {
            RconSession session = ensureRcon();
            String listReply = rconClient.command(session, "list");
            String tpsReply = rconClient.command(session, "tps");
            PaperListReplyParser.Result parsed = PaperListReplyParser.parse(listReply);
            double tps = PaperTpsReplyParser.parseMostRecent(tpsReply);
            rconResult = RconTickResult.ok(parsed.players(), parsed.online(), parsed.max(), tps);
        } catch (IOException e) {
            closeRcon();
            rconResult = RconTickResult.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }

        ServerState state = ServerStateMerger.merge(ampResult, rconResult, sampledAt);
        boolean crashFired = crashDetector.observe(state.status(), sampledAt);
        uiDispatcher.accept(() -> {
            listener.onStateUpdate(state);
            if (crashFired) listener.onCrashDetected(state);
        });
    }

    private AmpSession ensureAmp() throws IOException {
        AmpSession current = ampSession;
        if (current != null) return current;
        AmpSession fresh = ampClient.login(profile.ampUrl());
        ampSession = fresh;
        return fresh;
    }

    private RconSession ensureRcon() throws IOException {
        RconSession current = rconSession;
        if (current != null && !current.isClosed()) return current;
        char[] password = resolveRconPassword();
        RconSession fresh = rconClient.connect(profile.rconHost(), profile.rconPort(), password);
        rconSession = fresh;
        return fresh;
    }

    private char[] resolveRconPassword() {
        char[] pw = rconPasswordSupplier.get();
        if (pw == null) throw new IllegalStateException(
            "RCON password supplier returned null for " + profile.label());
        return pw;
    }

    private void closeRcon() {
        RconSession r = rconSession;
        if (r != null) {
            rconClient.close(r);
            rconSession = null;
        }
    }

    public void sendStart() {
        commandExecutor.submit(() -> runLifecycle("start", () -> ampClient.startInstance(ensureAmp(), profile.ampInstanceName())));
    }

    public void sendStop() {
        commandExecutor.submit(() -> {
            crashDetector.recordUserStop(java.time.Instant.now());
            runLifecycle("stop", () -> ampClient.stopInstance(ensureAmp(), profile.ampInstanceName()));
        });
    }

    public void sendRestart() {
        commandExecutor.submit(() -> {
            crashDetector.recordUserStop(java.time.Instant.now());
            runLifecycle("restart", () -> ampClient.restartInstance(ensureAmp(), profile.ampInstanceName()));
        });
    }

    public void sendBackup() {
        commandExecutor.submit(() -> runLifecycle("backup", () -> ampClient.backupInstance(ensureAmp(), profile.ampInstanceName())));
    }

    @FunctionalInterface
    private interface IoAction { void run() throws IOException; }

    private void runLifecycle(String label, IoAction action) {
        try {
            action.run();
        } catch (IOException e) {
            LOG.warn("Conch Minecraft: " + label + " failed for " + profile.label(), e);
            ampSession = null;
        }
    }
}
