package com.conch.sdk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * A single item that appears in the command palette search results.
 */
public final class PaletteItem {
    private final String id;
    private final String displayName;
    private final String description;
    private final Icon icon;
    private final Runnable action;

    public PaletteItem(@NotNull String id,
                       @NotNull String displayName,
                       @Nullable String description,
                       @Nullable Icon icon,
                       @NotNull Runnable action) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
        this.action = action;
    }

    public @NotNull String getId() { return id; }
    public @NotNull String getDisplayName() { return displayName; }
    public @Nullable String getDescription() { return description; }
    public @Nullable Icon getIcon() { return icon; }
    public @NotNull Runnable getAction() { return action; }
}
