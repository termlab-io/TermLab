package com.termlab.share.planner;

import org.jetbrains.annotations.NotNull;

public record ImportResult(
    int hostsImported,
    int tunnelsImported,
    int accountsImported,
    int keysImported,
    int skipped,
    @NotNull String summary
) {}
