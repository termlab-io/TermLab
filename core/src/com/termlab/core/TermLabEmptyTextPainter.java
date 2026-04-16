package com.termlab.core;

import com.intellij.openapi.fileEditor.impl.EditorEmptyTextPainter;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Custom empty editor text for TermLab — shows only terminal-relevant actions.
 * Removes "Go to File", "Recent Files", and "Drop files here" which are IDE
 * features not supported in a terminal workstation.
 */
public final class TermLabEmptyTextPainter extends EditorEmptyTextPainter {

    @Override
    protected void advertiseActions(@NotNull JComponent splitters, @NotNull UIUtil.TextPainter painter) {
        appendSearchEverywhere(painter);
        appendAction(painter, "New Terminal Tab", getActionShortcutText("TermLab.NewTerminalTab"));
    }

    @Override
    protected void appendDnd(@NotNull UIUtil.TextPainter painter) {
        // No drag-and-drop support in terminal workstation
    }
}
