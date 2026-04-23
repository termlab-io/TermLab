package com.termlab.proxmox.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record PveCluster(
    @NotNull UUID id,
    @NotNull String label,
    @NotNull String endpoint,
    @NotNull UUID credentialId,
    @Nullable String trustedCertificateSha256
) {
    public PveCluster withTrustedCertificateSha256(@Nullable String fingerprint) {
        return new PveCluster(id, label, endpoint, credentialId, fingerprint);
    }

    @Override
    public String toString() {
        return label;
    }
}
