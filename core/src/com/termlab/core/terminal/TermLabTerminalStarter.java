package com.termlab.core.terminal;

import com.jediterm.core.typeahead.TerminalTypeAheadManager;
import com.jediterm.terminal.TerminalDataStream;
import com.jediterm.terminal.TerminalExecutorServiceManager;
import com.jediterm.terminal.TerminalStarter;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.model.JediTerminal;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * TerminalStarter that writes terminal-generated responses (DA, DSR, cursor
 * position reports, etc.) synchronously from the emulator thread.
 *
 * <p>JediTerm's default {@link TerminalStarter#sendBytes} dispatches all writes
 * through a single-threaded executor. For terminal-generated responses (where
 * {@code userInput=false}) we write straight to the {@link TtyConnector} on the
 * emulator thread so the reply lands in the PTY before the next byte is
 * processed, and so {@link OscTrackingTtyConnector} can correlate it with the
 * matching query it just observed in the read stream. User input still goes
 * through the executor so type-ahead handling is preserved.
 */
public final class TermLabTerminalStarter extends TerminalStarter {

    private final @Nullable Project project;
    private final @Nullable TermLabTerminalVirtualFile sourceFile;
    private final TtyConnector ttyConnector;

    public TermLabTerminalStarter(@NotNull JediTerminal terminal,
                                @Nullable Project project,
                                @Nullable TermLabTerminalVirtualFile sourceFile,
                                @NotNull TtyConnector ttyConnector,
                                @NotNull TerminalDataStream dataStream,
                                @NotNull TerminalTypeAheadManager typeAheadManager,
                                @NotNull TerminalExecutorServiceManager executorServiceManager) {
        super(terminal, ttyConnector, dataStream, typeAheadManager, executorServiceManager);
        this.project = project;
        this.sourceFile = sourceFile;
        this.ttyConnector = ttyConnector;
    }

    @Override
    public void sendBytes(byte @NotNull [] bytes, boolean userInput) {
        if (userInput) {
            broadcastBytes(bytes);
            super.sendBytes(bytes, userInput);
            return;
        }
        try {
            ttyConnector.write(bytes);
        } catch (IOException e) {
            super.sendBytes(bytes, userInput);
        }
    }

    @Override
    public void sendString(@NotNull String string, boolean userInput) {
        if (userInput) {
            broadcastString(string);
            super.sendString(string, userInput);
            return;
        }
        try {
            ttyConnector.write(string);
        } catch (IOException e) {
            super.sendString(string, userInput);
        }
    }

    private void broadcastBytes(byte @NotNull [] bytes) {
        if (project == null || sourceFile == null || project.isDisposed()) {
            return;
        }
        TermLabMultiExecManager.getInstance(project).broadcastBytes(sourceFile, bytes);
    }

    private void broadcastString(@NotNull String string) {
        if (project == null || sourceFile == null || project.isDisposed()) {
            return;
        }
        TermLabMultiExecManager.getInstance(project).broadcastString(sourceFile, string);
    }
}
