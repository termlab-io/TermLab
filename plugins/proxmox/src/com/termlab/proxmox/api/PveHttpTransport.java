package com.termlab.proxmox.api;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public interface PveHttpTransport {
    @NotNull PveHttpResponse send(@NotNull PveHttpRequest request, String trustedCertificateSha256)
        throws IOException, InterruptedException, PveCertificateException;
}
