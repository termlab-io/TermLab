package com.termlab.proxmox.api;

import org.jetbrains.annotations.NotNull;

public record PveHttpResponse(int statusCode, @NotNull String body) {}
