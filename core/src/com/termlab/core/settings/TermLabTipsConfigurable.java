package com.termlab.core.settings;

import com.intellij.ide.GeneralSettings;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public final class TermLabTipsConfigurable implements SearchableConfigurable {
    public static final String ID = "termlab.tips.settings";

    private JPanel mainPanel;
    private JCheckBox showTipsOnStartupCheck;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Tips";
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nullable JComponent createComponent() {
        mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("Tip of the Day");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize() + 2f));
        mainPanel.add(title, gbc);

        gbc.gridy++;
        gbc.insets = new Insets(8, 0, 0, 0);
        JLabel description = new JLabel("Show TermLab tips when the application starts.");
        description.setForeground(UIManager.getColor("Label.disabledForeground"));
        mainPanel.add(description, gbc);

        gbc.gridy++;
        gbc.insets = new Insets(12, 0, 0, 0);
        showTipsOnStartupCheck = new JCheckBox("Show tips on startup");
        mainPanel.add(showTipsOnStartupCheck, gbc);

        gbc.gridy++;
        gbc.weighty = 1.0;
        mainPanel.add(Box.createVerticalGlue(), gbc);

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        return showTipsOnStartupCheck != null
            && showTipsOnStartupCheck.isSelected() != GeneralSettings.getInstance().isShowTipsOnStartup();
    }

    @Override
    public void apply() throws ConfigurationException {
        if (showTipsOnStartupCheck != null) {
            GeneralSettings.getInstance().setShowTipsOnStartup(showTipsOnStartupCheck.isSelected());
        }
    }

    @Override
    public void reset() {
        if (showTipsOnStartupCheck != null) {
            showTipsOnStartupCheck.setSelected(GeneralSettings.getInstance().isShowTipsOnStartup());
        }
    }
}
