package com.termlab.sysinfo.collect;

import com.termlab.sysinfo.model.DiskInfo;
import com.termlab.sysinfo.model.DiskIoInfo;
import com.termlab.sysinfo.model.NetworkInfo;
import com.termlab.sysinfo.model.OsKind;
import com.termlab.sysinfo.model.ProcessInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SysInfoParser {

    private SysInfoParser() {}

    public static @NotNull ParsedSystemInfo parse(@NotNull String output) {
        Map<String, List<String>> sections = sections(output);
        String osText = first(sections, "OS", "unknown");
        OsKind os = switch (osText.trim()) {
            case "Linux" -> OsKind.LINUX;
            case "Darwin" -> OsKind.MACOS;
            default -> OsKind.UNSUPPORTED;
        };

        String hostname = first(sections, "HOSTNAME", "unknown").trim();
        if (hostname.isEmpty()) hostname = "unknown";
        String kernel = first(sections, "KERNEL", "").trim();
        String arch = first(sections, "ARCH", "").trim();

        if (os == OsKind.LINUX) {
            return parseLinux(sections, hostname, kernel, arch);
        }
        if (os == OsKind.MACOS) {
            return parseMac(sections, hostname, kernel, arch);
        }
        return new ParsedSystemInfo(os, hostname, kernel, arch, null, null, "", osText, 0, 0, List.of(), List.of(), List.of(), List.of());
    }

    private static @NotNull ParsedSystemInfo parseLinux(
        @NotNull Map<String, List<String>> sections,
        @NotNull String hostname,
        @NotNull String kernel,
        @NotNull String arch
    ) {
        CpuTimes cpuTimes = parseLinuxCpu(first(sections, "PROC_STAT", ""));
        Memory memory = parseLinuxMemory(sections.getOrDefault("MEMINFO", List.of()));
        return new ParsedSystemInfo(
            OsKind.LINUX,
            hostname,
            kernel,
            arch,
            cpuTimes,
            null,
            parseLinuxLoad(first(sections, "LOADAVG", "")),
            parseLinuxUptime(first(sections, "UPTIME", "")),
            memory.totalKb,
            memory.usedKb,
            parseDf(sections.getOrDefault("DF", List.of())),
            parseLinuxDiskIo(sections.getOrDefault("DISK_IO", List.of())),
            parseLinuxNetworks(sections.getOrDefault("NET", List.of())),
            parseProcesses(sections.getOrDefault("PS", List.of()))
        );
    }

    private static @NotNull ParsedSystemInfo parseMac(
        @NotNull Map<String, List<String>> sections,
        @NotNull String hostname,
        @NotNull String kernel,
        @NotNull String arch
    ) {
        long totalKb = parseLong(first(sections, "MEMSIZE", "0")) / 1024;
        long freeKb = parseMacFreeKb(sections.getOrDefault("VM_STAT", List.of()));
        long usedKb = Math.max(0, totalKb - freeKb);
        return new ParsedSystemInfo(
            OsKind.MACOS,
            hostname,
            kernel,
            arch,
            null,
            parseMacCpu(sections.getOrDefault("TOP", List.of())),
            parseMacLoad(first(sections, "LOADAVG", "")),
            first(sections, "UPTIME", "").trim(),
            totalKb,
            usedKb,
            parseDf(sections.getOrDefault("DF", List.of())),
            parseMacDiskIo(sections.getOrDefault("DISK_IO", List.of())),
            parseMacNetworks(sections.getOrDefault("NET", List.of())),
            parseProcesses(sections.getOrDefault("PS", List.of()))
        );
    }

    public static @Nullable Double cpuUsage(@Nullable CpuTimes previous, @Nullable CpuTimes current) {
        if (previous == null || current == null) return null;
        long totalDelta = current.total() - previous.total();
        long idleDelta = current.idle() - previous.idle();
        if (totalDelta <= 0) return null;
        return clampPercent((totalDelta - idleDelta) * 100.0 / totalDelta);
    }

    private static @NotNull Map<String, List<String>> sections(@NotNull String output) {
        Map<String, List<String>> sections = new HashMap<>();
        String current = "";
        for (String line : output.split("\\R", -1)) {
            if (line.startsWith("__SYSINFO_") && line.endsWith("__")) {
                current = line.substring("__SYSINFO_".length(), line.length() - 2);
                sections.putIfAbsent(current, new ArrayList<>());
            } else if (!current.isEmpty()) {
                sections.get(current).add(line);
            }
        }
        return sections;
    }

    private static @Nullable CpuTimes parseLinuxCpu(@NotNull String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 5 || !"cpu".equals(parts[0])) return null;
        long user = parseLong(parts[1]);
        long nice = parseLong(parts[2]);
        long system = parseLong(parts[3]);
        long idle = parseLong(parts[4]);
        long iowait = parts.length > 5 ? parseLong(parts[5]) : 0;
        long irq = parts.length > 6 ? parseLong(parts[6]) : 0;
        long softirq = parts.length > 7 ? parseLong(parts[7]) : 0;
        long steal = parts.length > 8 ? parseLong(parts[8]) : 0;
        long total = user + nice + system + idle + iowait + irq + softirq + steal;
        return new CpuTimes(idle + iowait, total);
    }

    private static @NotNull Memory parseLinuxMemory(@NotNull List<String> lines) {
        long total = 0;
        long available = -1;
        long free = 0;
        long buffers = 0;
        long cached = 0;
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) continue;
            switch (parts[0]) {
                case "MemTotal:" -> total = parseLong(parts[1]);
                case "MemAvailable:" -> available = parseLong(parts[1]);
                case "MemFree:" -> free = parseLong(parts[1]);
                case "Buffers:" -> buffers = parseLong(parts[1]);
                case "Cached:" -> cached = parseLong(parts[1]);
            }
        }
        long avail = available >= 0 ? available : free + buffers + cached;
        return new Memory(total, Math.max(0, total - avail));
    }

    private static @NotNull String parseLinuxLoad(@NotNull String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 3) return "";
        return parts[0] + " " + parts[1] + " " + parts[2];
    }

    private static @NotNull String parseLinuxUptime(@NotNull String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) return "";
        long seconds = (long) parseDouble(parts[0]);
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private static @Nullable Double parseMacCpu(@NotNull List<String> lines) {
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            int idleIndex = lower.indexOf("% idle");
            if (idleIndex < 0) continue;
            int start = idleIndex - 1;
            while (start >= 0 && (Character.isDigit(lower.charAt(start)) || lower.charAt(start) == '.')) {
                start--;
            }
            double idle = parseDouble(lower.substring(start + 1, idleIndex));
            return clampPercent(100.0 - idle);
        }
        return null;
    }

    private static long parseMacFreeKb(@NotNull List<String> lines) {
        long pageSize = 4096;
        long freePages = 0;
        long inactivePages = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Mach Virtual Memory Statistics:")) {
                int index = trimmed.indexOf("page size of ");
                if (index >= 0) {
                    String tail = trimmed.substring(index + "page size of ".length());
                    pageSize = parseLong(tail.replaceAll("[^0-9].*", ""));
                }
            } else if (trimmed.startsWith("Pages free:")) {
                freePages = parseLong(trimmed.replaceAll("[^0-9]", ""));
            } else if (trimmed.startsWith("Pages inactive:")) {
                inactivePages = parseLong(trimmed.replaceAll("[^0-9]", ""));
            }
        }
        return (freePages + inactivePages) * pageSize / 1024;
    }

    private static @NotNull String parseMacLoad(@NotNull String line) {
        return line.replace("{", "").replace("}", "").trim();
    }

    private static @NotNull List<DiskInfo> parseDf(@NotNull List<String> lines) {
        List<DiskInfo> disks = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("Filesystem")) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 6) continue;
            long total = parseLong(parts[1]);
            long used = parseLong(parts[2]);
            long available = parseLong(parts[3]);
            int percent = (int) parseLong(parts[4].replace("%", ""));
            disks.add(new DiskInfo(parts[5], total, used, available, percent));
        }
        return disks;
    }

    private static @NotNull List<NetworkInfo> parseLinuxNetworks(@NotNull List<String> lines) {
        List<NetworkInfo> networks = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            int colon = trimmed.indexOf(':');
            if (colon < 0) continue;
            String name = trimmed.substring(0, colon).trim();
            if (name.isEmpty() || "lo".equals(name)) continue;
            String[] parts = trimmed.substring(colon + 1).trim().split("\\s+");
            if (parts.length < 16) continue;
            networks.add(new NetworkInfo(name, parseLong(parts[0]), parseLong(parts[8]), 0.0, 0.0));
        }
        return networks;
    }

    private static @NotNull List<DiskIoInfo> parseLinuxDiskIo(@NotNull List<String> lines) {
        List<DiskIoInfo> disks = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 14) continue;
            String name = parts[2];
            if (name.startsWith("loop") || name.startsWith("ram")) continue;
            long readSectors = parseLong(parts[5]);
            long writeSectors = parseLong(parts[9]);
            disks.add(new DiskIoInfo(name, readSectors * 512L, writeSectors * 512L, 0.0, 0.0));
        }
        return disks;
    }

    private static @NotNull List<DiskIoInfo> parseMacDiskIo(@NotNull List<String> lines) {
        List<String> devices = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("disk")) {
                devices.clear();
                for (String part : trimmed.split("\\s+")) {
                    if (part.startsWith("disk")) devices.add(part);
                }
                continue;
            }
            if (devices.isEmpty() || trimmed.startsWith("KB/t") || trimmed.startsWith("kB/t")) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < devices.size() * 3) continue;
            List<DiskIoInfo> disks = new ArrayList<>();
            for (int i = 0; i < devices.size(); i++) {
                int base = i * 3;
                long totalKb = (long) (parseDouble(parts[base + 2]) * 1024.0);
                disks.add(new DiskIoInfo(devices.get(i), totalKb * 1024L, 0L, 0.0, 0.0));
            }
            return disks;
        }
        return List.of();
    }

    private static @NotNull List<NetworkInfo> parseMacNetworks(@NotNull List<String> lines) {
        Map<String, NetworkInfo> byName = new HashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("Name ")) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 10) continue;
            String name = parts[0];
            if (name.startsWith("lo")) continue;
            long rx = parseLong(parts[6]);
            long tx = parseLong(parts[9]);
            NetworkInfo previous = byName.get(name);
            if (previous == null || rx + tx > previous.rxBytes() + previous.txBytes()) {
                byName.put(name, new NetworkInfo(name, rx, tx, 0.0, 0.0));
            }
        }
        return List.copyOf(byName.values());
    }

    private static @NotNull List<ProcessInfo> parseProcesses(@NotNull List<String> lines) {
        List<ProcessInfo> processes = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.toUpperCase(Locale.ROOT).startsWith("PID ")) continue;
            String[] parts = trimmed.split("\\s+", 7);
            if (parts.length < 7) continue;
            long pid = parseLong(parts[0]);
            if (pid <= 0) continue;
            processes.add(new ProcessInfo(
                pid,
                parts[1],
                parseDouble(parts[2]),
                parseDouble(parts[3]),
                parseLong(parts[4]),
                parseLong(parts[5]),
                parts[6]
            ));
        }
        processes.sort((a, b) -> Double.compare(b.cpuPercent(), a.cpuPercent()));
        if (processes.size() > 100) {
            return List.copyOf(processes.subList(0, 100));
        }
        return List.copyOf(processes);
    }

    private static @NotNull String first(@NotNull Map<String, List<String>> sections, @NotNull String key, @NotNull String fallback) {
        List<String> lines = sections.get(key);
        if (lines == null || lines.isEmpty()) return fallback;
        return lines.get(0);
    }

    private static long parseLong(@NotNull String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(@NotNull String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static double clampPercent(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }

    private record Memory(long totalKb, long usedKb) {
    }
}
