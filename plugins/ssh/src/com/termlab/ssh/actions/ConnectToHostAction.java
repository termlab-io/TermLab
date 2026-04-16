package com.termlab.ssh.actions;

import com.termlab.core.terminal.TermLabTerminalVirtualFile;
import com.termlab.ssh.model.SshHost;
import com.termlab.ssh.provider.SshSessionContext;
import com.termlab.ssh.provider.SshSessionProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * The code path that opens a terminal tab for an SSH session. Not an
 * {@link com.intellij.openapi.actionSystem.AnAction} — callers pass the
 * host explicitly instead of picking it up from a data context.
 *
 * <p>Invokers:
 * <ul>
 *   <li>{@code HostsToolWindow} — double-click or "Connect" button</li>
 *   <li>{@code HostsPaletteContributor} — palette entry selection</li>
 *   <li>{@link NewSshSessionAction} — after the Cmd+K host picker</li>
 * </ul>
 *
 * <p>Flow:
 * <ol>
 *   <li>Construct a {@link TermLabTerminalVirtualFile} with the
 *       {@link SshSessionProvider} and stash an {@link SshSessionContext}
 *       carrying the target host on the file.</li>
 *   <li>Ask {@link FileEditorManager} to open the file. That triggers
 *       {@code TermLabTerminalEditor.initTerminalSession()} which reads
 *       {@code file.getSessionContext()} → sees the SSH context → calls
 *       {@code SshSessionProvider.createSession()} with it → connects
 *       via MINA (inside {@code Task.Modal}, off the EDT) → hands back
 *       a {@code TtyConnector} → JediTerm renders the remote shell.</li>
 * </ol>
 */
public final class ConnectToHostAction {

    private ConnectToHostAction() {}

    /**
     * Open a new terminal tab connected to {@code host}. Must be called
     * on the EDT.
     */
    public static void run(@NotNull Project project, @NotNull SshHost host) {
        ApplicationManager.getApplication().assertIsDispatchThread();

        TermLabTerminalVirtualFile file = new TermLabTerminalVirtualFile(
            host.label(),
            new SshSessionProvider());
        file.setSessionContext(new SshSessionContext(host));
        // Pre-populate the title so the tab shows something sensible
        // even before the shell's OSC 0/2 takes over.
        file.setTerminalTitle(host.label());

        FileEditorManager.getInstance(project).openFile(file, /* focusEditor = */ true);
    }
}
