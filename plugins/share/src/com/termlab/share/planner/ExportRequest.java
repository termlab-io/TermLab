package com.termlab.share.planner;

import com.termlab.ssh.model.SshHost;
import com.termlab.tunnels.model.SshTunnel;
import com.termlab.vault.model.Vault;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record ExportRequest(
    @NotNull Set<UUID> selectedHostIds,
    @NotNull Set<UUID> selectedTunnelIds,
    boolean includeCredentials,
    @NotNull List<SshHost> allHosts,
    @NotNull List<SshTunnel> allTunnels,
    @Nullable Vault unlockedVault,
    @NotNull Path sshConfigPath,
    @NotNull String sourceHost,
    @NotNull String termlabVersion
) {}
