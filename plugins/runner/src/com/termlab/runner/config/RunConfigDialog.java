package com.termlab.runner.config;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.SshHost;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple dialog for creating or editing a run configuration.
 */
public final class RunConfigDialog extends DialogWrapper {

    private static final Pattern TOKEN_PATTERN =
        Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\S+");

    private final @Nullable RunConfig existing;
    private final JTextField nameField = new JTextField(24);
    private final JComboBox<HostItem> hostCombo = new JComboBox<>();
    private final JTextField interpreterField = new JTextField(24);
    private final JTextField interpreterArgsField = new JTextField(24);
    private final JTextField workingDirectoryField = new JTextField(24);
    private final JTextField scriptArgsField = new JTextField(24);
    private final JTextArea envVarsArea = new JTextArea(6, 28);

    private RunConfig result;

    private record HostItem(@Nullable UUID id, @NotNull String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public RunConfigDialog(
        @Nullable Project project,
        @Nullable RunConfig existing,
        @NotNull String defaultName,
        @Nullable UUID defaultHostId,
        @NotNull String defaultInterpreter
    ) {
        super(project, true);
        this.existing = existing;
        setTitle(existing == null ? "Create Run Configuration" : "Edit Run Configuration");
        setOKButtonText(existing == null ? "Create" : "Save");
        populateHosts();
        populateFields(defaultName, defaultHostId, defaultInterpreter);
        init();
    }

    public static @Nullable RunConfig show(
        @Nullable Project project,
        @Nullable RunConfig existing,
        @NotNull String defaultName,
        @Nullable UUID defaultHostId,
        @NotNull String defaultInterpreter
    ) {
        RunConfigDialog dialog = new RunConfigDialog(project, existing, defaultName, defaultHostId, defaultInterpreter);
        return dialog.showAndGet() ? dialog.result : null;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(JBUI.Borders.empty(8));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 0;
        c.gridx = 0;
        c.gridy = 0;

        addRow(form, c, "Name", nameField);
        addRow(form, c, "Host", hostCombo);
        addRow(form, c, "Interpreter", interpreterField);
        addRow(form, c, "Interpreter Args", interpreterArgsField);
        addRow(form, c, "Working Dir", workingDirectoryField);
        addRow(form, c, "Script Args", scriptArgsField);

        envVarsArea.setLineWrap(false);
        envVarsArea.setWrapStyleWord(false);
        JScrollPane envScroll = new JScrollPane(envVarsArea);
        envScroll.setBorder(JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()));

        JPanel envPanel = new JPanel();
        envPanel.setLayout(new BoxLayout(envPanel, BoxLayout.Y_AXIS));
        envPanel.add(new JLabel("Environment"));
        envPanel.add(envScroll);
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        c.gridy++;
        form.add(envPanel, c);

        root.add(form, BorderLayout.CENTER);
        return root;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (nameField.getText().isBlank()) {
            return new ValidationInfo("Name is required", nameField);
        }
        if (interpreterField.getText().isBlank()) {
            return new ValidationInfo("Interpreter is required", interpreterField);
        }
        try {
            parseEnvVars();
        } catch (IllegalArgumentException e) {
            return new ValidationInfo(e.getMessage(), envVarsArea);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        HostItem selectedHost = (HostItem) hostCombo.getSelectedItem();
        UUID hostId = selectedHost != null ? selectedHost.id() : null;
        result = existing == null
            ? RunConfig.create(
                nameField.getText().trim(),
                hostId,
                interpreterField.getText().trim(),
                parseArgs(interpreterArgsField.getText()),
                trimToNull(workingDirectoryField.getText()),
                parseEnvVars(),
                parseArgs(scriptArgsField.getText())
            )
            : existing.withEdited(
                nameField.getText().trim(),
                hostId,
                interpreterField.getText().trim(),
                parseArgs(interpreterArgsField.getText()),
                trimToNull(workingDirectoryField.getText()),
                parseEnvVars(),
                parseArgs(scriptArgsField.getText())
            );
        super.doOKAction();
    }

    private void populateHosts() {
        DefaultComboBoxModel<HostItem> model = new DefaultComboBoxModel<>();
        model.addElement(new HostItem(null, "Local"));
        if (ApplicationManager.getApplication() != null) {
            HostStore hostStore = ApplicationManager.getApplication().getService(HostStore.class);
            if (hostStore != null) {
                for (SshHost host : hostStore.getHosts()) {
                    model.addElement(new HostItem(host.id(), host.label()));
                }
            }
        }
        hostCombo.setModel(model);
    }

    private void populateFields(
        @NotNull String defaultName,
        @Nullable UUID defaultHostId,
        @NotNull String defaultInterpreter
    ) {
        RunConfig source = existing;
        if (source == null) {
            nameField.setText(defaultName);
            selectHost(defaultHostId);
            interpreterField.setText(defaultInterpreter);
            return;
        }

        nameField.setText(source.name());
        selectHost(source.hostId());
        interpreterField.setText(source.interpreter());
        interpreterArgsField.setText(String.join(" ", source.args()));
        workingDirectoryField.setText(source.workingDirectory() == null ? "" : source.workingDirectory());
        scriptArgsField.setText(String.join(" ", source.scriptArgs()));
        envVarsArea.setText(formatEnvVars(source.envVars()));
    }

    private void selectHost(@Nullable UUID hostId) {
        for (int i = 0; i < hostCombo.getItemCount(); i++) {
            HostItem item = hostCombo.getItemAt(i);
            if ((hostId == null && item.id() == null)
                || (hostId != null && hostId.equals(item.id()))) {
                hostCombo.setSelectedIndex(i);
                return;
            }
        }
        hostCombo.setSelectedIndex(0);
    }

    private static void addRow(JPanel panel, GridBagConstraints c, String label, JComponent component) {
        c.gridwidth = 1;
        c.weightx = 0;
        c.gridx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(component, c);
        c.gridy++;
    }

    private static @NotNull List<String> parseArgs(@NotNull String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.trim());
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 2) {
                char first = token.charAt(0);
                char last = token.charAt(token.length() - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    token = token.substring(1, token.length() - 1);
                }
            }
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private @NotNull Map<String, String> parseEnvVars() {
        Map<String, String> envVars = new TreeMap<>();
        String[] lines = envVarsArea.getText().split("\\R");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("Environment lines must use KEY=VALUE");
            }
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Environment variable name cannot be empty");
            }
            envVars.put(key, value);
        }
        return Map.copyOf(envVars);
    }

    private static @NotNull String formatEnvVars(@NotNull Map<String, String> envVars) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
