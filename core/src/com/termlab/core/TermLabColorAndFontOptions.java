package com.termlab.core;

import com.intellij.application.options.colors.ColorAndFontOptions;
import com.intellij.application.options.colors.ColorAndFontPanelFactory;
import com.intellij.application.options.colors.ColorAndFontPanelFactoryEx;
import com.intellij.application.options.colors.FontEditorPreview;
import com.intellij.application.options.colors.FontOptions;
import com.intellij.application.options.colors.NewColorAndFontPanel;
import com.intellij.application.options.colors.SchemesPanel;
import com.intellij.application.options.colors.SimpleEditorPreview;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import com.intellij.openapi.options.colors.ColorSettingsPages;
import com.intellij.psi.codeStyle.DisplayPriority;
import com.intellij.psi.codeStyle.DisplayPrioritySortable;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class TermLabColorAndFontOptions extends ColorAndFontOptions {

    @Override
    protected List<ColorAndFontPanelFactory> createPanelFactories() {
        List<ColorAndFontPanelFactory> factories = new ArrayList<>();
        factories.add(new FontConfigurableFactory());

        for (ColorSettingsPage page : ColorSettingsPages.getInstance().getRegisteredPages()) {
            factories.add(new ColorSettingsFactory(page));
        }

        factories.addAll(ColorAndFontPanelFactory.EP_NAME.getExtensionList());
        factories.sort((f1, f2) ->
            DisplayPrioritySortable.compare(f1, f2, factory -> factory.getPanelDisplayName()));
        return new ArrayList<>(factories);
    }

    private static final class FontConfigurableFactory implements ColorAndFontPanelFactoryEx {
        @Override
        public @NotNull NewColorAndFontPanel createPanel(@NotNull ColorAndFontOptions options) {
            FontEditorPreview preview = new FontEditorPreview(() -> options.getSelectedScheme(), true);
            return new NewColorAndFontPanel(
                new SchemesPanel(options, 0),
                new FontOptions(options),
                preview,
                ColorAndFontOptions.getFontConfigurableName(),
                null,
                null
            ) {
                @Override
                public boolean containsFontOptions() {
                    return true;
                }
            };
        }

        @Override
        public @NotNull String getPanelDisplayName() {
            return ColorAndFontOptions.getFontConfigurableName();
        }

        @Override
        public @NotNull @NonNls String getConfigurableId() {
            return "ColorSchemeFont";
        }

        @Override
        public @NotNull DisplayPriority getPriority() {
            return DisplayPriority.FONT_SETTINGS;
        }
    }

    private static final class ColorSettingsFactory implements ColorAndFontPanelFactoryEx {
        private final ColorSettingsPage page;

        private ColorSettingsFactory(@NotNull ColorSettingsPage page) {
            this.page = page;
        }

        @Override
        public @NotNull NewColorAndFontPanel createPanel(@NotNull ColorAndFontOptions options) {
            return NewColorAndFontPanel.create(new SimpleEditorPreview(options, page), page.getDisplayName(), options, null, page);
        }

        @Override
        public @NotNull String getPanelDisplayName() {
            return page.getDisplayName();
        }

        @Override
        public @NotNull @NonNls String getConfigurableId() {
            return page.getId();
        }

        @Override
        public @NotNull DisplayPriority getPriority() {
            if (page instanceof DisplayPrioritySortable sortable) {
                return sortable.getPriority();
            }
            return DisplayPriority.LANGUAGE_SETTINGS;
        }

        @Override
        public int getWeight() {
            if (page instanceof DisplayPrioritySortable sortable) {
                return sortable.getWeight();
            }
            return ColorAndFontPanelFactoryEx.super.getWeight();
        }

        @Override
        public @NotNull Class<?> getOriginalClass() {
            return page.getClass();
        }
    }
}
