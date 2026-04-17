package com.termlab.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

public record FileHit(
    @NotNull Kind kind,
    @NotNull String displayName,
    @Nullable String description,
    @NotNull String sourceChip,
    @Nullable Path localPath,
    @Nullable String remotePath,
    @Nullable String remoteHostLabel,
    int priority
) {
    public enum Kind {
        LOCAL,
        REMOTE,
        MESSAGE
    }

    public static @NotNull FileHit local(
        @NotNull Path path,
        @NotNull String sourceChip,
        int priority
    ) {
        Path fileName = path.getFileName();
        return new FileHit(
            Kind.LOCAL,
            fileName == null ? path.toString() : fileName.toString(),
            path.toString(),
            sourceChip,
            path,
            null,
            null,
            priority
        );
    }

    public static @NotNull FileHit remote(
        @NotNull String absolutePath,
        @NotNull String sourceChip,
        int priority
    ) {
        int slash = absolutePath.lastIndexOf('/');
        String name = slash >= 0 && slash < absolutePath.length() - 1
            ? absolutePath.substring(slash + 1)
            : absolutePath;
        return new FileHit(
            Kind.REMOTE,
            name,
            absolutePath,
            sourceChip,
            null,
            absolutePath,
            sourceChip,
            priority
        );
    }

    public static @NotNull FileHit message(@NotNull String text, int priority) {
        return new FileHit(Kind.MESSAGE, text, null, "", null, null, null, priority);
    }
}
