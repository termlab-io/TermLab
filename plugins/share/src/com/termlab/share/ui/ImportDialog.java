package com.termlab.share.ui;

import com.termlab.share.codec.ShareBundleCodec;
import com.termlab.share.codec.exceptions.BundleCorruptedException;
import com.termlab.share.codec.exceptions.UnsupportedBundleVersionException;
import com.termlab.share.codec.exceptions.WrongBundlePasswordException;
import com.termlab.share.model.ImportItem;
import com.termlab.share.model.ShareBundle;
import com.termlab.share.planner.CurrentState;
import com.termlab.share.planner.ImportExecutor;
import com.termlab.share.planner.ImportPaths;
import com.termlab.share.planner.ImportPlan;
import com.termlab.share.planner.ImportPlanner;
import com.termlab.share.planner.ImportResult;
import com.termlab.ssh.model.HostStore;
import com.termlab.tunnels.model.TunnelStore;
import com.termlab.vault.lock.LockManager;
import com.termlab.vault.ui.UnlockDialog;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ImportDialog {

    private final @Nullable Project project;

    public ImportDialog(@Nullable Project project) {
        this.project = project;
    }

    public void run() {
        VirtualFile file = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
            project,
            null
        );
        if (file == null) {
            return;
        }

        Path bundlePath = file.toNioPath();
        ShareBundle bundle = promptAndDecodeBundle(bundlePath, file.getName());
        if (bundle == null) {
            return;
        }

        LockManager lockManager = ApplicationManager.getApplication().getService(LockManager.class);
        byte[] vaultPassword = null;
        try {
            if (bundle.metadata().includesCredentials() && lockManager != null) {
                vaultPassword = setupVaultForCredentialImport(lockManager);
                if (vaultPassword == null) {
                    return;
                }
            }

            CurrentState state = loadCurrentState(lockManager);
            ImportPlan plan = ImportPlanner.plan(bundle, state);
            List<ImportItem> resolved = resolveConflicts(plan.items());
            if (resolved == null) {
                return;
            }

            ImportPaths paths = defaultPaths(lockManager);
            ImportResult result = ImportExecutor.execute(
                bundle,
                new ImportPlan(resolved),
                paths,
                lockManager,
                vaultPassword
            );
            reloadStores();
            Messages.showInfoMessage(project, result.summary(), "Import Complete");
        } catch (Exception e) {
            Messages.showErrorDialog(project, "Import failed: " + e.getMessage(), "Import Failed");
        } finally {
            PasswordUtil.zero(vaultPassword);
        }
    }

    private @Nullable ShareBundle promptAndDecodeBundle(Path bundlePath, String displayName) {
        while (true) {
            String password = Messages.showPasswordDialog(
                project,
                "Bundle password:",
                "Open " + displayName,
                null
            );
            if (password == null) {
                return null;
            }
            byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
            try {
                byte[] bytes = Files.readAllBytes(bundlePath);
                return ShareBundleCodec.decode(bytes, passwordBytes);
            } catch (WrongBundlePasswordException e) {
                Messages.showErrorDialog(project, "Incorrect password.", "Bundle Locked");
            } catch (BundleCorruptedException e) {
                Messages.showErrorDialog(project, "Not a valid TermLab bundle: " + e.getMessage(), "Invalid Bundle");
                return null;
            } catch (UnsupportedBundleVersionException e) {
                Messages.showErrorDialog(
                    project,
                    "This bundle was created by a newer TermLab version (" + e.version() + ").",
                    "Unsupported Bundle Version"
                );
                return null;
            } catch (Exception e) {
                Messages.showErrorDialog(project, "Could not read bundle: " + e.getMessage(), "Error");
                return null;
            } finally {
                PasswordUtil.zero(passwordBytes);
            }
        }
    }

    private byte @Nullable [] setupVaultForCredentialImport(@NotNull LockManager lockManager) throws Exception {
        if (!Files.isRegularFile(lockManager.getVaultPath())) {
            String pw = Messages.showPasswordDialog(
                project,
                "This bundle includes credentials. Set a master password for a new local vault:",
                "Create Vault",
                null
            );
            if (pw == null) {
                return null;
            }
            byte[] bytes = pw.getBytes(StandardCharsets.UTF_8);
            lockManager.createVault(bytes);
            return bytes;
        }

        if (lockManager.isLocked()) {
            UnlockDialog unlockDialog = new UnlockDialog(project, lockManager);
            if (!unlockDialog.showAndGet()) {
                return null;
            }
        }

        // Vault is already unlocked. withUnlocked(...) won't use these bytes.
        return new byte[0];
    }

    private CurrentState loadCurrentState(@Nullable LockManager lockManager) {
        HostStore hostStore = ApplicationManager.getApplication().getService(HostStore.class);
        TunnelStore tunnelStore = ApplicationManager.getApplication().getService(TunnelStore.class);

        List<com.termlab.ssh.model.SshHost> hosts =
            hostStore != null ? hostStore.getHosts() : List.of();
        List<com.termlab.tunnels.model.SshTunnel> tunnels =
            tunnelStore != null ? tunnelStore.getTunnels() : List.of();
        return new CurrentState(hosts, tunnels, lockManager != null ? lockManager.getVault() : null);
    }

    private @Nullable List<ImportItem> resolveConflicts(List<ImportItem> items) {
        ImportItem.Action sameIdAction = null;
        String sameIdRename = null;
        ImportItem.Action collisionAction = null;
        String collisionRename = null;

        List<ImportItem> out = new ArrayList<>(items.size());
        for (ImportItem item : items) {
            if (item.status == ImportItem.Status.NEW || item.status == ImportItem.Status.REFERENCE_BROKEN) {
                out.add(item);
                continue;
            }

            if (item.status == ImportItem.Status.SAME_UUID_EXISTS && sameIdAction != null) {
                item.action = sameIdAction;
                item.renameTo = sameIdRename;
                out.add(item);
                continue;
            }
            if (item.status == ImportItem.Status.LABEL_COLLISION && collisionAction != null) {
                item.action = collisionAction;
                item.renameTo = collisionRename;
                out.add(item);
                continue;
            }

            ConflictResolutionDialog dialog = new ConflictResolutionDialog(project, item);
            if (!dialog.showAndGet()) {
                return null;
            }
            ConflictResolutionDialog.Result result = dialog.getResult();
            if (result == null) {
                return null;
            }
            item.action = result.action;
            item.renameTo = result.renameTo;

            if (result.applyToAll) {
                if (item.status == ImportItem.Status.SAME_UUID_EXISTS) {
                    sameIdAction = result.action;
                    sameIdRename = result.renameTo;
                } else {
                    collisionAction = result.action;
                    collisionRename = result.renameTo;
                }
            }

            out.add(item);
        }
        return out;
    }

    private ImportPaths defaultPaths(@Nullable LockManager lockManager) {
        Path base = Path.of(System.getProperty("user.home"), ".config", "termlab");
        Path vaultPath = lockManager != null ? lockManager.getVaultPath() : base.resolve("vault.enc");
        return new ImportPaths(
            base.resolve("ssh-hosts.json"),
            base.resolve("tunnels.json"),
            vaultPath,
            base.resolve("imported-keys")
        );
    }

    private void reloadStores() {
        HostStore hostStore = ApplicationManager.getApplication().getService(HostStore.class);
        TunnelStore tunnelStore = ApplicationManager.getApplication().getService(TunnelStore.class);
        if (hostStore != null) {
            try {
                hostStore.reload();
            } catch (Exception ignored) {
            }
        }
        if (tunnelStore != null) {
            try {
                tunnelStore.reload();
            } catch (Exception ignored) {
            }
        }
    }
}
