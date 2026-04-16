package com.termlab.core.terminal;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public final class TermLabTerminalFileType implements FileType {
    public static final TermLabTerminalFileType INSTANCE = new TermLabTerminalFileType();
    private TermLabTerminalFileType() {}

    @Override public @NotNull String getName() { return "TermLabTerminal"; }
    @Override public @NotNull String getDescription() { return "TermLab Terminal Session"; }
    @Override public @NotNull String getDefaultExtension() { return ""; }
    @Override public @Nullable Icon getIcon() { return AllIcons.Debugger.Console; }
    @Override public boolean isBinary() { return true; }
    @Override public boolean isReadOnly() { return true; }
}
