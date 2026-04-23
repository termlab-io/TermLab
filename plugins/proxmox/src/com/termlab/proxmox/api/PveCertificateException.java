package com.termlab.proxmox.api;

import org.jetbrains.annotations.NotNull;

public class PveCertificateException extends Exception {
    private final String fingerprint;

    public PveCertificateException(@NotNull String message, @NotNull String fingerprint) {
        super(message);
        this.fingerprint = fingerprint;
    }

    public @NotNull String fingerprint() {
        return fingerprint;
    }
}
