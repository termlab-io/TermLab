package com.termlab.share.planner;

import com.termlab.share.conversion.KeyFileImporter;
import com.termlab.share.conversion.SshConfigEntry;
import com.termlab.share.conversion.SshConfigReader;
import com.termlab.share.model.BundleMetadata;
import com.termlab.share.model.BundledKeyMaterial;
import com.termlab.share.model.BundledVault;
import com.termlab.share.model.ShareBundle;
import com.termlab.ssh.model.KeyFileAuth;
import com.termlab.ssh.model.PromptPasswordAuth;
import com.termlab.ssh.model.SshAuth;
import com.termlab.ssh.model.SshHost;
import com.termlab.ssh.model.VaultAuth;
import com.termlab.tunnels.model.InternalHost;
import com.termlab.tunnels.model.SshConfigHost;
import com.termlab.tunnels.model.SshTunnel;
import com.termlab.tunnels.model.TunnelHost;
import com.termlab.vault.model.AuthMethod;
import com.termlab.vault.model.Vault;
import com.termlab.vault.model.VaultAccount;
import com.termlab.vault.model.VaultKey;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ExportPlanner {

    private ExportPlanner() {}

    public static @NotNull ExportPlan plan(@NotNull ExportRequest req) {
        List<ConversionWarning> warnings = new ArrayList<>();
        List<String> autoPulled = new ArrayList<>();
        List<String> convertedAliases = new ArrayList<>();
        List<String> convertedKeyPaths = new ArrayList<>();

        Map<UUID, SshHost> allHostsById = new HashMap<>();
        for (SshHost h : req.allHosts()) {
            allHostsById.put(h.id(), h);
        }

        Map<UUID, SshHost> bundleHosts = new LinkedHashMap<>();
        Map<UUID, SshTunnel> bundleTunnels = new LinkedHashMap<>();
        Map<UUID, VaultAccount> bundleAccounts = new LinkedHashMap<>();
        Map<UUID, VaultKey> bundleKeys = new LinkedHashMap<>();
        List<BundledKeyMaterial> keyMaterial = new ArrayList<>();

        for (UUID id : req.selectedHostIds()) {
            SshHost h = allHostsById.get(id);
            if (h != null) {
                bundleHosts.put(h.id(), h);
            }
        }

        for (SshTunnel t : req.allTunnels()) {
            if (!req.selectedTunnelIds().contains(t.id())) {
                continue;
            }
            TunnelHost tunnelHost = t.host();
            if (tunnelHost instanceof InternalHost ih) {
                SshHost ref = allHostsById.get(ih.hostId());
                if (ref != null && !bundleHosts.containsKey(ref.id())) {
                    bundleHosts.put(ref.id(), ref);
                    autoPulled.add(ref.label());
                }
                bundleTunnels.put(t.id(), t);
                continue;
            }
            if (tunnelHost instanceof SshConfigHost sch) {
                Optional<SshConfigEntry> entry;
                try {
                    entry = SshConfigReader.read(req.sshConfigPath(), sch.alias());
                } catch (Exception e) {
                    warnings.add(new ConversionWarning(
                        "tunnel " + t.label(),
                        "Failed to read ~/.ssh/config: " + e.getMessage()
                    ));
                    continue;
                }
                if (entry.isEmpty()) {
                    warnings.add(new ConversionWarning(
                        "tunnel " + t.label(),
                        "Alias '" + sch.alias() + "' not found in ssh_config; tunnel skipped"
                    ));
                    continue;
                }

                SshConfigEntry e = entry.get();
                for (String w : e.warnings()) {
                    warnings.add(new ConversionWarning("alias " + sch.alias(), w));
                }

                SshHost synthesized = new SshHost(
                    UUID.randomUUID(),
                    e.alias(),
                    e.hostName() != null ? e.hostName() : e.alias(),
                    e.port(),
                    e.user() != null ? e.user() : System.getProperty("user.name", "user"),
                    new PromptPasswordAuth(),
                    e.proxyCommand(),
                    e.proxyJump(),
                    Instant.now(),
                    Instant.now()
                );
                bundleHosts.put(synthesized.id(), synthesized);
                convertedAliases.add(e.alias());

                SshTunnel rewritten = new SshTunnel(
                    t.id(),
                    t.label(),
                    t.type(),
                    new InternalHost(synthesized.id()),
                    t.bindPort(),
                    t.bindAddress(),
                    t.targetHost(),
                    t.targetPort(),
                    t.createdAt(),
                    t.updatedAt()
                );
                bundleTunnels.put(rewritten.id(), rewritten);
            }
        }

        Map<UUID, SshHost> hostReplacements = new HashMap<>();
        for (SshHost h : bundleHosts.values()) {
            SshAuth auth = h.auth();
            if (auth instanceof VaultAuth va && va.credentialId() != null) {
                if (req.includeCredentials() && req.unlockedVault() != null) {
                    VaultAccount account = findAccount(req.unlockedVault(), va.credentialId());
                    if (account != null) {
                        AccountBundleResult bundled = bundleAccount(account, keyMaterial);
                        bundleAccounts.put(bundled.account.id(), bundled.account);
                        for (VaultKey key : bundled.keys) {
                            bundleKeys.put(key.id(), key);
                        }
                    } else {
                        warnings.add(new ConversionWarning(
                            "host " + h.label(),
                            "Referenced vault credential not found; downgrading to prompt"
                        ));
                        hostReplacements.put(h.id(), h.withAuth(new PromptPasswordAuth()));
                    }
                } else {
                    hostReplacements.put(h.id(), h.withAuth(new PromptPasswordAuth()));
                }
                continue;
            }

            if (auth instanceof KeyFileAuth kfa) {
                if (!req.includeCredentials()) {
                    continue;
                }
                KeyFileImporter.Result result = KeyFileImporter.read(Path.of(kfa.keyFilePath()), null);
                if (result instanceof KeyFileImporter.Result.Ok ok) {
                    keyMaterial.add(ok.material());
                    convertedKeyPaths.add(kfa.keyFilePath());

                    UUID syntheticAccountId = UUID.randomUUID();
                    VaultAccount synthAccount = new VaultAccount(
                        syntheticAccountId,
                        h.label() + " (imported key)",
                        h.username(),
                        new AuthMethod.Key(BundledKeyMaterial.sentinelFor(ok.material().id()), null),
                        Instant.now(),
                        Instant.now()
                    );
                    bundleAccounts.put(syntheticAccountId, synthAccount);
                    hostReplacements.put(h.id(), h.withAuth(new VaultAuth(syntheticAccountId)));
                } else if (result instanceof KeyFileImporter.Result.Warning warning) {
                    warnings.add(new ConversionWarning("host " + h.label(), warning.message()));
                } else if (result instanceof KeyFileImporter.Result.NeedsPassphrase needsPassphrase) {
                    warnings.add(new ConversionWarning(
                        "host " + h.label(),
                        "Key file requires a passphrase and cannot be bundled without one: " + needsPassphrase.path()
                    ));
                }
            }
        }

        List<SshHost> finalHosts = new ArrayList<>();
        for (SshHost h : bundleHosts.values()) {
            finalHosts.add(hostReplacements.getOrDefault(h.id(), h));
        }

        BundleMetadata metadata = new BundleMetadata(
            Instant.now(),
            req.sourceHost(),
            req.termlabVersion(),
            req.includeCredentials()
        );

        ShareBundle bundle = new ShareBundle(
            ShareBundle.CURRENT_SCHEMA_VERSION,
            metadata,
            List.copyOf(finalHosts),
            List.copyOf(bundleTunnels.values()),
            new BundledVault(List.copyOf(bundleAccounts.values()), List.copyOf(bundleKeys.values())),
            List.copyOf(keyMaterial)
        );

        return new ExportPlan(
            bundle,
            List.copyOf(warnings),
            List.copyOf(autoPulled),
            List.copyOf(convertedAliases),
            List.copyOf(convertedKeyPaths)
        );
    }

    private static VaultAccount findAccount(Vault vault, UUID id) {
        for (VaultAccount account : vault.accounts) {
            if (account.id().equals(id)) {
                return account;
            }
        }
        return null;
    }

    private record AccountBundleResult(VaultAccount account, List<VaultKey> keys) {}

    private static AccountBundleResult bundleAccount(VaultAccount account, List<BundledKeyMaterial> keyMaterial) {
        AuthMethod method = account.auth();
        if (method instanceof AuthMethod.Key key
            && key.keyPath() != null
            && !BundledKeyMaterial.isSentinel(key.keyPath())) {
            KeyFileImporter.Result result = KeyFileImporter.read(Path.of(key.keyPath()), key.passphrase());
            if (result instanceof KeyFileImporter.Result.Ok ok) {
                keyMaterial.add(ok.material());
                VaultAccount rewritten = new VaultAccount(
                    account.id(),
                    account.displayName(),
                    account.username(),
                    new AuthMethod.Key(BundledKeyMaterial.sentinelFor(ok.material().id()), key.passphrase()),
                    account.createdAt(),
                    account.updatedAt()
                );
                return new AccountBundleResult(rewritten, List.of());
            }
        }
        if (method instanceof AuthMethod.KeyAndPassword keyAndPassword
            && keyAndPassword.keyPath() != null
            && !BundledKeyMaterial.isSentinel(keyAndPassword.keyPath())) {
            KeyFileImporter.Result result = KeyFileImporter.read(
                Path.of(keyAndPassword.keyPath()),
                keyAndPassword.passphrase()
            );
            if (result instanceof KeyFileImporter.Result.Ok ok) {
                keyMaterial.add(ok.material());
                VaultAccount rewritten = new VaultAccount(
                    account.id(),
                    account.displayName(),
                    account.username(),
                    new AuthMethod.KeyAndPassword(
                        BundledKeyMaterial.sentinelFor(ok.material().id()),
                        keyAndPassword.passphrase(),
                        keyAndPassword.password()
                    ),
                    account.createdAt(),
                    account.updatedAt()
                );
                return new AccountBundleResult(rewritten, List.of());
            }
        }
        return new AccountBundleResult(account, List.of());
    }
}
