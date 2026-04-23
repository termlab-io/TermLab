package com.termlab.proxmox.credentials;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public final class PveApiToken implements AutoCloseable {
    private final String tokenId;
    private final char[] secret;

    public PveApiToken(@NotNull String tokenId, char @NotNull [] secret) {
        this.tokenId = tokenId;
        this.secret = secret;
    }

    public @NotNull String authorizationValue() {
        return "PVEAPIToken=" + tokenId + "=" + new String(secret);
    }

    @Override
    public void close() {
        Arrays.fill(secret, '\0');
    }
}
