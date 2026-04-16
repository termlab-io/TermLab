package com.termlab.core.settings;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public final class TermLabTerminalAppearanceConfigurable implements SearchableConfigurable {

    public static final String ID = "termlab.terminal.settings.appearance";

    private JPanel mainPanel;
    private JComboBox<String> fontFamilyCombo;
    private JSpinner fontSizeSpinner;
    private JSpinner lineSpacingSpinner;
    private JSpinner charSpacingSpinner;
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
        mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        fontFamilyCombo = new JComboBox<>();
        fontFamilyCombo.setEditable(true);
        fontFamilyCombo.addItem("");  // Auto-detect
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (String name : ge.getAvailableFontFamilyNames()) {
            Font f = new Font(name, Font.PLAIN, 12);
            if (f.canDisplay('M') && f.canDisplay('i')) {
                fontFamilyCombo.addItem(name);
            }
        }
        addLabeledRow(gbc, row++, "Font family:", fontFamilyCombo,
            "(empty = auto: JetBrains Mono, Menlo, Monaco...)");

        fontSizeSpinner = new JSpinner(new SpinnerNumberModel(14, 8, 48, 1));
        addLabeledRow(gbc, row++, "Font size:", fontSizeSpinner, null);

        lineSpacingSpinner = new JSpinner(new SpinnerNumberModel(1.0, 0.5, 3.0, 0.05));
        addLabeledRow(gbc, row++, "Line spacing:", lineSpacingSpinner, "(1.0 = normal)");

        charSpacingSpinner = new JSpinner(new SpinnerNumberModel(0.0, -0.5, 1.0, 0.01));
        addLabeledRow(gbc, row++, "Character spacing:", charSpacingSpinner, "(0.0 = normal)");

        cursorShapeCombo = new JComboBox<>(new String[]{"BLOCK", "UNDERLINE", "VERTICAL_BAR"});
        addLabeledRow(gbc, row++, "Cursor shape:", cursorShapeCombo, null);

        gbc.gridy = row;
        gbc.weighty = 1.0;
        mainPanel.add(Box.createVerticalGlue(), gbc);

        reset();
        return mainPanel;
    }

    private void addLabeledRow(GridBagConstraints gbc, int row, String label,
                               JComponent field, String tooltip) {
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.weightx = 0;
        gbc.weighty = 0;
        mainPanel.add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.5;
        mainPanel.add(field, gbc);

        if (tooltip != null) {
            gbc.gridx = 2;
            gbc.weightx = 0.5;
            JLabel hint = new JLabel(tooltip);
            hint.setForeground(UIManager.getColor("Label.disabledForeground"));
            hint.setFont(hint.getFont().deriveFont(Font.ITALIC, hint.getFont().getSize() - 1f));
            mainPanel.add(hint, gbc);
        }
    }

    @Override
    public boolean isModified() {
        TermLabTerminalConfig.State state = TermLabTerminalConfig.getInstance().getState();

        return !getSelectedFont().equals(state.fontFamily)
            || (int) fontSizeSpinner.getValue() != state.fontSize
            || floatsDiffer((double) lineSpacingSpinner.getValue(), state.lineSpacing)
            || floatsDiffer((double) charSpacingSpinner.getValue(), state.characterSpacing)
            || !String.valueOf(cursorShapeCombo.getSelectedItem()).equals(state.cursorShape);
    }

    @Override
    public void apply() throws ConfigurationException {
        TermLabTerminalConfig config = TermLabTerminalConfig.getInstance();
        TermLabTerminalConfig.State state = config.getState();

        state.fontFamily = getSelectedFont();
        state.fontSize = (int) fontSizeSpinner.getValue();
        state.lineSpacing = ((Number) lineSpacingSpinner.getValue()).floatValue();
        state.characterSpacing = ((Number) charSpacingSpinner.getValue()).floatValue();
        state.cursorShape = String.valueOf(cursorShapeCombo.getSelectedItem());

        config.save();
    }

    @Override
    public void reset() {
        TermLabTerminalConfig.State state = TermLabTerminalConfig.getInstance().getState();

        fontFamilyCombo.setSelectedItem(state.fontFamily);
        fontSizeSpinner.setValue(state.fontSize);
        lineSpacingSpinner.setValue((double) state.lineSpacing);
        charSpacingSpinner.setValue((double) state.characterSpacing);
        cursorShapeCombo.setSelectedItem(state.cursorShape);
    }

    private String getSelectedFont() {
        Object item = fontFamilyCombo.getSelectedItem();
        return item == null ? "" : item.toString();
    }

    private static boolean floatsDiffer(double a, float b) {
        return Math.abs(a - b) > 0.001;
    }
}
