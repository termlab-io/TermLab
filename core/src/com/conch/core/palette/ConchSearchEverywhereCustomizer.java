package com.conch.core.palette;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPoint;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * Removes unwanted built-in Search Everywhere contributors at app startup so
 * Conch's Command Palette (rebranded Search Everywhere) never surfaces files,
 * classes, symbols, text matches, run configurations, top hits, calculator
 * results, or YAML keys.
 *
 * <p>{@link ConchTabsCustomizationStrategy} separately limits which contributors
 * appear as their own tab, but the "All" tab aggregates <b>every</b>
 * registered contributor regardless of tab visibility. The only way to keep
 * files out of the All tab is to unregister them from the extension point
 * entirely — which is what this listener does on {@code appFrameCreated},
 * before the user can possibly trigger Search Everywhere for the first time.
 *
 * <p>The contributors we keep:
 * <ul>
 *   <li>{@code ActionSearchEverywhereContributor} — platform actions</li>
 *   <li>{@code ConchTerminals} (our own {@link TerminalPaletteContributor}) —
 *       open terminal tabs</li>
 * </ul>
 *
 * <p>Everything else registered under the {@code com.intellij.searchEverywhereContributor}
 * extension point gets pulled. Matching is done by fully qualified class name
 * so we don't have to instantiate the unwanted factories just to check their
 * identity.
 */
public final class ConchSearchEverywhereCustomizer implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(ConchSearchEverywhereCustomizer.class);

    /**
     * Fully qualified factory class names to unregister. If the platform
     * renames one of these, the filter silently leaves it registered and
     * we'll see it in the All tab again — at which point we add the new
     * name here.
     */
    private static final Set<String> UNWANTED_FACTORIES = Set.of(
        // LangExtensions.xml
        "com.intellij.ide.actions.searcheverywhere.FileSearchEverywhereContributorFactory",
        "com.intellij.ide.actions.searcheverywhere.NonIndexableFilesSEContributor$Factory",
        "com.intellij.ide.actions.searcheverywhere.CalculatorSEContributorFactory",
        "com.intellij.ide.actions.searcheverywhere.RecentFilesSEContributor$Factory",
        "com.intellij.ide.actions.searcheverywhere.TopHitSEContributor$Factory",
        // SearchEverywhereCodeInsightContributors.xml
        "com.intellij.find.impl.TextSearchContributor$Companion$Factory",
        "com.intellij.find.impl.TextSearchContributor$Factory",
        "com.intellij.ide.actions.searcheverywhere.ClassSearchEverywhereContributor$Factory",
        "com.intellij.ide.actions.searcheverywhere.SymbolSearchEverywhereContributor$Factory",
        "com.intellij.ide.actions.searcheverywhere.RunConfigurationsSEContributor$Factory",
        // YAML backend
        "org.jetbrains.yaml.navigation.YAMLKeysSearchEverywhereContributor$Factory",
        // Git (not in Conch's product, but listed for completeness if it gets added)
        "git4idea.search.GitSearchEverywhereContributor$Factory"
    );

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        ExtensionPoint<com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory<?>> ep =
            SearchEverywhereContributor.EP_NAME.getPoint();

        boolean removed = ep.unregisterExtensions(
            (className, adapter) -> !UNWANTED_FACTORIES.contains(className),
            /* stopAfterFirstMatch = */ false);

        if (removed) {
            LOG.info("Conch: stripped built-in Search Everywhere contributors "
                + "(files, classes, symbols, text, run configs, top hits, calculator, etc.)");
        }
    }
}
