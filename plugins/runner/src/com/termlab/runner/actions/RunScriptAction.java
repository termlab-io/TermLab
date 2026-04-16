package com.termlab.runner.actions;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.termlab.core.terminal.TermLabTerminalVirtualFile;
import com.termlab.runner.actions.SaveBeforeRunHelper.RunTarget;
import com.termlab.runner.config.FileConfigBinding;
import com.termlab.runner.config.InterpreterRegistry;
import com.termlab.runner.config.RunConfig;
import com.termlab.runner.config.RunConfigDialog;
import com.termlab.runner.config.RunConfigStore;
import com.termlab.runner.execution.CommandBuilder;
import com.termlab.runner.execution.LocalExecution;
import com.termlab.runner.execution.RemoteExecution;
import com.termlab.runner.execution.ScriptExecution;
import com.termlab.runner.output.ScriptOutputPanel;
import com.termlab.runner.output.ScriptOutputToolWindowFactory;
import com.termlab.sftp.client.SshSftpSession;
import com.termlab.sftp.client.TermLabSftpConnector;
import com.termlab.sftp.vfs.AtomicSftpWrite;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.client.SshResolvedCredential;
import com.termlab.ssh.client.TermLabServerKeyVerifier;
import com.termlab.ssh.client.TermLabSshClient;
import com.termlab.ssh.credentials.SshCredentialResolver;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.SshHost;
import com.termlab.ssh.model.VaultAuth;
import org.apache.sshd.client.session.ClientSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Run the active file locally or on a configured SSH host.
 */
