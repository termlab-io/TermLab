package com.conch.sftp.transfer;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.DecimalFormat;

/**
 * FileZilla-style file-collision prompt. Shown when a transfer's
 * destination already exists; returns the user's choice for this
 * one file. The {@code *_ALL} variants are promoted to latched
 * decisions by {@link CollisionResolver}.
 *
 * <p>Use {@link #prompt(String, long, long)} from any thread — the
 * call marshals itself onto the EDT via
 * {@link com.intellij.openapi.application.Application#invokeAndWait}.
 */
public final class CollisionDialog extends DialogWrapper {

    private static final DecimalFormat SIZE_FORMAT = new DecimalFormat("#,##0");

    private final String destination;
    private final long sourceSize;
    private final long existingSize;
    private @Nullable CollisionDecision choice;

    private CollisionDialog(@NotNull String destination, long sourceSize, long existingSize) {
        super((Project) null, true);
        this.destination = destination;
        this.sourceSize = sourceSize;
        this.existingSize = existingSize;
        setTitle("File Already Exists");
        setResizable(false);
        init();
    }

    /**
     * Blocking prompt — safe to call from a background thread.
     */
    public static @NotNull CollisionDecision prompt(
        @NotNull String destination,
        long sourceSize,
        long existingSize
    ) {
        CollisionDecision[] out = {CollisionDecision.CANCEL};
        ApplicationManager.getApplication().invokeAndWait(() -> {
            CollisionDialog dialog = new CollisionDialog(destination, sourceSize, existingSize);
            dialog.show();
            out[0] = dialog.choice != null ? dialog.choice : CollisionDecision.CANCEL;
        });
        return out[0];
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(new EmptyBorder(JBUI.insets(12)));

        JLabel intro = new JLabel("<html>The destination already exists:<br><b>" + escape(destination) + "</b></html>");
        panel.add(intro, BorderLayout.NORTH);

        JPanel sizes = new JPanel(new GridLayout(2, 2, 12, 4));
        sizes.add(new JLabel("Incoming size:"));
        sizes.add(new JLabel(SIZE_FORMAT.format(sourceSize) + " bytes"));
        sizes.add(new JLabel("Existing size:"));
        sizes.add(new JLabel(SIZE_FORMAT.format(existingSize) + " bytes"));
        panel.add(sizes, BorderLayout.CENTER);

        JLabel question = new JLabel("What would you like to do?");
        question.setBorder(new EmptyBorder(JBUI.insetsTop(4)));
        panel.add(question, BorderLayout.SOUTH);

        return panel;
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{
            makeAction("Overwrite", CollisionDecision.OVERWRITE),
            makeAction("Overwrite All", CollisionDecision.OVERWRITE_ALL),
            makeAction("Rename", CollisionDecision.RENAME),
            makeAction("Skip", CollisionDecision.SKIP),
            makeAction("Skip All", CollisionDecision.SKIP_ALL),
            makeAction("Cancel", CollisionDecision.CANCEL),
        };
    }

    private @NotNull Action makeAction(@NotNull String label, @NotNull CollisionDecision decision) {
        return new AbstractAction(label) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                choice = decision;
                if (decision == CollisionDecision.CANCEL) {
                    doCancelAction();
                } else {
                    close(OK_EXIT_CODE);
                }
            }
        };
    }

    private static @NotNull String escape(@NotNull String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
