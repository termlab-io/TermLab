package com.termlab.core.settings;

import com.intellij.application.options.editor.fonts.AppConsoleFontConfigurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.ui.JBSplitter;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public final class TermLabTerminalAppearanceConfigurable implements SearchableConfigurable {

    public static final String ID = "termlab.terminal.settings.appearance";

    private JPanel mainPanel;
    private AppConsoleFontConfigurable fontConfigurable;
    private JComboBox<String> cursorShapeCombo;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Appearance";
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nullable JComponent createComponent() {
        fontConfigurable = new AppConsoleFontConfigurable();
        cursorShapeCombo = new JComboBox<>(new String[]{"BLOCK", "UNDERLINE", "VERTICAL_BAR"});

        JComponent fontComponent = fontConfigurable.createComponent();
        JComponent cursorPanel = createCursorPanel();

        mainPanel = new JPanel(new BorderLayout(0, 12));
        mainPanel.add(fontComponent, BorderLayout.CENTER);
        if (!attachCursorPanel(fontComponent, cursorPanel)) {
            mainPanel.add(cursorPanel, BorderLayout.SOUTH);
        }

        reset();
        return mainPanel;
    }

    private JComponent createCursorPanel() {
        Color borderColor = UIManager.getColor("Component.borderColor");
        if (borderColor == null) {
            borderColor = UIManager.getColor("Separator.foreground");
        }
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor),
            BorderFactory.createEmptyBorder(12, 5, 0, 5)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 0, 8);
        panel.add(new JLabel("Cursor shape:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(cursorShapeCombo, gbc);
        return panel;
    }

    private static boolean attachCursorPanel(@NotNull JComponent root, @NotNull JComponent cursorPanel) {
        JBSplitter splitter = findComponent(root, JBSplitter.class);
        if (splitter == null) {
            return false;
        }
        JComponent firstComponent = splitter.getFirstComponent();
        if (firstComponent == null) {
            return false;
        }

        JPanel wrapped = new JPanel(new BorderLayout(0, 12));
        wrapped.add(firstComponent, BorderLayout.CENTER);
        wrapped.add(cursorPanel, BorderLayout.SOUTH);
        splitter.setFirstComponent(wrapped);
        return true;
    }

    private static <T extends Component> T findComponent(@NotNull Component root, @NotNull Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                T match = findComponent(child, type);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    @Override
    public boolean isModified() {
        TermLabTerminalConfig.State state = TermLabTerminalConfig.getInstance().getState();
        return (fontConfigurable != null && fontConfigurable.isModified())
            || !String.valueOf(cursorShapeCombo.getSelectedItem()).equals(state.cursorShape);
    }

    @Override
    public void apply() throws ConfigurationException {
        if (fontConfigurable != null) {
            fontConfigurable.apply();
        }

        TermLabTerminalConfig config = TermLabTerminalConfig.getInstance();
        TermLabTerminalConfig.State state = config.getState();
        state.cursorShape = String.valueOf(cursorShapeCombo.getSelectedItem());
        config.save();
    }

    @Override
    public void reset() {
        if (fontConfigurable != null) {
            fontConfigurable.reset();
        }

        TermLabTerminalConfig.State state = TermLabTerminalConfig.getInstance().getState();
        cursorShapeCombo.setSelectedItem(state.cursorShape);
    }

    @Override
    public void disposeUIResources() {
        if (fontConfigurable != null) {
            fontConfigurable.disposeUIResources();
            fontConfigurable = null;
        }
        mainPanel = null;
        cursorShapeCombo = null;
    }
}
