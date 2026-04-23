package com.termlab.proxmox.model;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

public record PveGuestDetails(
    @NotNull PveGuest guest,
    @NotNull Map<String, String> config
) {}
