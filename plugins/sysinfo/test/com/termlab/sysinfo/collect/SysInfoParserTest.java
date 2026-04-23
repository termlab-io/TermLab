package com.termlab.sysinfo.collect;

import com.termlab.sysinfo.model.OsKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class SysInfoParserTest {

    @Test
    void parsesLinuxSnapshotSections() {
        String output = """
            __SYSINFO_OS__
            Linux
            __SYSINFO_HOSTNAME__
            demo
            __SYSINFO_KERNEL__
            6.8.0
            __SYSINFO_ARCH__
            x86_64
            __SYSINFO_PROC_STAT__
            cpu  100 20 30 850 10 0 0 0 0 0
            __SYSINFO_MEMINFO__
            MemTotal:       1000000 kB
            MemFree:         100000 kB
            MemAvailable:    250000 kB
            Buffers:          50000 kB
            Cached:          100000 kB
            __SYSINFO_LOADAVG__
            0.12 0.34 0.56 1/234 5678
            __SYSINFO_UPTIME__
            90061.00 100.00
            __SYSINFO_DF__
            Filesystem 1024-blocks Used Available Capacity Mounted on
            /dev/disk1 2000000 500000 1500000 25% /
            __SYSINFO_DISK_IO__
               8       0 sda 10 0 20 0 30 0 40 0 0 0 0 0 0 0 0 0
            __SYSINFO_NET__
            Inter-| Receive | Transmit
              eth0: 1000 0 0 0 0 0 0 0 3000 0 0 0 0 0 0 0
            __SYSINFO_PS__
              42 root 12.5 3.4 1024 2048 /usr/bin/demo worker
            """;

        ParsedSystemInfo parsed = SysInfoParser.parse(output);

        assertEquals(OsKind.LINUX, parsed.osKind());
        assertEquals("demo", parsed.hostname());
        assertEquals("6.8.0", parsed.kernel());
        assertEquals("x86_64", parsed.architecture());
        assertNotNull(parsed.cpuTimes());
        assertEquals("0.12 0.34 0.56", parsed.loadAverage());
        assertEquals("1d 1h 1m", parsed.uptime());
        assertEquals(1_000_000, parsed.memoryTotalKb());
        assertEquals(750_000, parsed.memoryUsedKb());
        assertEquals(1, parsed.disks().size());
        assertEquals("/", parsed.disks().get(0).mount());
        assertEquals(25, parsed.disks().get(0).usedPercent());
        assertEquals(1, parsed.diskIo().size());
        assertEquals("sda", parsed.diskIo().get(0).name());
        assertEquals(20 * 512L, parsed.diskIo().get(0).readBytes());
        assertEquals(40 * 512L, parsed.diskIo().get(0).writeBytes());
        assertEquals(1, parsed.networks().size());
        assertEquals("eth0", parsed.networks().get(0).name());
        assertEquals(1000, parsed.networks().get(0).rxBytes());
        assertEquals(3000, parsed.networks().get(0).txBytes());
        assertEquals(1, parsed.processes().size());
        assertEquals(42, parsed.processes().get(0).pid());
        assertEquals("/usr/bin/demo worker", parsed.processes().get(0).command());
    }

    @Test
    void parsesMacSnapshotSections() {
        String output = """
            __SYSINFO_OS__
            Darwin
            __SYSINFO_HOSTNAME__
            mac
            __SYSINFO_KERNEL__
            23.6.0
            __SYSINFO_ARCH__
            arm64
            __SYSINFO_TOP__
            CPU usage: 7.20% user, 10.10% sys, 82.70% idle
            __SYSINFO_VM_STAT__
            Mach Virtual Memory Statistics: (page size of 4096 bytes)
            Pages free:                               1000.
            Pages inactive:                           2000.
            __SYSINFO_MEMSIZE__
            8192000
            __SYSINFO_LOADAVG__
            { 1.11 2.22 3.33 }
            __SYSINFO_UPTIME__
            10:24  up 2 days, 1:02, 3 users, load averages: 1.11 2.22 3.33
            __SYSINFO_DF__
            Filesystem 1024-blocks Used Available Capacity Mounted on
            /dev/disk3s1 100000 25000 75000 25% /
            __SYSINFO_NET__
            Name Mtu Network Address Ipkts Ierrs Ibytes Opkts Oerrs Obytes
            en0 1500 <Link#4> aa:bb:cc 1 0 2048 2 0 4096
            __SYSINFO_PS__
              99 dustin 1.5 2.5 4096 8192 /Applications/App Name
            """;

        ParsedSystemInfo parsed = SysInfoParser.parse(output);

        assertEquals(OsKind.MACOS, parsed.osKind());
        assertEquals("mac", parsed.hostname());
        assertEquals("23.6.0", parsed.kernel());
        assertEquals("arm64", parsed.architecture());
        assertEquals(17.3, parsed.cpuUsagePercent(), 0.001);
        assertEquals("1.11 2.22 3.33", parsed.loadAverage());
        assertEquals(8000, parsed.memoryTotalKb());
        assertEquals(0, parsed.memoryUsedKb());
        assertEquals("en0", parsed.networks().get(0).name());
        assertEquals(2048, parsed.networks().get(0).rxBytes());
        assertEquals(4096, parsed.networks().get(0).txBytes());
        assertEquals("/Applications/App Name", parsed.processes().get(0).command());
    }

    @Test
    void computesLinuxCpuDelta() {
        CpuTimes previous = new CpuTimes(100, 200);
        CpuTimes current = new CpuTimes(150, 400);

        assertEquals(75.0, SysInfoParser.cpuUsage(previous, current), 0.001);
        assertNull(SysInfoParser.cpuUsage(null, current));
    }

    @Test
    void reportsUnsupportedOs() {
        ParsedSystemInfo parsed = SysInfoParser.parse("""
            __SYSINFO_OS__
            FreeBSD
            __SYSINFO_HOSTNAME__
            box
            __SYSINFO_UNSUPPORTED__
            FreeBSD
            """);

        assertEquals(OsKind.UNSUPPORTED, parsed.osKind());
        assertEquals("FreeBSD", parsed.uptime());
    }
}
