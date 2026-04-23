package com.termlab.sysinfo.toolwindow;

import com.termlab.ssh.model.PromptPasswordAuth;
import com.termlab.ssh.model.SshHost;
import com.termlab.sysinfo.model.SystemSnapshot;
import com.termlab.sysinfo.model.SystemTarget;
import com.termlab.sysinfo.model.OsKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SysInfoViewModelTest {

    @Test
    void targetListStartsWithLocalThenSortsHosts() {
        SysInfoViewModel model = new SysInfoViewModel();
        SshHost beta = SshHost.create("beta", "beta.example", 22, "me", new PromptPasswordAuth(), null, null);
        SshHost alpha = SshHost.create("alpha", "alpha.example", 22, "me", new PromptPasswordAuth(), null, null);

        List<SystemTarget> targets = model.targetsFor(List.of(beta, alpha));

        assertEquals("Local", targets.get(0).label());
        assertEquals("alpha", targets.get(1).label());
        assertEquals("beta", targets.get(2).label());
    }

    @Test
    void memoryHistoryIsCapped() {
        SysInfoViewModel model = new SysInfoViewModel();
        for (int i = 0; i < SysInfoViewModel.MEMORY_HISTORY_LIMIT + 5; i++) {
            model.applySnapshot(new SystemSnapshot(
                Instant.now(),
                OsKind.LINUX,
                "host",
                "6.8.0",
                "x86_64",
                12,
                (double) i,
                "",
                "",
                100,
                i,
                List.of(),
                List.of(),
                List.of(),
                List.of()
            ));
        }

        ArrayList<Double> history = new ArrayList<>(model.memoryHistory());
        assertEquals(SysInfoViewModel.MEMORY_HISTORY_LIMIT, history.size());
        assertEquals(5.0, history.get(0), 0.001);

        ArrayList<Double> cpuHistory = new ArrayList<>(model.cpuHistory());
        assertEquals(SysInfoViewModel.MEMORY_HISTORY_LIMIT, cpuHistory.size());
        assertEquals(5.0, cpuHistory.get(0), 0.001);
    }

    @Test
    void successfulSnapshotDoesNotSetStatusText() {
        SysInfoViewModel model = new SysInfoViewModel();

        model.applySnapshot(new SystemSnapshot(
            Instant.now(),
            OsKind.LINUX,
            "host",
            "6.8.0",
            "x86_64",
            12,
            null,
            "",
            "",
            100,
            50,
            List.of(),
            List.of(),
            List.of(),
            List.of()
        ));

        assertEquals("", model.status());
    }
}
