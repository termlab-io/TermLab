package com.termlab.proxmox.toolwindow;

import com.termlab.proxmox.model.PveAction;
import com.termlab.proxmox.model.PveGuest;
import com.termlab.proxmox.model.PveGuestStatus;
import com.termlab.proxmox.model.PveGuestType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxmoxViewModelTest {
    private final ProxmoxViewModel viewModel = new ProxmoxViewModel();

    @Test
    void enablesActionsByGuestState() {
        PveGuest running = guest(PveGuestStatus.RUNNING);
        PveGuest stopped = guest(PveGuestStatus.STOPPED);

        assertTrue(viewModel.canRun(PveAction.SHUTDOWN, running));
        assertTrue(viewModel.canRun(PveAction.STOP, running));
        assertTrue(viewModel.canRun(PveAction.REBOOT, running));
        assertFalse(viewModel.canRun(PveAction.START, running));

        assertTrue(viewModel.canRun(PveAction.START, stopped));
        assertFalse(viewModel.canRun(PveAction.STOP, stopped));
    }

    @Test
    void formatsTablePercentages() {
        PveGuest guest = new PveGuest(1, "db", "pve", PveGuestType.QEMU, PveGuestStatus.RUNNING,
            0, 1, 512, 1024, 256, 1024, 0, null);

        assertTrue(guest.memoryPercent() > 49.0);
        assertTrue(guest.diskPercent() > 24.0);
    }

    private static PveGuest guest(PveGuestStatus status) {
        return new PveGuest(1, "db", "pve", PveGuestType.QEMU, status, 0, 1, 0, 0, 0, 0, 0, null);
    }
}
