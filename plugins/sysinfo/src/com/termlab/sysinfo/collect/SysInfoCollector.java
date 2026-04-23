package com.termlab.sysinfo.collect;

import com.intellij.openapi.application.ApplicationManager;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.SshHost;
import com.termlab.sysinfo.model.SystemSnapshot;
import com.termlab.sysinfo.model.SystemTarget;
import com.termlab.sysinfo.model.DiskIoInfo;
import com.termlab.sysinfo.model.NetworkInfo;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SysInfoCollector {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(12);

    private final HostStore hostStore;
    private final CommandRunner localRunner;
    private final Map<String, CpuTimes> previousCpu = new ConcurrentHashMap<>();
    private final Map<String, NetworkSample> previousNetwork = new ConcurrentHashMap<>();
    private final Map<String, DiskIoSample> previousDiskIo = new ConcurrentHashMap<>();

    public SysInfoCollector(@NotNull HostStore hostStore) {
        this(hostStore, new LocalCommandRunner());
    }

    public SysInfoCollector(@NotNull HostStore hostStore, @NotNull CommandRunner localRunner) {
        this.hostStore = hostStore;
        this.localRunner = localRunner;
    }

    public @NotNull SystemSnapshot collect(@NotNull SystemTarget target) throws IOException, InterruptedException {
        CommandRunner runner = runnerFor(target);
        long startNs = System.nanoTime();
        CommandResult result = runner.run(SysInfoScript.COMMAND, COMMAND_TIMEOUT);
        long elapsedMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
        if (result.exitCode() != 0 && result.stdout().isBlank()) {
            throw new IOException(result.stderr().isBlank()
                ? "System information command failed with exit code " + result.exitCode()
                : result.stderr().trim());
        }

        ParsedSystemInfo parsed = SysInfoParser.parse(result.stdout());
        Double cpu = parsed.cpuUsagePercent();
        if (cpu == null) {
            CpuTimes previous = previousCpu.put(target.key(), parsed.cpuTimes());
            cpu = SysInfoParser.cpuUsage(previous, parsed.cpuTimes());
        }
        List<NetworkInfo> networks = networkRates(target, parsed.networks());
        List<DiskIoInfo> diskIo = diskIoRates(target, parsed.diskIo());
        return new SystemSnapshot(
            Instant.now(),
            parsed.osKind(),
            parsed.hostname(),
            parsed.kernel(),
            parsed.architecture(),
            elapsedMs,
            cpu,
            parsed.loadAverage(),
            parsed.uptime(),
            parsed.memoryTotalKb(),
            parsed.memoryUsedKb(),
            parsed.disks(),
            diskIo,
            networks,
            parsed.processes()
        );
    }

    public void reset(@NotNull SystemTarget target) {
        previousCpu.remove(target.key());
        previousNetwork.keySet().removeIf(key -> key.startsWith(target.key() + ":"));
        previousDiskIo.keySet().removeIf(key -> key.startsWith(target.key() + ":"));
    }

    public void signalProcess(
        @NotNull SystemTarget target,
        long pid,
        @NotNull ProcessSignal signal
    ) throws IOException, InterruptedException {
        if (pid <= 0) {
            throw new IOException("Invalid process id: " + pid);
        }
        CommandResult result = runnerFor(target).run("kill -" + signal.commandName() + " " + pid, Duration.ofSeconds(5));
        if (result.exitCode() != 0) {
            throw new IOException(result.stderr().isBlank()
                ? "kill exited with code " + result.exitCode()
                : result.stderr().trim());
        }
    }

    private @NotNull List<NetworkInfo> networkRates(
        @NotNull SystemTarget target,
        @NotNull List<NetworkInfo> networks
    ) {
        long nowNs = System.nanoTime();
        return networks.stream().map(current -> {
            String key = target.key() + ":" + current.name();
            NetworkSample previous = previousNetwork.put(key, new NetworkSample(nowNs, current.rxBytes(), current.txBytes()));
            if (previous == null) return current;
            double seconds = Math.max(0.001, (nowNs - previous.timestampNs()) / 1_000_000_000.0);
            double rxRate = Math.max(0.0, (current.rxBytes() - previous.rxBytes()) / seconds);
            double txRate = Math.max(0.0, (current.txBytes() - previous.txBytes()) / seconds);
            return current.withRates(rxRate, txRate);
        }).toList();
    }

    private @NotNull List<DiskIoInfo> diskIoRates(
        @NotNull SystemTarget target,
        @NotNull List<DiskIoInfo> disks
    ) {
        long nowNs = System.nanoTime();
        return disks.stream().map(current -> {
            String key = target.key() + ":" + current.name();
            DiskIoSample previous = previousDiskIo.put(key, new DiskIoSample(nowNs, current.readBytes(), current.writeBytes()));
            if (previous == null) return current;
            double seconds = Math.max(0.001, (nowNs - previous.timestampNs()) / 1_000_000_000.0);
            double readRate = Math.max(0.0, (current.readBytes() - previous.readBytes()) / seconds);
            double writeRate = Math.max(0.0, (current.writeBytes() - previous.writeBytes()) / seconds);
            return current.withRates(readRate, writeRate);
        }).toList();
    }

    private @NotNull CommandRunner runnerFor(@NotNull SystemTarget target) throws IOException {
        if (target.kind() == SystemTarget.Kind.LOCAL) {
            return localRunner;
        }
        UUID hostId = target.hostId();
        if (hostId == null) {
            throw new IOException("No SSH host selected");
        }
        SshHost host = hostStore.findById(hostId);
        if (host == null) {
            throw new IOException("SSH host no longer exists: " + target.label());
        }
        if (ApplicationManager.getApplication() == null) {
            throw new IOException("Remote collection requires the TermLab application");
        }
        return new RemoteCommandRunner(host);
    }

    private record NetworkSample(long timestampNs, long rxBytes, long txBytes) {
    }

    private record DiskIoSample(long timestampNs, long readBytes, long writeBytes) {
    }

    public enum ProcessSignal {
        TERM("TERM"),
        KILL("KILL");

        private final String commandName;

        ProcessSignal(@NotNull String commandName) {
            this.commandName = commandName;
        }

        @NotNull String commandName() {
            return commandName;
        }
    }
}
