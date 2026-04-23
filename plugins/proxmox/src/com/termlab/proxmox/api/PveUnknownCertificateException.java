package com.termlab.proxmox.api;

import org.jetbrains.annotations.NotNull;

public final class PveUnknownCertificateException extends PveCertificateException {
    public PveUnknownCertificateException(@NotNull String fingerprint) {
        super("The Proxmox server uses an untrusted certificate.", fingerprint);
    }
}
