package com.termlab.proxmox.toolwindow;

import com.termlab.proxmox.model.PveAction;
import com.termlab.proxmox.model.PveGuest;
import org.jetbrains.annotations.NotNull;

public final class ProxmoxViewModel {
    public boolean canRun(@NotNull PveAction action, PveGuest guest) {
        if (guest == null) return false;
        return switch (action) {
            case START -> guest.isStopped();
            case SHUTDOWN, STOP, REBOOT -> guest.isRunning();
        };
    }

    public @NotNull String guestSummary(@NotNull PveGuest guest) {
        return guest.type().displayName() + " " + guest.vmid() + " on " + guest.node()
            + " is " + guest.status().displayName();
    }
}
