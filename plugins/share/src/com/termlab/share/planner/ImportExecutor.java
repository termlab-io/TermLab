package com.termlab.share.planner;

import com.termlab.share.model.BundledKeyMaterial;
import com.termlab.share.model.ImportItem;
import com.termlab.share.model.ShareBundle;
import com.termlab.ssh.model.SshHost;
import com.termlab.ssh.persistence.HostsFile;
import com.termlab.tunnels.model.SshTunnel;
import com.termlab.tunnels.persistence.TunnelsFile;
import com.termlab.vault.lock.LockManager;
import com.termlab.vault.model.AuthMethod;
import com.termlab.vault.model.Vault;
import com.termlab.vault.model.VaultAccount;
import com.termlab.vault.model.VaultKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ImportExecutor {

    private ImportExecutor() {}

    public static @NotNull ImportResult execute(
        @NotNull ShareBundle bundle,
        @NotNull ImportPlan plan,
        @NotNull ImportPaths paths,
        @Nullable LockManager lockManager,
        byte @Nullable [] vaultPassword
    ) throws Exception {
        Map<UUID, String> materializedPaths = materializeKeyMaterial(bundle.keyMaterial(), paths.importedKeysDir());

        int skipped = 0;
        Set<UUID> accountsToImport = new HashSet<>();
        Set<UUID> keysToImport = new HashSet<>();
        for (ImportItem item : plan.items()) {
            if (item.action == ImportItem.Action.SKIP) {
                skipped++;
                continue;
            }
            if (item.type == ImportItem.Type.ACCOUNT) {
                accountsToImport.add(item.id);
            } else if (item.type == ImportItem.Type.KEY) {
                keysToImport.add(item.id);
            }
        }

        int accountsImported = 0;
        int keysImported = 0;
        if (lockManager != null && vaultPassword != null && !bundle.vault().isEmpty()) {
            final int[] accountCount = {0};
            final int[] keyCount = {0};
            lockManager.withUnlocked(vaultPassword, vault -> {
                for (VaultKey key : bundle.vault().keys()) {
                    if (!keysToImport.contains(key.id())) {
                        continue;
                    }
                    replaceOrAddKey(vault, rewriteKeyPaths(key, materializedPaths));
                    keyCount[0]++;
                }
                for (VaultAccount account : bundle.vault().accounts()) {
                    if (!accountsToImport.contains(account.id())) {
                        continue;
                    }
                    VaultAccount rewritten = rewriteAccountPaths(account, materializedPaths);
                    replaceOrAddAccount(vault, applyRename(rewritten, plan, account.id()));
                    accountCount[0]++;
                }
                return null;
            });
            accountsImported = accountCount[0];
            keysImported = keyCount[0];
        }

        int hostsImported = applyHostChanges(plan, paths.hostsFile());
        int tunnelsImported = applyTunnelChanges(plan, paths.tunnelsFile());

        String summary = String.format(
            "Imported %d hosts, %d tunnels, %d credentials, %d keys. %d skipped.",
            hostsImported, tunnelsImported, accountsImported, keysImported, skipped
        );
        return new ImportResult(hostsImported, tunnelsImported, accountsImported, keysImported, skipped, summary);
    }

    private static Map<UUID, String> materializeKeyMaterial(
        List<BundledKeyMaterial> keyMaterial,
        Path importedKeysDir
    ) throws IOException {
        Files.createDirectories(importedKeysDir);
        Map<UUID, String> out = new HashMap<>();
        for (BundledKeyMaterial material : keyMaterial) {
            Path target = importedKeysDir.resolve(material.id() + ".key");
            byte[] bytes = Base64.getDecoder().decode(material.privateKeyBase64());
            writeAtomic(target, bytes);
            try {
                Files.setPosixFilePermissions(
                    target,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                );
            } catch (UnsupportedOperationException ignored) {
                // Non-posix filesystem.
            }
            out.put(material.id(), target.toString());
        }
        return out;
    }

    private static VaultKey rewriteKeyPaths(VaultKey key, Map<UUID, String> materializedPaths) {
        String newPrivate = rewriteIfSentinel(key.privatePath(), materializedPaths);
        if (newPrivate.equals(key.privatePath())) {
            return key;
        }
        return new VaultKey(
            key.id(),
            key.name(),
            key.algorithm(),
            key.fingerprint(),
            key.comment(),
            newPrivate,
            key.publicPath(),
            key.createdAt()
        );
    }

    private static VaultAccount rewriteAccountPaths(VaultAccount account, Map<UUID, String> materializedPaths) {
        AuthMethod rewritten = switch (account.auth()) {
            case AuthMethod.Password password -> password;
            case AuthMethod.Key key -> new AuthMethod.Key(
                rewriteIfSentinel(key.keyPath(), materializedPaths),
                key.passphrase()
            );
            case AuthMethod.KeyAndPassword keyAndPassword -> new AuthMethod.KeyAndPassword(
                rewriteIfSentinel(keyAndPassword.keyPath(), materializedPaths),
                keyAndPassword.passphrase(),
                keyAndPassword.password()
            );
        };
        if (rewritten == account.auth()) {
            return account;
        }
        return new VaultAccount(
            account.id(),
            account.displayName(),
            account.username(),
            rewritten,
            account.createdAt(),
            Instant.now()
        );
    }

    private static String rewriteIfSentinel(String path, Map<UUID, String> materializedPaths) {
        if (!BundledKeyMaterial.isSentinel(path)) {
            return path;
        }
        UUID id = BundledKeyMaterial.parseSentinel(path);
        String resolved = materializedPaths.get(id);
        if (resolved == null) {
            throw new IllegalStateException("key material not materialized for " + id);
        }
        return resolved;
    }

    private static VaultAccount applyRename(VaultAccount account, ImportPlan plan, UUID id) {
        for (ImportItem item : plan.items()) {
            if (item.id.equals(id) && item.action == ImportItem.Action.RENAME && item.renameTo != null) {
                return new VaultAccount(
                    account.id(),
                    item.renameTo,
                    account.username(),
                    account.auth(),
                    account.createdAt(),
                    Instant.now()
                );
            }
        }
        return account;
    }

    private static void replaceOrAddKey(Vault vault, VaultKey key) {
        for (int i = 0; i < vault.keys.size(); i++) {
            if (vault.keys.get(i).id().equals(key.id())) {
                vault.keys.set(i, key);
                return;
            }
        }
        vault.keys.add(key);
    }

    private static void replaceOrAddAccount(Vault vault, VaultAccount account) {
        for (int i = 0; i < vault.accounts.size(); i++) {
            if (vault.accounts.get(i).id().equals(account.id())) {
                vault.accounts.set(i, account);
                return;
            }
        }
        vault.accounts.add(account);
    }

    private static int applyHostChanges(ImportPlan plan, Path hostsFile) throws IOException {
        List<SshHost> existing = HostsFile.exists(hostsFile)
            ? new ArrayList<>(HostsFile.load(hostsFile))
            : new ArrayList<>();
        Map<UUID, Integer> indexById = new HashMap<>();
        for (int i = 0; i < existing.size(); i++) {
            indexById.put(existing.get(i).id(), i);
        }
        int imported = 0;

        for (ImportItem item : plan.items()) {
            if (item.type != ImportItem.Type.HOST || item.action == ImportItem.Action.SKIP) {
                continue;
            }
            SshHost host = (SshHost) item.payload;
            if (item.action == ImportItem.Action.RENAME && item.renameTo != null) {
                host = host.withLabel(item.renameTo);
            }
            Integer existingIdx = indexById.get(host.id());
            if (existingIdx != null && item.action == ImportItem.Action.REPLACE) {
                existing.set(existingIdx, host);
            } else if (existingIdx == null) {
                existing.add(host);
                indexById.put(host.id(), existing.size() - 1);
            } else {
                continue;
            }
            imported++;
        }

        HostsFile.save(hostsFile, existing);
        return imported;
    }

    private static int applyTunnelChanges(ImportPlan plan, Path tunnelsFile) throws IOException {
        List<SshTunnel> existing;
        try {
            existing = new ArrayList<>(TunnelsFile.load(tunnelsFile));
        } catch (IOException e) {
            existing = new ArrayList<>();
        }
        Map<UUID, Integer> indexById = new HashMap<>();
        for (int i = 0; i < existing.size(); i++) {
            indexById.put(existing.get(i).id(), i);
        }
        int imported = 0;

        for (ImportItem item : plan.items()) {
            if (item.type != ImportItem.Type.TUNNEL || item.action == ImportItem.Action.SKIP) {
                continue;
            }
            SshTunnel tunnel = (SshTunnel) item.payload;
            if (item.action == ImportItem.Action.RENAME && item.renameTo != null) {
                tunnel = tunnel.withLabel(item.renameTo);
            }
            Integer existingIdx = indexById.get(tunnel.id());
            if (existingIdx != null && item.action == ImportItem.Action.REPLACE) {
                existing.set(existingIdx, tunnel);
            } else if (existingIdx == null) {
                existing.add(tunnel);
                indexById.put(tunnel.id(), existing.size() - 1);
            } else {
                continue;
            }
            imported++;
        }

        TunnelsFile.save(tunnelsFile, existing);
        return imported;
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
