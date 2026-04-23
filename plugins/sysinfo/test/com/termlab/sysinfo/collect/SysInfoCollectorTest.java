package com.termlab.sysinfo.collect;

import com.termlab.ssh.model.HostStore;
import com.termlab.sysinfo.model.SystemSnapshot;
import com.termlab.sysinfo.model.SystemTarget;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SysInfoCollectorTest {

    @Test
    void collectsLocalSnapshotWithFakeRunner() throws Exception {
        CommandRunner runner = new CommandRunner() {
            @Override
            public CommandResult run(String command, Duration timeout) throws IOException {
                return new CommandResult(0, """
                    __SYSINFO_OS__
                    Linux
                    __SYSINFO_HOSTNAME__
                    local
                    __SYSINFO_KERNEL__
                    6.8.0
                    __SYSINFO_ARCH__
                    x86_64
                    __SYSINFO_PROC_STAT__
                    cpu  100 0 100 800 0 0 0 0
                    __SYSINFO_MEMINFO__
                    MemTotal: 1000 kB
                    MemAvailable: 400 kB
                    __SYSINFO_LOADAVG__
                    1.00 2.00 3.00 1/2 3
                    __SYSINFO_UPTIME__
                    60.00 0.00
                    __SYSINFO_DF__
                    Filesystem 1024-blocks Used Available Capacity Mounted on
                    /dev/root 1000 250 750 25% /
                    __SYSINFO_DISK_IO__
                       8       0 sda 10 0 20 0 30 0 40 0 0 0 0 0 0 0 0 0
                    __SYSINFO_NET__
                      eth0: 1000 0 0 0 0 0 0 0 2000 0 0 0 0 0 0 0
                    __SYSINFO_PS__
                    1 root 0.1 0.2 10 20 init
                    """, "");
            }
        };
        SysInfoCollector collector = new SysInfoCollector(new HostStore(List.of()), runner);

        SystemSnapshot snapshot = collector.collect(SystemTarget.local());

        assertEquals("local", snapshot.hostname());
        assertEquals("6.8.0", snapshot.kernel());
        assertEquals("x86_64", snapshot.architecture());
        assertEquals(600, snapshot.memoryUsedKb());
        assertEquals(1, snapshot.diskIo().size());
        assertEquals(1, snapshot.networks().size());
        assertEquals(1, snapshot.processes().size());
    }

    @Test
    void sendsProcessSignalsThroughRunner() throws Exception {
        List<String> commands = new ArrayList<>();
        CommandRunner runner = (command, timeout) -> {
            commands.add(command);
            return new CommandResult(0, "", "");
        };
        SysInfoCollector collector = new SysInfoCollector(new HostStore(List.of()), runner);

        collector.signalProcess(SystemTarget.local(), 1234, SysInfoCollector.ProcessSignal.TERM);
        collector.signalProcess(SystemTarget.local(), 1234, SysInfoCollector.ProcessSignal.KILL);

        assertEquals(List.of("kill -TERM 1234", "kill -KILL 1234"), commands);
    }
}
