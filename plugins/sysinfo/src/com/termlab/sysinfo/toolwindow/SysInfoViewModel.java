package com.termlab.sysinfo.toolwindow;

import com.termlab.ssh.model.SshHost;
import com.termlab.sysinfo.model.SystemSnapshot;
import com.termlab.sysinfo.model.SystemTarget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SysInfoViewModel {

    public static final int MEMORY_HISTORY_LIMIT = 90;

    private final List<Double> memoryHistory = new ArrayList<>();
    private final List<Double> cpuHistory = new ArrayList<>();
    private final List<Double> diskIoHistory = new ArrayList<>();
    private @Nullable SystemSnapshot snapshot;
    private @NotNull String status = "";

    public @NotNull List<SystemTarget> targetsFor(@NotNull List<SshHost> hosts) {
        List<SystemTarget> targets = new ArrayList<>();
        targets.add(SystemTarget.local());
        hosts.stream()
            .sorted(Comparator.comparing(SshHost::label, String.CASE_INSENSITIVE_ORDER))
            .map(host -> SystemTarget.ssh(host.id(), host.label()))
            .forEach(targets::add);
        return targets;
    }

    public void clearHistory() {
        memoryHistory.clear();
        cpuHistory.clear();
        diskIoHistory.clear();
    }

    public void applySnapshot(@NotNull SystemSnapshot snapshot) {
        this.snapshot = snapshot;
        memoryHistory.add(snapshot.memoryUsedPercent());
        while (memoryHistory.size() > MEMORY_HISTORY_LIMIT) {
            memoryHistory.remove(0);
        }
        cpuHistory.add(snapshot.cpuUsagePercent() == null ? 0.0 : snapshot.cpuUsagePercent());
        while (cpuHistory.size() > MEMORY_HISTORY_LIMIT) {
            cpuHistory.remove(0);
        }
        double diskRate = snapshot.diskIo().stream()
            .mapToDouble(disk -> disk.readBytesPerSecond() + disk.writeBytesPerSecond())
            .sum();
        diskIoHistory.add(Math.min(100.0, diskRate / (1024.0 * 1024.0)));
        while (diskIoHistory.size() > MEMORY_HISTORY_LIMIT) {
            diskIoHistory.remove(0);
        }
    }

    public @NotNull List<Double> memoryHistory() {
        return List.copyOf(memoryHistory);
    }

    public @NotNull List<Double> cpuHistory() {
        return List.copyOf(cpuHistory);
    }

    public @NotNull List<Double> diskIoHistory() {
        return List.copyOf(diskIoHistory);
    }

    public @Nullable SystemSnapshot snapshot() {
        return snapshot;
    }

    public @NotNull String status() {
        return status;
    }

    public void setStatus(@NotNull String status) {
        this.status = status;
    }
}
