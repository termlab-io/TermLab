package com.termlab.share.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;

public record BundleMetadata(
    @NotNull Instant createdAt,
    @NotNull String sourceHost,
    @NotNull String termlabVersion,
    boolean includesCredentials
) {}