public final class RunScriptAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(RunScriptAction.class);
    private static final String NOTIFICATION_GROUP = "TermLab Script Runner";

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = activeFile(e);
        boolean enabled = file != null && isLightEditorFile(file);
        e.getPresentation().setVisible(true);
        e.getPresentation().setEnabled(enabled);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile file = activeFile(e);
        if (project == null || file == null) {
            return;
        }

        RunTarget target = SaveBeforeRunHelper.resolve(project, file);
        if (target == null) {
            return;
        }

        VirtualFile currentFile = activeFile(e);
        if (currentFile == null) {
            currentFile = file;
        }
        String filename = currentFile.getName();
        String scriptPath = target.scriptPath();

        RunConfigStore configStore = ApplicationManager.getApplication().getService(RunConfigStore.class);
        FileConfigBinding binding = ApplicationManager.getApplication().getService(FileConfigBinding.class);

        UUID boundConfigId = binding.getConfigId(scriptPath);
        RunConfig config = boundConfigId != null ? configStore.getById(boundConfigId) : null;
        boolean quickRun = config == null;

        if (quickRun) {
            String interpreter = InterpreterRegistry.interpreterForFile(filename);
            if (interpreter == null) {
                RunConfig created = RunConfigDialog.show(project, null, filename, target.sftpHostId(), "bash");
                if (created == null) {
                    return;
                }
                configStore.add(created);
                binding.bind(scriptPath, created.id());
                saveSilently(configStore);
                saveSilently(binding);
                config = created;
                quickRun = false;
            } else {
                config = RunConfig.create(filename, target.sftpHostId(), interpreter, List.of(), null, Map.of(), List.of());
            }
        }

        RunConfig runConfig = config;
        boolean finalQuickRun = quickRun;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ScriptExecution execution = startExecution(runConfig, target);
                String hostLabel = resolveHostLabel(runConfig, target);
                ApplicationManager.getApplication().invokeLater(() -> {
                    showOutput(project, execution, filename, hostLabel, runConfig.interpreter());
                    attachCompletionNotification(project, execution, filename, hostLabel);
                    if (!finalQuickRun) {
                        binding.bind(scriptPath, runConfig.id());
                        saveSilently(binding);
                    } else {
                        notify(project,
                            "Quick run used default settings. Use Edit Run Configuration to customize it.",
                            NotificationType.INFORMATION);
                    }
                });
            } catch (IOException | SshConnectException ex) {
                ApplicationManager.getApplication().invokeLater(() ->
                    notify(project, "Execution failed: " + ex.getMessage(), NotificationType.ERROR));
            }
        });
    }

    private @NotNull ScriptExecution startExecution(
        @NotNull RunConfig config,
        @NotNull RunTarget target
    ) throws IOException, SshConnectException {
        if (config.isLocal() && target.isLocal()) {
            List<String> command = CommandBuilder.buildLocalCommand(config, target.scriptPath());
            return LocalExecution.start(command, config.workingDirectory(), config.envVars(), target.scriptPath());
        }

        UUID hostId = config.hostId() != null ? config.hostId() : target.sftpHostId();
        if (hostId == null) {
            throw new IOException("No SSH host is configured for remote execution");
        }

        HostStore hostStore = ApplicationManager.getApplication().getService(HostStore.class);
        SshHost host = hostStore.findById(hostId);
        if (host == null) {
            throw new IOException("Configured SSH host was not found");
        }

        SshResolvedCredential credential = resolveCredential(host);
        if (credential == null) {
            throw new IOException("Could not resolve credentials for host " + host.label());
        }

        try {
            String remoteScriptPath = target.scriptPath();
            String command;
            if (target.isLocal()) {
                remoteScriptPath = uploadLocalScript(host, credential, Path.of(target.scriptPath()));
                command = buildRemoteCommandWithCleanup(config, remoteScriptPath);
            } else {
                command = CommandBuilder.buildRemoteCommand(config, remoteScriptPath);
            }

            TermLabSshClient sshClient = ApplicationManager.getApplication().getService(TermLabSshClient.class);
            ClientSession session = sshClient.connectSession(host, credential, new TermLabServerKeyVerifier());
            return RemoteExecution.start(session, command);
        } finally {
            credential.close();
        }
    }

    private @NotNull String uploadLocalScript(
        @NotNull SshHost host,
        @NotNull SshResolvedCredential credential,
        @NotNull Path localScriptPath
    ) throws IOException, SshConnectException {
        byte[] content = Files.readAllBytes(localScriptPath);
        String remoteTempPath = buildRemoteTempPath(localScriptPath.getFileName() != null
            ? localScriptPath.getFileName().toString()
            : "script");

        try (SshSftpSession sftpSession = TermLabSftpConnector.open(host, credential, null)) {
            AtomicSftpWrite.writeAtomically(sftpSession.client(), remoteTempPath, content);
        }
        return remoteTempPath;
    }

    private @NotNull String buildRemoteCommandWithCleanup(
        @NotNull RunConfig config,
        @NotNull String remoteScriptPath
    ) {
        String command = CommandBuilder.buildRemoteCommand(config, remoteScriptPath);
        String quotedPath = CommandBuilder.shellQuoteIfNeeded(remoteScriptPath);
        return "tmp=" + quotedPath + "; trap 'rm -f \"$tmp\"' EXIT; " + command;
    }

    private static @NotNull String buildRemoteTempPath(@NotNull String filename) {
        String safeName = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        return "/tmp/termlab-runner-" + UUID.randomUUID() + "-" + safeName;
    }

    private @Nullable SshResolvedCredential resolveCredential(@NotNull SshHost host) {
        if (!(host.auth() instanceof VaultAuth vaultAuth)) {
            return null;
        }
        if (vaultAuth.credentialId() == null) {
            return null;
        }
        SshCredentialResolver resolver = new SshCredentialResolver();
        SshResolvedCredential credential = resolver.resolve(vaultAuth.credentialId(), host.username());
        if (credential != null) {
            return credential;
        }

        AtomicBoolean unlocked = new AtomicBoolean(false);
        ApplicationManager.getApplication().invokeAndWait(() ->
            unlocked.set(resolver.ensureAnyProviderAvailable()));
        if (!unlocked.get()) {
            return null;
        }

        return resolver.resolve(vaultAuth.credentialId(), host.username());
    }

    private @NotNull String resolveHostLabel(@NotNull RunConfig config, @NotNull RunTarget target) {
        if (config.isLocal() && target.isLocal()) {
            return "local";
        }
        UUID hostId = config.hostId() != null ? config.hostId() : target.sftpHostId();
        if (hostId == null) {
            return "remote";
        }
        HostStore hostStore = ApplicationManager.getApplication().getService(HostStore.class);
        SshHost host = hostStore.findById(hostId);
        return host != null ? host.label() : "remote";
    }

    private void showOutput(
        @NotNull Project project,
        @NotNull ScriptExecution execution,
        @NotNull String filename,
        @NotNull String hostLabel,
        @NotNull String interpreter
    ) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ScriptOutputToolWindowFactory.ID);
        if (toolWindow == null) {
            return;
        }
        toolWindow.show(() -> {
            ScriptOutputPanel panel = findOutputPanel(toolWindow);
            if (panel != null) {
                panel.addExecution(execution, filename, hostLabel, interpreter);
            }
        });
    }

    private void attachCompletionNotification(
        @NotNull Project project,
        @NotNull ScriptExecution execution,
        @NotNull String filename,
        @NotNull String hostLabel
    ) {
        execution.addTerminationListener(() -> ApplicationManager.getApplication().invokeLater(() -> {
            Integer exitCode = execution.getExitCode();
            boolean success = exitCode != null && exitCode == 0;
            Notification notification = new Notification(
                NOTIFICATION_GROUP,
                success ? "Script Succeeded" : "Script Failed",
                filename + " on " + hostLabel + (exitCode == null ? "" : " (exit " + exitCode + ")"),
                success ? NotificationType.INFORMATION : NotificationType.ERROR
            );
            notification.addAction(NotificationAction.createSimple("Show Output", () -> {
                revealOutput(project, filename, hostLabel);
                notification.expire();
            }));
            Notifications.Bus.notify(notification, project);
        }));
    }

    private void revealOutput(
        @NotNull Project project,
        @NotNull String filename,
        @NotNull String hostLabel
    ) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ScriptOutputToolWindowFactory.ID);
        if (toolWindow == null) {
            return;
        }
        toolWindow.show(() -> {
            ScriptOutputPanel panel = findOutputPanel(toolWindow);
            if (panel != null) {
                panel.selectExecution(filename, hostLabel);
            }
        });
    }

    private @Nullable ScriptOutputPanel findOutputPanel(@NotNull ToolWindow toolWindow) {
        var content = toolWindow.getContentManager().getContent(0);
        if (content == null) {
            return null;
        }
        return content.getComponent() instanceof ScriptOutputPanel panel ? panel : null;
    }

    private static void saveSilently(@NotNull FileConfigBinding binding) {
        try {
            binding.save();
        } catch (IOException e) {
            LOG.warn("TermLab Runner: failed to save run binding: " + e.getMessage());
        }
    }

    private static void saveSilently(@NotNull RunConfigStore store) {
        try {
            store.save();
        } catch (IOException e) {
            LOG.warn("TermLab Runner: failed to save run configs: " + e.getMessage());
        }
    }

    private static void notify(@NotNull Project project, @NotNull String message, @NotNull NotificationType type) {
        Notifications.Bus.notify(new Notification(NOTIFICATION_GROUP, "Script Runner", message, type), project);
    }

    private static @Nullable VirtualFile activeFile(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return null;
        }
        FileEditor editor = FileEditorManager.getInstance(project).getSelectedEditor();
        return editor != null ? editor.getFile() : null;
    }

    private static boolean isLightEditorFile(@NotNull VirtualFile file) {
        return !(file instanceof TermLabTerminalVirtualFile);
    }

}
