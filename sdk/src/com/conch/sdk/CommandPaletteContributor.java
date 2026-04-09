package com.conch.sdk;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Extension point for plugins that contribute searchable items
 * to the command palette. SSH plugin contributes hosts,
 * vault plugin contributes accounts, etc.
 */
public interface CommandPaletteContributor {

    /** Tab label in the command palette (e.g., "Hosts", "Vault"). */
    @NotNull String getTabName();

    /** Weight for ordering tabs. Lower values appear first. */
    int getTabWeight();

    /**
     * Search for items matching the query string.
     * Called on each keystroke in the command palette.
     *
     * @param query the user's search text (may be empty)
     * @return matching items, ordered by relevance
     */
    @NotNull List<PaletteItem> search(@NotNull String query);
}
