package com.termlab.share.model;

import com.termlab.ssh.model.SshHost;
import com.termlab.tunnels.model.SshTunnel;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ShareBundle(
    int schemaVersion,
    @NotNull BundleMetadata metadata,
    @NotNull List<SshHost> hosts,
    @NotNull List<SshTunnel> tunnels,
    @NotNull BundledVault vault,
    @NotNull List<BundledKeyMaterial> keyMaterial
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
