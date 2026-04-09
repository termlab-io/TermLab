package com.conch.core.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.ColorPanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public final class ConchTerminalConfigurable implements Configurable {

    private JPanel mainPanel;
    private JComboBox<String> fontFamilyCombo;
    private JSpinner fontSizeSpinner;
    private JSpinner lineSpacingSpinner;
    private JSpinner charSpacingSpinner;
    private JComboBox<String> cursorShapeCombo;
    private ColorPanel foregroundColor;
    private ColorPanel backgroundColor;
    private ColorPanel selectionFgColor;
    private ColorPanel selectionBgColor;
    private JSpinner scrollbackSpinner;
    private JCheckBox copyOnSelectCheck;
    private JCheckBox audibleBellCheck;
    private JCheckBox mouseReportingCheck;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Conch Terminal";
    }

    @Override
    public @Nullable JComponent createComponent() {
        mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        // === Font Section ===
        addSectionHeader(gbc, row++, "Font");

        // Font Family
        fontFamilyCombo = new JComboBox<>();
        fontFamilyCombo.setEditable(true);
        fontFamilyCombo.addItem("");  // Auto-detect
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (String name : ge.getAvailableFontFamilyNames()) {
            Font f = new Font(name, Font.PLAIN, 12);
            if (f.canDisplay('M') && f.canDisplay('i')) {
                // Include fonts that can display basic ASCII
                fontFamilyCombo.addItem(name);
            }
        }
        addLabeledRow(gbc, row++, "Font family:", fontFamilyCombo,
            "(empty = auto: JetBrains Mono, Menlo, Monaco...)");

        // Font Size
        fontSizeSpinner = new JSpinner(new SpinnerNumberModel(14, 8, 48, 1));
        addLabeledRow(gbc, row++, "Font size:", fontSizeSpinner, null);

        // Line Spacing
        lineSpacingSpinner = new JSpinner(new SpinnerNumberModel(1.0, 0.8, 2.0, 0.05));
        addLabeledRow(gbc, row++, "Line spacing:", lineSpacingSpinner, "(1.0 = normal)");

        // Character Spacing
        charSpacingSpinner = new JSpinner(new SpinnerNumberModel(0.0, -0.1, 0.5, 0.01));
        addLabeledRow(gbc, row++, "Character spacing:", charSpacingSpinner, "(0.0 = normal)");

        // === Cursor Section ===
        addSectionHeader(gbc, row++, "Cursor");

        cursorShapeCombo = new JComboBox<>(new String[]{"BLOCK", "UNDERLINE", "VERTICAL_BAR"});
        addLabeledRow(gbc, row++, "Cursor shape:", cursorShapeCombo, null);

        // === Colors Section ===
        addSectionHeader(gbc, row++, "Colors");

        foregroundColor = new ColorPanel();
        addLabeledRow(gbc, row++, "Foreground:", foregroundColor, null);

        backgroundColor = new ColorPanel();
        addLabeledRow(gbc, row++, "Background:", backgroundColor, null);

        selectionFgColor = new ColorPanel();
        addLabeledRow(gbc, row++, "Selection foreground:", selectionFgColor, null);

        selectionBgColor = new ColorPanel();
        addLabeledRow(gbc, row++, "Selection background:", selectionBgColor, null);

        // === Behavior Section ===
        addSectionHeader(gbc, row++, "Behavior");

        scrollbackSpinner = new JSpinner(new SpinnerNumberModel(10000, 1000, 100000, 1000));
        addLabeledRow(gbc, row++, "Scrollback lines:", scrollbackSpinner, null);

        copyOnSelectCheck = new JCheckBox("Copy text on selection");
        addCheckboxRow(gbc, row++, copyOnSelectCheck);

        audibleBellCheck = new JCheckBox("Audible bell");
        addCheckboxRow(gbc, row++, audibleBellCheck);

        mouseReportingCheck = new JCheckBox("Enable mouse reporting");
        addCheckboxRow(gbc, row++, mouseReportingCheck);

        // Spacer
        gbc.gridy = row;
        gbc.weighty = 1.0;
        mainPanel.add(Box.createVerticalGlue(), gbc);

        reset();
        return mainPanel;
    }

    private void addSectionHeader(GridBagConstraints gbc, int row, String title) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        gbc.weighty = 0;
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        mainPanel.add(label, gbc);
        gbc.gridwidth = 1;
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

    private void addCheckboxRow(GridBagConstraints gbc, int row, JCheckBox checkBox) {
        gbc.gridy = row;
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.weighty = 0;
        gbc.gridwidth = 2;
        mainPanel.add(checkBox, gbc);
        gbc.gridwidth = 1;
    }

    @Override
    public boolean isModified() {
        ConchTerminalConfig.State state = ConchTerminalConfig.getInstance().getState();

        return !getSelectedFont().equals(state.fontFamily)
            || (int) fontSizeSpinner.getValue() != state.fontSize
            || floatsDiffer((double) lineSpacingSpinner.getValue(), state.lineSpacing)
            || floatsDiffer((double) charSpacingSpinner.getValue(), state.characterSpacing)
            || !String.valueOf(cursorShapeCombo.getSelectedItem()).equals(state.cursorShape)
            || !colorToHex(foregroundColor.getSelectedColor()).equals(state.foreground)
            || !colorToHex(backgroundColor.getSelectedColor()).equals(state.background)
            || !colorToHex(selectionFgColor.getSelectedColor()).equals(state.selectionForeground)
            || !colorToHex(selectionBgColor.getSelectedColor()).equals(state.selectionBackground)
            || (int) scrollbackSpinner.getValue() != state.scrollbackLines
            || copyOnSelectCheck.isSelected() != state.copyOnSelect
            || audibleBellCheck.isSelected() != state.audibleBell
            || mouseReportingCheck.isSelected() != state.enableMouseReporting;
    }

    @Override
    public void apply() throws ConfigurationException {
        ConchTerminalConfig config = ConchTerminalConfig.getInstance();
        ConchTerminalConfig.State state = config.getState();

        state.fontFamily = getSelectedFont();
        state.fontSize = (int) fontSizeSpinner.getValue();
        state.lineSpacing = ((Number) lineSpacingSpinner.getValue()).floatValue();
        state.characterSpacing = ((Number) charSpacingSpinner.getValue()).floatValue();
        state.cursorShape = String.valueOf(cursorShapeCombo.getSelectedItem());
        state.foreground = colorToHex(foregroundColor.getSelectedColor());
        state.background = colorToHex(backgroundColor.getSelectedColor());
        state.selectionForeground = colorToHex(selectionFgColor.getSelectedColor());
        state.selectionBackground = colorToHex(selectionBgColor.getSelectedColor());
        state.scrollbackLines = (int) scrollbackSpinner.getValue();
        state.copyOnSelect = copyOnSelectCheck.isSelected();
        state.audibleBell = audibleBellCheck.isSelected();
        state.enableMouseReporting = mouseReportingCheck.isSelected();

        // Save to disk immediately
        config.save();
    }

    @Override
    public void reset() {
        ConchTerminalConfig.State state = ConchTerminalConfig.getInstance().getState();

        fontFamilyCombo.setSelectedItem(state.fontFamily);
        fontSizeSpinner.setValue(state.fontSize);
        lineSpacingSpinner.setValue((double) state.lineSpacing);
        charSpacingSpinner.setValue((double) state.characterSpacing);
        cursorShapeCombo.setSelectedItem(state.cursorShape);
        foregroundColor.setSelectedColor(hexToColor(state.foreground));
        backgroundColor.setSelectedColor(hexToColor(state.background));
        selectionFgColor.setSelectedColor(hexToColor(state.selectionForeground));
        selectionBgColor.setSelectedColor(hexToColor(state.selectionBackground));
        scrollbackSpinner.setValue(state.scrollbackLines);
        copyOnSelectCheck.setSelected(state.copyOnSelect);
        audibleBellCheck.setSelected(state.audibleBell);
        mouseReportingCheck.setSelected(state.enableMouseReporting);
    }

    private String getSelectedFont() {
        Object item = fontFamilyCombo.getSelectedItem();
        return item == null ? "" : item.toString();
    }

    private static String colorToHex(Color c) {
        if (c == null) return "#BBBBBB";
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static boolean floatsDiffer(double a, float b) {
        return Math.abs(a - b) > 0.001;
    }

    private static Color hexToColor(String hex) {
        try {
            return Color.decode(hex);
        } catch (Exception e) {
            return Color.GRAY;
        }
    }
}
