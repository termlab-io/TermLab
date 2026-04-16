package com.termlab.share.planner;

import com.termlab.share.model.BundleMetadata;
import com.termlab.share.model.BundledVault;
import com.termlab.share.model.ImportItem;
import com.termlab.share.model.ShareBundle;
import com.termlab.ssh.model.PromptPasswordAuth;
import com.termlab.ssh.model.SshHost;
import com.termlab.tunnels.model.InternalHost;
import com.termlab.tunnels.model.SshTunnel;
import com.termlab.tunnels.model.TunnelType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImportPlannerTest {

    private SshHost host(UUID id, String label) {
        return new SshHost(
            id,
            label,
            label + ".example.com",
            22,
            "ops",
            new PromptPasswordAuth(),
            null,
            null,
            Instant.now(),
            Instant.now()
        );
    }

    private SshTunnel tunnel(UUID id, String label, UUID hostId) {
        return new SshTunnel(
            id,
            label,
            TunnelType.LOCAL,
            new InternalHost(hostId),
            8080,
            "localhost",
            "t",
            80,
            Instant.now(),
            Instant.now()
        );
    }

    private ShareBundle bundleOf(List<SshHost> hosts, List<SshTunnel> tunnels) {
        return new ShareBundle(
            ShareBundle.CURRENT_SCHEMA_VERSION,
            new BundleMetadata(Instant.now(), "src", "0.0.0", false),
            hosts,
            tunnels,
            BundledVault.empty(),
            List.of()
        );
    }

    @Test
    void allNew_whenCurrentStateEmpty() {
        UUID hid = UUID.randomUUID();
        ShareBundle b = bundleOf(List.of(host(hid, "web")), List.of());

        ImportPlan plan = ImportPlanner.plan(b, CurrentState.empty());
        assertEquals(1, plan.items().size());
        assertEquals(ImportItem.Status.NEW, plan.items().get(0).status);
        assertEquals(ImportItem.Action.IMPORT, plan.items().get(0).action);
    }

    @Test
    void sameUuidExists_marksAsConflict() {
        UUID hid = UUID.randomUUID();
        ShareBundle b = bundleOf(List.of(host(hid, "web")), List.of());
        CurrentState state = new CurrentState(List.of(host(hid, "existing")), List.of(), null);

        ImportPlan plan = ImportPlanner.plan(b, state);
        assertEquals(ImportItem.Status.SAME_UUID_EXISTS, plan.items().get(0).status);
    }

    @Test
    void labelCollision_marksAsConflict() {
        UUID bundleId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        ShareBundle b = bundleOf(List.of(host(bundleId, "web")), List.of());
        CurrentState state = new CurrentState(List.of(host(existingId, "web")), List.of(), null);

        ImportPlan plan = ImportPlanner.plan(b, state);
        assertEquals(ImportItem.Status.LABEL_COLLISION, plan.items().get(0).status);
    }

    @Test
    void tunnelReferencingMissingHost_marksAsReferenceBroken() {
        UUID missingHost = UUID.randomUUID();
        UUID tid = UUID.randomUUID();
        ShareBundle b = bundleOf(List.of(), List.of(tunnel(tid, "db", missingHost)));

        ImportPlan plan = ImportPlanner.plan(b, CurrentState.empty());
        ImportItem tunnelItem = plan.items().stream()
            .filter(i -> i.type == ImportItem.Type.TUNNEL)
            .findFirst()
            .orElseThrow();
        assertEquals(ImportItem.Status.REFERENCE_BROKEN, tunnelItem.status);
        assertEquals(ImportItem.Action.SKIP, tunnelItem.action);
    }

    @Test
    void tunnelWithReferencedHostInBundle_isNew() {
        UUID hid = UUID.randomUUID();
        UUID tid = UUID.randomUUID();
        ShareBundle b = bundleOf(List.of(host(hid, "web")), List.of(tunnel(tid, "db", hid)));

        ImportPlan plan = ImportPlanner.plan(b, CurrentState.empty());
        ImportItem tunnelItem = plan.items().stream()
            .filter(i -> i.type == ImportItem.Type.TUNNEL)
            .findFirst()
            .orElseThrow();
        assertEquals(ImportItem.Status.NEW, tunnelItem.status);
    }
}
