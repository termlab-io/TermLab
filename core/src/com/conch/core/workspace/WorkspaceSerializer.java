package com.conch.core.workspace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNull;

public final class WorkspaceSerializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private WorkspaceSerializer() {}

    public static @NotNull String toJson(@NotNull WorkspaceState state) {
        return GSON.toJson(state);
    }

    public static @NotNull WorkspaceState fromJson(@NotNull String json) {
        return GSON.fromJson(json, WorkspaceState.class);
    }
}
