package com.termlab.core.palette;

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.TabsCustomizationStrategy;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * Limits Search Everywhere tabs to only Actions and Terminals.
 * Hides the Classes, Files, Symbols, Text, and other tabs that are
 * irrelevant to the TermLab.
 */
public final class TermLabTabsCustomizationStrategy implements TabsCustomizationStrategy {

    /**
     * Provider IDs for tabs that should remain visible.
     * "ActionSearchEverywhereContributor" is the Actions tab from the
     * platform. "TermLabTerminals" / "TermLabHosts" / "TermLabVault" are our
     * custom tabs contributed by the core, SSH, and vault plugins
     * respectively.
     */
    private static final Set<String> ALLOWED_TAB_IDS = Set.of(
        "ActionSearchEverywhereContributor",
        "TermLabTerminals",
        "TermLabHosts",
        "TermLabVault",
        "TermLabTunnels",
        "TermLabSftp"
    );

    @Override
    @SuppressWarnings("unchecked")
    public @NotNull List<SearchEverywhereContributor<?>> getSeparateTabContributors(
            @NotNull List<? extends SearchEverywhereContributor<?>> contributors) {
        return (List<SearchEverywhereContributor<?>>) (List<?>) contributors.stream()
            .filter(c -> ALLOWED_TAB_IDS.contains(c.getSearchProviderId()))
            .toList();
    }
}
