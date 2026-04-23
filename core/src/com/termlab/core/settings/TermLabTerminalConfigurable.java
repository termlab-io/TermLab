package com.termlab.core.settings;

import com.intellij.ide.DataManager;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.options.ex.Settings;
import com.intellij.ui.components.ActionLink;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;

/**
 * Parent node for terminal settings.
 * Child configurables provide the editable sections.
 */
public final class TermLabTerminalConfigurable implements SearchableConfigurable {

    public static final String ID = "termlab.terminal.settings";

    private JPanel mainPanel;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Terminal";
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nullable JComponent createComponent() {
        mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JLabel title = new JLabel("Terminal settings");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize() + 2f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(title);

        JLabel subtitle = new JLabel("Jump to a section:");
        subtitle.setBorder(BorderFactory.createEmptyBorder(8, 0, 12, 0));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(subtitle);

        mainPanel.add(createShortcutRow(
            "Appearance",
            "Font family, fallback font, size, line spacing, ligatures, cursor shape",
            TermLabTerminalAppearanceConfigurable.ID
        ));
        mainPanel.add(Box.createVerticalStrut(8));
        mainPanel.add(createShortcutRow(
            "General",
            "Shell program, arguments, scrollback, copy on select, bell, mouse reporting",
            TermLabTerminalTerminalConfigurable.ID
        ));
        mainPanel.add(Box.createVerticalGlue());

        return mainPanel;
    }

    private JComponent createShortcutRow(String label, String description, String configurableId) {
        Color borderColor = UIManager.getColor("Component.borderColor");
        if (borderColor == null) {
            borderColor = UIManager.getColor("Separator.foreground");
        }
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));

        ActionLink link = new ActionLink(label, (ActionListener)e -> selectConfigurable(configurableId));
        link.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(link);

        JLabel details = new JLabel(description);
        details.setAlignmentX(Component.LEFT_ALIGNMENT);
        details.setForeground(UIManager.getColor("Label.disabledForeground"));
        details.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        row.add(details);
        return row;
    }

    private void selectConfigurable(@NotNull String configurableId) {
        if (mainPanel == null) {
            return;
        }
        Settings settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(mainPanel));
        if (settings == null) {
            return;
        }
        Configurable configurable = settings.find(configurableId);
        if (configurable != null) {
            settings.select(configurable);
        }
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
