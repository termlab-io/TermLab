package com.termlab.proxmox.api;

import org.jetbrains.annotations.NotNull;

public final class PveCertificateMismatchException extends PveCertificateException {
    public PveCertificateMismatchException(@NotNull String fingerprint) {
        super("The Proxmox server certificate changed.", fingerprint);
    }
}
