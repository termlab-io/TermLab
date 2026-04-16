package com.termlab.share.planner;

import com.termlab.share.model.ImportItem;
import com.termlab.share.model.ShareBundle;
import com.termlab.ssh.model.SshHost;
import com.termlab.tunnels.model.InternalHost;
import com.termlab.tunnels.model.SshTunnel;
import com.termlab.tunnels.model.TunnelHost;
import com.termlab.vault.model.VaultAccount;
import com.termlab.vault.model.VaultKey;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ImportPlanner {

    private ImportPlanner() {}

    public static @NotNull ImportPlan plan(@NotNull ShareBundle bundle, @NotNull CurrentState state) {
        Map<UUID, SshHost> existingHostsById = new HashMap<>();
        Set<String> existingHostLabels = new HashSet<>();
        for (SshHost host : state.hosts()) {
            existingHostsById.put(host.id(), host);
            existingHostLabels.add(host.label());
        }

        Map<UUID, SshTunnel> existingTunnelsById = new HashMap<>();
        Set<String> existingTunnelLabels = new HashSet<>();
        for (SshTunnel tunnel : state.tunnels()) {
            existingTunnelsById.put(tunnel.id(), tunnel);
            existingTunnelLabels.add(tunnel.label());
        }

        Map<UUID, VaultAccount> existingAccountsById = new HashMap<>();
        Set<String> existingAccountNames = new HashSet<>();
        Map<UUID, VaultKey> existingKeysById = new HashMap<>();
        Set<String> existingKeyNames = new HashSet<>();
        if (state.vault() != null) {
            for (VaultAccount account : state.vault().accounts) {
                existingAccountsById.put(account.id(), account);
                existingAccountNames.add(account.displayName());
            }
            for (VaultKey key : state.vault().keys) {
                existingKeysById.put(key.id(), key);
                existingKeyNames.add(key.name());
            }
        }

        Set<UUID> bundleHostIds = new HashSet<>();
        for (SshHost host : bundle.hosts()) {
            bundleHostIds.add(host.id());
        }

        List<ImportItem> items = new ArrayList<>();

        for (SshHost host : bundle.hosts()) {
            ImportItem.Status status = determineStatus(
                existingHostsById.containsKey(host.id()),
                existingHostLabels.contains(host.label())
            );
            items.add(new ImportItem(
                ImportItem.Type.HOST,
                host.id(),
                host.label(),
                status,
                defaultAction(status),
                host
            ));
        }

        for (SshTunnel tunnel : bundle.tunnels()) {
            ImportItem.Status status;
            TunnelHost tunnelHost = tunnel.host();
            if (tunnelHost instanceof InternalHost ih
                && !bundleHostIds.contains(ih.hostId())
                && !existingHostsById.containsKey(ih.hostId())) {
                status = ImportItem.Status.REFERENCE_BROKEN;
            } else {
                status = determineStatus(
                    existingTunnelsById.containsKey(tunnel.id()),
                    existingTunnelLabels.contains(tunnel.label())
                );
            }
            items.add(new ImportItem(
                ImportItem.Type.TUNNEL,
                tunnel.id(),
                tunnel.label(),
                status,
                defaultAction(status),
                tunnel
            ));
        }

        for (VaultAccount account : bundle.vault().accounts()) {
            ImportItem.Status status = determineStatus(
                existingAccountsById.containsKey(account.id()),
                existingAccountNames.contains(account.displayName())
            );
            items.add(new ImportItem(
                ImportItem.Type.ACCOUNT,
                account.id(),
                account.displayName(),
                status,
                defaultAction(status),
                account
            ));
        }

        for (VaultKey key : bundle.vault().keys()) {
            ImportItem.Status status = determineStatus(
                existingKeysById.containsKey(key.id()),
                existingKeyNames.contains(key.name())
            );
            items.add(new ImportItem(
                ImportItem.Type.KEY,
                key.id(),
                key.name(),
                status,
                defaultAction(status),
                key
            ));
        }

        return new ImportPlan(List.copyOf(items));
    }

    private static ImportItem.Status determineStatus(boolean sameId, boolean sameLabel) {
        if (sameId) {
            return ImportItem.Status.SAME_UUID_EXISTS;
        }
        if (sameLabel) {
            return ImportItem.Status.LABEL_COLLISION;
        }
        return ImportItem.Status.NEW;
    }

    private static ImportItem.Action defaultAction(ImportItem.Status status) {
        return switch (status) {
            case NEW -> ImportItem.Action.IMPORT;
            case REFERENCE_BROKEN, SAME_UUID_EXISTS, LABEL_COLLISION -> ImportItem.Action.SKIP;
        };
    }
}
