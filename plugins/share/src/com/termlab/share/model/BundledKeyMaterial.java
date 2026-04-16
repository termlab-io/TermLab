package com.termlab.share.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record BundledKeyMaterial(
    @NotNull UUID id,
    @NotNull String privateKeyBase64,
    @Nullable String publicKeyBase64,
    @Nullable String originalFilename
) {
    public static final String SENTINEL_PREFIX = "$TERMLAB_SHARE_KEY:";

    public static @NotNull String sentinelFor(@NotNull UUID id) {
        return SENTINEL_PREFIX + id;
    }

    public static boolean isSentinel(@Nullable String path) {
        return path != null && path.startsWith(SENTINEL_PREFIX);
    }

    public static @NotNull UUID parseSentinel(@NotNull String sentinel) {
        if (!isSentinel(sentinel)) {
            throw new IllegalArgumentException("not a sentinel path: " + sentinel);
        }
        return UUID.fromString(sentinel.substring(SENTINEL_PREFIX.length()));
    }
}
