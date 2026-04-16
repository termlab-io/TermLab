package com.termlab.share.planner;

import com.termlab.share.model.BundledKeyMaterial;
import com.termlab.ssh.model.KeyFileAuth;
import com.termlab.ssh.model.PromptPasswordAuth;
import com.termlab.ssh.model.SshAuth;
import com.termlab.ssh.model.SshHost;
import com.termlab.ssh.model.VaultAuth;
import com.termlab.tunnels.model.InternalHost;
import com.termlab.tunnels.model.SshConfigHost;
import com.termlab.tunnels.model.SshTunnel;
import com.termlab.tunnels.model.TunnelHost;
import com.termlab.tunnels.model.TunnelType;
import com.termlab.vault.model.AuthMethod;
import com.termlab.vault.model.Vault;
import com.termlab.vault.model.VaultAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportPlannerTest {

    private SshHost host(String label, SshAuth auth) {
        return new SshHost(
            UUID.randomUUID(),
            label,
            label + ".example.com",
            22,
            "ops",
            auth,
            null,
            null,
            Instant.now(),
            Instant.now()
        );
    }

    private SshTunnel tunnel(String label, TunnelHost host) {
        return new SshTunnel(
            UUID.randomUUID(),
            label,
            TunnelType.LOCAL,
            host,
            8080,
            "localhost",
            "target",
            80,
            Instant.now(),
            Instant.now()
        );
    }

    @Test
    void selectedHost_withPromptAuth_credentialsOff_exportsCleanly(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, "");
        SshHost h = host("web", new PromptPasswordAuth());

        ExportRequest req = new ExportRequest(
            Set.of(h.id()),
            Set.of(),
            false,
            List.of(h),
            List.of(),
            null,
            config,
            "src",
            "0.0.0"
        );

        ExportPlan plan = ExportPlanner.plan(req);
        assertEquals(1, plan.bundle().hosts().size());
        assertTrue(plan.bundle().tunnels().isEmpty());
        assertTrue(plan.bundle().vault().isEmpty());
        assertTrue(!plan.bundle().metadata().includesCredentials());
        assertInstanceOf(PromptPasswordAuth.class, plan.bundle().hosts().get(0).auth());
    }

    @Test
    void selectedTunnel_autoPullsInternalHost(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, "");
        SshHost h = host("web", new PromptPasswordAuth());
        SshTunnel t = tunnel("db", new InternalHost(h.id()));

        ExportRequest req = new ExportRequest(
            Set.of(),
            Set.of(t.id()),
            false,
            List.of(h),
            List.of(t),
            null,
            config,
            "src",
            "0.0.0"
        );

        ExportPlan plan = ExportPlanner.plan(req);
        assertEquals(1, plan.bundle().hosts().size(), "host should be auto-pulled");
        assertEquals(1, plan.bundle().tunnels().size());
        assertTrue(plan.autoPulledHostLabels().contains("web"));
    }

    @Test
    void tunnelWithSshConfigHost_convertsToInternal(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Host bastion
                HostName bastion.example.com
                User ops
            """);
        SshTunnel t = tunnel("via-bastion", new SshConfigHost("bastion"));

        ExportRequest req = new ExportRequest(
            Set.of(),
            Set.of(t.id()),
            false,
            List.of(),
            List.of(t),
            null,
            config,
            "src",
            "0.0.0"
        );

        ExportPlan plan = ExportPlanner.plan(req);
        assertEquals(1, plan.bundle().hosts().size());
        SshHost converted = plan.bundle().hosts().get(0);
        assertEquals("bastion", converted.label());
        assertEquals("bastion.example.com", converted.host());
        assertEquals("ops", converted.username());

        SshTunnel rewritten = plan.bundle().tunnels().get(0);
        assertInstanceOf(InternalHost.class, rewritten.host());
        assertEquals(converted.id(), ((InternalHost) rewritten.host()).hostId());
        assertTrue(plan.convertedSshConfigAliases().contains("bastion"));
    }

    @Test
    void hostWithVaultAuth_credentialsOn_bundlesAccount(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, "");
        UUID accountId = UUID.randomUUID();
        SshHost h = host("web", new VaultAuth(accountId));

        Vault vault = new Vault();
        vault.accounts = new ArrayList<>(List.of(new VaultAccount(
            accountId,
            "web-creds",
            "ops",
            new AuthMethod.Password("s3cret"),
            Instant.now(),
            Instant.now()
        )));

        ExportRequest req = new ExportRequest(
            Set.of(h.id()),
            Set.of(),
            true,
            List.of(h),
            List.of(),
            vault,
            config,
            "src",
            "0.0.0"
        );

        ExportPlan plan = ExportPlanner.plan(req);
        assertEquals(1, plan.bundle().vault().accounts().size());
        assertEquals(accountId, plan.bundle().vault().accounts().get(0).id());
        assertInstanceOf(VaultAuth.class, plan.bundle().hosts().get(0).auth());
    }

    @Test
    void hostWithVaultAuth_credentialsOff_downgradesToPrompt(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, "");
        UUID accountId = UUID.randomUUID();
        SshHost h = host("web", new VaultAuth(accountId));

        ExportRequest req = new ExportRequest(
            Set.of(h.id()),
            Set.of(),
            false,
            List.of(h),
            List.of(),
            null,
            config,
            "src",
            "0.0.0"
        );

        ExportPlan plan = ExportPlanner.plan(req);
        assertInstanceOf(PromptPasswordAuth.class, plan.bundle().hosts().get(0).auth());
        assertTrue(plan.bundle().vault().isEmpty());
    }

    @Test
    void hostWithKeyFileAuth_credentialsOn_convertsToVaultAuthAndBundlesMaterial(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, "");
        Path keyFile = tmp.resolve("id_ed25519");
        Files.writeString(keyFile, """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAA=
            -----END OPENSSH PRIVATE KEY-----
            """);
        SshHost h = host("web", new KeyFileAuth(keyFile.toString()));

        ExportRequest req = new ExportRequest(
            Set.of(h.id()),
            Set.of(),
            true,
            List.of(h),
            List.of(),
            new Vault(),
            config,
            "src",
            "0.0.0"
        );

        ExportPlan plan = ExportPlanner.plan(req);
        assertInstanceOf(VaultAuth.class, plan.bundle().hosts().get(0).auth());
        assertEquals(1, plan.bundle().vault().accounts().size());
        assertEquals(1, plan.bundle().keyMaterial().size());
        BundledKeyMaterial mat = plan.bundle().keyMaterial().get(0);
        assertNotNull(mat.privateKeyBase64());
        assertTrue(plan.convertedKeyFilePaths().stream().anyMatch(p -> p.endsWith("id_ed25519")));

        VaultAccount account = plan.bundle().vault().accounts().get(0);
        assertInstanceOf(AuthMethod.Key.class, account.auth());
        String keyPath = ((AuthMethod.Key) account.auth()).keyPath();
        assertTrue(BundledKeyMaterial.isSentinel(keyPath));
        assertEquals(mat.id(), BundledKeyMaterial.parseSentinel(keyPath));
    }

    @Test
    void hostWithKeyFileAuth_credentialsOff_leavesAuthAlone(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, "");
        Path keyFile = tmp.resolve("id_ed25519");
        Files.writeString(keyFile, "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n");
        SshHost h = host("web", new KeyFileAuth(keyFile.toString()));

        ExportRequest req = new ExportRequest(
            Set.of(h.id()),
            Set.of(),
            false,
            List.of(h),
            List.of(),
            null,
            config,
            "src",
            "0.0.0"
        );

        ExportPlan plan = ExportPlanner.plan(req);
        assertInstanceOf(KeyFileAuth.class, plan.bundle().hosts().get(0).auth());
        assertTrue(plan.bundle().keyMaterial().isEmpty());
    }
}
