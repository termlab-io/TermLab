package com.termlab.share.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class ImportItem {
    public enum Type { HOST, TUNNEL, ACCOUNT, KEY }
    public enum Status { NEW, SAME_UUID_EXISTS, LABEL_COLLISION, REFERENCE_BROKEN }
    public enum Action { IMPORT, SKIP, REPLACE, RENAME }

    public final @NotNull Type type;
    public final @NotNull UUID id;
    public final @NotNull String label;
    public @NotNull Status status;
    public @NotNull Action action;
    public @Nullable String renameTo;
    public final @NotNull Object payload;

    public ImportItem(
        @NotNull Type type,
        @NotNull UUID id,
        @NotNull String label,
        @NotNull Status status,
        @NotNull Action action,
        @NotNull Object payload
    ) {
        this.type = type;
        this.id = id;
        this.label = label;
        this.status = status;
        this.action = action;
        this.payload = payload;
    }
}
