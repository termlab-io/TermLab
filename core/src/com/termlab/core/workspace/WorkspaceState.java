package com.termlab.core.workspace;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;

public record WorkspaceState(
    @NotNull String name,
    @NotNull List<TabState> tabs
) {
    public record TabState(
        @NotNull String sessionId,
        @NotNull String title,
        @NotNull String providerId,
        @Nullable String workingDirectory,
        @Nullable String connectionId
    ) {}
}
