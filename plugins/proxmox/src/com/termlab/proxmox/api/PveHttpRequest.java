package com.termlab.proxmox.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;

public record PveHttpRequest(
    @NotNull String method,
    @NotNull URI uri,
    @NotNull String authorization,
    @Nullable String body
) {}
