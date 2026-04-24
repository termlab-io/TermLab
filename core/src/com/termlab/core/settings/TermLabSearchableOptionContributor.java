package com.termlab.core.settings;

import com.intellij.ide.ui.search.SearchableOptionContributor;
import com.intellij.ide.ui.search.SearchableOptionProcessor;
import com.intellij.openapi.options.SearchableConfigurable;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Runtime searchable-options contributor for TermLab settings.
 * Index terms are auto-discovered from the actual UI labels/components,
 * so settings search stays in sync as pages evolve.
 */
public final class TermLabSearchableOptionContributor extends SearchableOptionContributor {

    @Override
    public void processOptions(@NotNull SearchableOptionProcessor processor) {
        contributeConfigurable(processor, new TermLabTerminalConfigurable(), "Terminal");
        contributeConfigurable(processor, new TermLabTerminalAppearanceConfigurable(), "Terminal");
        contributeConfigurable(processor, new TermLabTerminalTerminalConfigurable(), "Terminal");
        contributeConfigurable(processor, new TermLabTipsConfigurable(), "Tips");
    }

    private static void contributeConfigurable(@NotNull SearchableOptionProcessor processor,
                                               @NotNull SearchableConfigurable configurable,
                                               @NotNull String configurableDisplayName) {
        Set<String> discovered = new LinkedHashSet<>();
        discovered.add(configurable.getDisplayName());

        try {
            JComponent component = configurable.createComponent();
            if (component != null) {
                collectSearchTexts(component, discovered);
            }

            String path = configurable.getDisplayName();
            for (String text : discovered) {
                if (text == null || text.isBlank()) {
                    continue;
                }
                processor.addOptions(
                    text,
                    path,
                    text,
                    configurable.getId(),
                    configurableDisplayName,
                    true
                );
            }
        } finally {
            configurable.disposeUIResources();
        }
    }

    private static void collectSearchTexts(@NotNull Component component, @NotNull Set<String> out) {
        if (component instanceof JLabel label) {
            addText(label.getText(), out);
        } else if (component instanceof AbstractButton button) {
            addText(button.getText(), out);
        } else if (component instanceof JComboBox<?> comboBox) {
            ComboBoxModel<?> model = comboBox.getModel();
            int size = model.getSize();
            for (int i = 0; i < size; i++) {
                Object item = model.getElementAt(i);
                if (item != null) {
                    addText(item.toString(), out);
                }
            }
        }

        if (component instanceof JComponent jComponent) {
            Border border = jComponent.getBorder();
            if (border instanceof TitledBorder titledBorder) {
                addText(titledBorder.getTitle(), out);
            }

            String tooltip = jComponent.getToolTipText();
            if (tooltip != null) {
                addText(tooltip, out);
            }
        }

        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectSearchTexts(child, out);
            }
        }
    }

    private static void addText(String text, Set<String> out) {
        if (text == null) {
            return;
        }
        String normalized = text.trim();
        if (!normalized.isEmpty()) {
            out.add(normalized);
        }
    }
}
