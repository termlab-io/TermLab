package com.conch.core.terminal;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public final class ConchTerminalFileType implements FileType {
    public static final ConchTerminalFileType INSTANCE = new ConchTerminalFileType();
    private ConchTerminalFileType() {}

    @Override public @NotNull String getName() { return "ConchTerminal"; }
    @Override public @NotNull String getDescription() { return "Conch Terminal Session"; }
    @Override public @NotNull String getDefaultExtension() { return ""; }
    @Override public @Nullable Icon getIcon() { return AllIcons.Debugger.Console; }
    @Override public boolean isBinary() { return true; }
    @Override public boolean isReadOnly() { return true; }
}
