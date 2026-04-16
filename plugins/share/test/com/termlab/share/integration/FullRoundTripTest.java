package com.termlab.share.integration;

import com.termlab.share.codec.ShareBundleCodec;
import com.termlab.share.model.ShareBundle;
import com.termlab.share.planner.CurrentState;
import com.termlab.share.planner.ExportPlan;
import com.termlab.share.planner.ExportPlanner;
import com.termlab.share.planner.ExportRequest;
import com.termlab.share.planner.ImportExecutor;
import com.termlab.share.planner.ImportPaths;
import com.termlab.share.planner.ImportPlan;
import com.termlab.share.planner.ImportPlanner;
import com.termlab.ssh.model.SshHost;
import com.termlab.ssh.model.VaultAuth;
import com.termlab.ssh.persistence.HostsFile;
import com.termlab.tunnels.model.InternalHost;
import com.termlab.tunnels.model.SshTunnel;
import com.termlab.tunnels.model.TunnelType;
import com.termlab.tunnels.persistence.TunnelsFile;
import com.termlab.vault.lock.LockManager;
import com.termlab.vault.model.AuthMethod;
import com.termlab.vault.model.Vault;
import com.termlab.vault.model.VaultAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FullRoundTripTest {

    @Test
    void export_thenImport_preservesHostsTunnelsAndCredentials(@TempDir Path tmp) throws Exception {
        Path machineA = tmp.resolve("a");
        Files.createDirectories(machineA);

        UUID accountId = UUID.randomUUID();
        VaultAccount account = new VaultAccount(
            accountId,
            "web-creds",
            "ops",
            new AuthMethod.Password("s3cret"),
            Instant.now(),
            Instant.now()
        );
        Vault vaultA = new Vault();
        vaultA.accounts = new ArrayList<>(List.of(account));

        SshHost host = new SshHost(
            UUID.randomUUID(),
            "web",
            "web.example.com",
            22,
            "ops",
            new VaultAuth(accountId),
            null,
            null,
            Instant.now(),
            Instant.now()
        );
        SshTunnel tunnel = new SshTunnel(
            UUID.randomUUID(),
            "db",
            TunnelType.LOCAL,
            new InternalHost(host.id()),
            8080,
            "localhost",
            "db.internal",
            5432,
            Instant.now(),
            Instant.now()
        );

        Path sshConfig = machineA.resolve("config");
        Files.writeString(sshConfig, "");
        ExportRequest req = new ExportRequest(
            Set.of(host.id()),
            Set.of(tunnel.id()),
            true,
            List.of(host),
            List.of(tunnel),
            vaultA,
            sshConfig,
            "machine-a",
            "0.14.2"
        );
        ExportPlan plan = ExportPlanner.plan(req);

        byte[] bundlePassword = "sharepassword1".getBytes();
        byte[] encoded = ShareBundleCodec.encode(plan.bundle(), bundlePassword);
        Path bundleFile = machineA.resolve("bundle.termlabshare");
        Files.write(bundleFile, encoded);

        Path machineB = tmp.resolve("b");
        Files.createDirectories(machineB);
        Path vaultBPath = machineB.resolve("vault.enc");
        Path hostsBPath = machineB.resolve("ssh-hosts.json");
        Path tunnelsBPath = machineB.resolve("tunnels.json");
        Path keysBDir = machineB.resolve("imported-keys");

        LockManager lmB = new LockManager(vaultBPath);
        byte[] masterB = "masterb-password".getBytes();
        lmB.createVault(masterB);
        lmB.lock();

        byte[] bundleBytes = Files.readAllBytes(bundleFile);
        ShareBundle decoded = ShareBundleCodec.decode(bundleBytes, bundlePassword);
        assertEquals(1, decoded.hosts().size());
        assertEquals(1, decoded.tunnels().size());
        assertEquals(1, decoded.vault().accounts().size());

        ImportPlan importPlan = ImportPlanner.plan(decoded, CurrentState.empty());
        ImportPaths paths = new ImportPaths(hostsBPath, tunnelsBPath, vaultBPath, keysBDir);
        ImportExecutor.execute(decoded, importPlan, paths, lmB, masterB);

        List<SshHost> importedHosts = HostsFile.load(hostsBPath);
        assertEquals(1, importedHosts.size());
        assertEquals("web", importedHosts.get(0).label());
        assertEquals("web.example.com", importedHosts.get(0).host());

        List<SshTunnel> importedTunnels = TunnelsFile.load(tunnelsBPath);
        assertEquals(1, importedTunnels.size());
        assertEquals("db", importedTunnels.get(0).label());

        lmB.unlock(masterB);
        Vault vaultB = lmB.getVault();
        assertNotNull(vaultB);
        assertEquals(1, vaultB.accounts.size());
        assertEquals("web-creds", vaultB.accounts.get(0).displayName());
        assertEquals("s3cret", ((AuthMethod.Password) vaultB.accounts.get(0).auth()).password());

        Arrays.fill(bundlePassword, (byte) 0);
        Arrays.fill(masterB, (byte) 0);
    }
}
