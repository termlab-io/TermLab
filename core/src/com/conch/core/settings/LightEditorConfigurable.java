package com.conch.core.settings;

import com.conch.core.editor.FirstLaunchEditorNotifier;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Settings subpage that exposes a single checkbox to enable or
 * disable the opt-in light editor plugin. Changes take effect
 * after an IDE restart.
 */
public final class LightEditorConfigurable implements SearchableConfigurable {

    public static final String ID = "conch.workbench.settings.editor";

    private JCheckBox checkbox;
    private JLabel statusLabel;
    private boolean initialEnabled;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Light Editor";
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        checkbox = new JCheckBox("Enable light editor and remote file editing");
        checkbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(checkbox);

        JLabel desc = new JLabel(
            "<html>Allows creating scratch files and editing files from the SFTP panel."
                + " Requires a restart to apply.</html>");
        desc.setAlignmentX(Component.LEFT_ALIGNMENT);
        desc.setBorder(BorderFactory.createEmptyBorder(4, 24, 8, 0));
        panel.add(desc);

        statusLabel = new JLabel(" ");
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        panel.add(statusLabel);

        checkbox.addActionListener(e -> updateStatusLabel());

        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        return checkbox != null && checkbox.isSelected() != initialEnabled;
    }

    @Override
    public void apply() throws ConfigurationException {
        if (checkbox == null) return;
        boolean want = checkbox.isSelected();
        if (want == initialEnabled) return;

        int confirm = Messages.showYesNoDialog(
            (want ? "Enable" : "Disable") + " the light editor? Conch will restart.",
            "Restart Required",
            Messages.getQuestionIcon());
        if (confirm != Messages.YES) {
            checkbox.setSelected(initialEnabled);
            return;
        }
        if (want) {
            FirstLaunchEditorNotifier.enablePluginsAndRestart();
        } else {
            FirstLaunchEditorNotifier.disablePluginsAndRestart();
        }
    }

    @Override
    public void reset() {
        if (checkbox == null) return;
        initialEnabled = FirstLaunchEditorNotifier.isEditorEnabled();
        checkbox.setSelected(initialEnabled);
        updateStatusLabel();
    }

    private void updateStatusLabel() {
        if (checkbox == null || statusLabel == null) return;
        if (checkbox.isSelected() == initialEnabled) {
            statusLabel.setText(initialEnabled ? "Currently enabled." : "Currently disabled.");
        } else {
            statusLabel.setText("Restart required to apply.");
        }
    }
}
