package com.termlab.share.ui;

import com.termlab.share.model.ImportItem;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public final class ConflictResolutionDialog extends DialogWrapper {

    public static final class Result {
        public final @NotNull ImportItem.Action action;
        public final @Nullable String renameTo;
        public final boolean applyToAll;

        public Result(@NotNull ImportItem.Action action, @Nullable String renameTo, boolean applyToAll) {
            this.action = action;
            this.renameTo = renameTo;
            this.applyToAll = applyToAll;
        }
    }

    private final ImportItem item;
    private final JCheckBox applyToAllBox = new JCheckBox("Apply to all remaining conflicts of this type");
    private Result result;

    public ConflictResolutionDialog(@Nullable Project project, @NotNull ImportItem item) {
        super(project, true);
        this.item = item;
        setTitle("Resolve Conflict");
        init();
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(10));
        panel.setPreferredSize(new Dimension(460, 190));

        String message = switch (item.status) {
            case SAME_UUID_EXISTS -> "An item with this ID already exists: " + item.label;
            case LABEL_COLLISION -> "A different item already uses this label: " + item.label;
            default -> item.label;
        };

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(new JLabel("<html>" + message + "</html>"), c);

        c.gridy++;
        c.gridwidth = 1;
        c.weightx = 0;
        JButton skipBtn = new JButton("Skip");
        JButton replaceBtn = new JButton("Replace");
        JButton renameBtn = new JButton("Rename...");
        replaceBtn.setEnabled(item.status == ImportItem.Status.SAME_UUID_EXISTS);
        renameBtn.setEnabled(item.status == ImportItem.Status.LABEL_COLLISION);
        panel.add(skipBtn, c);

        c.gridx = 1;
        panel.add(replaceBtn, c);

        c.gridx = 2;
        panel.add(renameBtn, c);

        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 3;
        panel.add(applyToAllBox, c);

        skipBtn.addActionListener(e -> {
            result = new Result(ImportItem.Action.SKIP, null, applyToAllBox.isSelected());
            close(OK_EXIT_CODE);
        });
        replaceBtn.addActionListener(e -> {
            result = new Result(ImportItem.Action.REPLACE, null, applyToAllBox.isSelected());
            close(OK_EXIT_CODE);
        });
        renameBtn.addActionListener(e -> {
            String newName = Messages.showInputDialog(
                getContentPanel(),
                "New label:",
                "Rename",
                null,
                item.label + " (imported)",
                null
            );
            if (newName == null || newName.isBlank()) {
                return;
            }
            result = new Result(ImportItem.Action.RENAME, newName, applyToAllBox.isSelected());
            close(OK_EXIT_CODE);
        });

        return panel;
    }

    public @Nullable Result getResult() {
        return result;
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[0];
    }
}
