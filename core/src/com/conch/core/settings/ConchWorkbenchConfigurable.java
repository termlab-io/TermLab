package com.conch.core.settings;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Parent node for workbench-level (non-terminal) settings. Child
 * configurables provide the actual editable categories.
 */
public final class ConchWorkbenchConfigurable implements SearchableConfigurable {

    public static final String ID = "conch.workbench.settings";

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Conch Workbench";
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel label = new JLabel("Select a category on the left (Light Editor).");
        label.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(label, BorderLayout.NORTH);
        return panel;
    }

    @Override
    public boolean isModified() { return false; }

    @Override
    public void apply() throws ConfigurationException {}

    @Override
    public void reset() {}
}
