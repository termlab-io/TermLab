package com.termlab.share.planner;

import com.termlab.ssh.model.SshHost;
import com.termlab.tunnels.model.SshTunnel;
import com.termlab.vault.model.Vault;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record CurrentState(
    @NotNull List<SshHost> hosts,
    @NotNull List<SshTunnel> tunnels,
    @Nullable Vault vault
) {
    public static @NotNull CurrentState empty() {
        return new CurrentState(List.of(), List.of(), null);
    }
}
