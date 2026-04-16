package com.termlab.core.settings;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public final class TermLabTerminalTerminalConfigurable implements SearchableConfigurable {

    public static final String ID = "termlab.terminal.settings.terminal";

    private JPanel mainPanel;
    private JTextField shellProgramField;
    private JTextField shellArgumentsField;
    private JSpinner scrollbackSpinner;
    private JCheckBox copyOnSelectCheck;
    private JCheckBox audibleBellCheck;
    private JCheckBox mouseReportingCheck;

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
        mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        shellProgramField = new JTextField();
        addLabeledRow(gbc, row++, "Program:", shellProgramField,
            "(empty = use $SHELL / %COMSPEC%, platform default otherwise)");

        shellArgumentsField = new JTextField();
        addLabeledRow(gbc, row++, "Arguments:", shellArgumentsField,
            "(example: -l or --noprofile --norc)");

        scrollbackSpinner = new JSpinner(new SpinnerNumberModel(10000, 1000, 100000, 1000));
        addLabeledRow(gbc, row++, "Scrollback lines:", scrollbackSpinner, null);

        copyOnSelectCheck = new JCheckBox("Copy text on selection");
        addCheckboxRow(gbc, row++, copyOnSelectCheck);

        audibleBellCheck = new JCheckBox("Audible bell");
        addCheckboxRow(gbc, row++, audibleBellCheck);

        mouseReportingCheck = new JCheckBox("Enable mouse reporting");
        addCheckboxRow(gbc, row++, mouseReportingCheck);

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
        TermLabTerminalConfig.State state = TermLabTerminalConfig.getInstance().getState();

        return !normalize(shellProgramField.getText()).equals(normalize(state.shellProgram))
            || !normalize(shellArgumentsField.getText()).equals(normalize(state.shellArguments))
            || (int) scrollbackSpinner.getValue() != state.scrollbackLines
            || copyOnSelectCheck.isSelected() != state.copyOnSelect
            || audibleBellCheck.isSelected() != state.audibleBell
            || mouseReportingCheck.isSelected() != state.enableMouseReporting;
    }

    @Override
    public void apply() throws ConfigurationException {
        TermLabTerminalConfig config = TermLabTerminalConfig.getInstance();
        TermLabTerminalConfig.State state = config.getState();

        state.shellProgram = normalize(shellProgramField.getText());
        state.shellArguments = normalize(shellArgumentsField.getText());
        state.scrollbackLines = (int) scrollbackSpinner.getValue();
        state.copyOnSelect = copyOnSelectCheck.isSelected();
        state.audibleBell = audibleBellCheck.isSelected();
        state.enableMouseReporting = mouseReportingCheck.isSelected();

        config.save();
    }

    @Override
    public void reset() {
        TermLabTerminalConfig.State state = TermLabTerminalConfig.getInstance().getState();

        shellProgramField.setText(normalize(state.shellProgram));
        shellArgumentsField.setText(normalize(state.shellArguments));
        scrollbackSpinner.setValue(state.scrollbackLines);
        copyOnSelectCheck.setSelected(state.copyOnSelect);
        audibleBellCheck.setSelected(state.audibleBell);
        mouseReportingCheck.setSelected(state.enableMouseReporting);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
