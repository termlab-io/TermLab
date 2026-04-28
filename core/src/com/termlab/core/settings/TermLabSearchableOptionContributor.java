package com.termlab.core.settings;

import com.intellij.ide.ui.search.SearchableOptionContributor;
import com.intellij.ide.ui.search.SearchableOptionProcessor;
import org.jetbrains.annotations.NotNull;

/**
 * Runtime searchable-options contributor for TermLab settings.
 * Keep this list explicit: searchable option indexing can run off the EDT,
 * so it must not instantiate Swing settings panels or editor previews.
 */
public final class TermLabSearchableOptionContributor extends SearchableOptionContributor {

    @Override
    public void processOptions(@NotNull SearchableOptionProcessor processor) {
        add(processor, TermLabTerminalConfigurable.ID, "Terminal", "Terminal",
            "Terminal",
            "Terminal settings",
            "Appearance",
            "General",
            "Font family",
            "Fallback font",
            "Font size",
            "Line spacing",
            "Ligatures",
            "Cursor shape",
            "Shell program",
            "Shell arguments",
            "Scrollback",
            "Copy on select",
            "Audible bell",
            "Mouse reporting");
        add(processor, TermLabTerminalAppearanceConfigurable.ID, "Appearance", "Terminal",
            "Appearance",
            "Font",
            "Font family",
            "Fallback font",
            "Font size",
            "Line spacing",
            "Ligatures",
            "Cursor shape",
            "Block cursor",
            "Underline cursor",
            "Vertical bar cursor");
        add(processor, TermLabTerminalTerminalConfigurable.ID, "General", "Terminal",
            "General",
            "Program",
            "Shell program",
            "Arguments",
            "Shell arguments",
            "Scrollback lines",
            "Copy text on selection",
            "Copy on select",
            "Audible bell",
            "Enable mouse reporting",
            "Mouse reporting");
        add(processor, TermLabTipsConfigurable.ID, "Tips", "Tips",
            "Tips",
            "Tip of the Day",
            "Show TermLab tips when the application starts",
            "Show tips on startup");
    }

    private static void add(@NotNull SearchableOptionProcessor processor,
                            @NotNull String configurableId,
                            @NotNull String path,
                            @NotNull String configurableDisplayName,
                            String @NotNull ... terms) {
        for (String term : terms) {
            if (term.isBlank()) {
                continue;
            }
            processor.addOptions(term, path, term, configurableId, configurableDisplayName, true);
        }
    }
}
