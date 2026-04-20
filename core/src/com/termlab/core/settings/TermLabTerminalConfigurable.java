package com.termlab.core.settings;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Parent node for TermLab terminal settings.
 * Child configurables provide actual editable categories.
 */
public final class TermLabTerminalConfigurable implements SearchableConfigurable {

    public static final String ID = "termlab.terminal.settings";

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "TermLab Terminal";
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel label = new JLabel("Select a category on the left (Terminal).");
        label.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(label, BorderLayout.NORTH);
        return panel;
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() throws ConfigurationException {
        // Parent node has no editable fields.
    }

    @Override
    public void reset() {
        // Parent node has no editable fields.
    }
}
