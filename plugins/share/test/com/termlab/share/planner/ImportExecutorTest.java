package com.termlab.share.planner;

import com.termlab.share.model.BundleMetadata;
import com.termlab.share.model.BundledKeyMaterial;
import com.termlab.share.model.BundledVault;
import com.termlab.share.model.ImportItem;
import com.termlab.share.model.ShareBundle;
import com.termlab.ssh.model.PromptPasswordAuth;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportExecutorTest {

    private ImportPaths pathsIn(Path tmp) throws Exception {
        Path keysDir = tmp.resolve("imported-keys");
        Files.createDirectories(keysDir);
        return new ImportPaths(
            tmp.resolve("ssh-hosts.json"),
            tmp.resolve("tunnels.json"),
            tmp.resolve("vault.enc"),
            keysDir
        );
    }

    @Test
    void imports_hostsAndTunnels_whenNoCredentials(@TempDir Path tmp) throws Exception {
        UUID hid = UUID.randomUUID();
        UUID tid = UUID.randomUUID();
        SshHost h = new SshHost(
            hid,
            "web",
            "web.example.com",
            22,
            "ops",
            new PromptPasswordAuth(),
            null,
            null,
            Instant.now(),
            Instant.now()
        );
        SshTunnel t = new SshTunnel(
            tid,
            "db",
            TunnelType.LOCAL,
            new InternalHost(hid),
            8080,
            "localhost",
            "target",
            80,
            Instant.now(),
            Instant.now()
        );

        ShareBundle bundle = new ShareBundle(
            ShareBundle.CURRENT_SCHEMA_VERSION,
            new BundleMetadata(Instant.now(), "src", "0.0.0", false),
            List.of(h),
            List.of(t),
            BundledVault.empty(),
            List.of()
        );

        ImportPlan plan = new ImportPlan(List.of(
            new ImportItem(ImportItem.Type.HOST, hid, "web", ImportItem.Status.NEW, ImportItem.Action.IMPORT, h),
            new ImportItem(ImportItem.Type.TUNNEL, tid, "db", ImportItem.Status.NEW, ImportItem.Action.IMPORT, t)
        ));

        ImportPaths paths = pathsIn(tmp);
        ImportResult result = ImportExecutor.execute(bundle, plan, paths, null, null);

        assertEquals(1, result.hostsImported());
        assertEquals(1, result.tunnelsImported());
        assertEquals(List.of(h), HostsFile.load(paths.hostsFile()));
        assertEquals(1, TunnelsFile.load(paths.tunnelsFile()).size());
    }

    @Test
    void imports_keyMaterial_rewritesSentinelPaths(@TempDir Path tmp) throws Exception {
        UUID materialId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        byte[] keyBytes = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n".getBytes();

        BundledKeyMaterial material = new BundledKeyMaterial(
            materialId,
            Base64.getEncoder().encodeToString(keyBytes),
            null,
            "id_ed25519"
        );

        VaultAccount account = new VaultAccount(
            accountId,
            "web-creds",
            "ops",
            new AuthMethod.Key(BundledKeyMaterial.sentinelFor(materialId), null),
            Instant.now(),
            Instant.now()
        );

        SshHost host = new SshHost(
            hostId,
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

        ShareBundle bundle = new ShareBundle(
            ShareBundle.CURRENT_SCHEMA_VERSION,
            new BundleMetadata(Instant.now(), "src", "0.0.0", true),
            List.of(host),
            List.of(),
            new BundledVault(List.of(account), List.of()),
            List.of(material)
        );

        ImportPlan plan = new ImportPlan(List.of(
            new ImportItem(ImportItem.Type.HOST, hostId, "web", ImportItem.Status.NEW, ImportItem.Action.IMPORT, host),
            new ImportItem(
                ImportItem.Type.ACCOUNT,
                accountId,
                "web-creds",
                ImportItem.Status.NEW,
                ImportItem.Action.IMPORT,
                account
            )
        ));

        ImportPaths paths = pathsIn(tmp);
        LockManager lm = new LockManager(paths.vaultFile());
        byte[] password = "masterpass12345".getBytes();
        try {
            lm.createVault(password);
            lm.lock();

            ImportExecutor.execute(bundle, plan, paths, lm, password);
        } finally {
            Arrays.fill(password, (byte) 0);
        }

        Path written = paths.importedKeysDir().resolve(materialId + ".key");
        assertTrue(Files.exists(written), "key file should be materialized to disk");
        assertArrayEquals(keyBytes, Files.readAllBytes(written));

        byte[] pw2 = "masterpass12345".getBytes();
        try {
            LockManager lm2 = new LockManager(paths.vaultFile());
            lm2.unlock(pw2);
            Vault loaded = lm2.getVault();
            VaultAccount reloaded = loaded.accounts.stream()
                .filter(a -> a.id().equals(accountId))
                .findFirst()
                .orElseThrow();
            String path = ((AuthMethod.Key) reloaded.auth()).keyPath();
            assertFalse(BundledKeyMaterial.isSentinel(path));
            assertEquals(written.toString(), path);
        } finally {
            Arrays.fill(pw2, (byte) 0);
        }
    }

    @Test
    void skip_actionIsNotWritten(@TempDir Path tmp) throws Exception {
        UUID hid = UUID.randomUUID();
        SshHost h = new SshHost(
            hid,
            "web",
            "web.example.com",
            22,
            "ops",
            new PromptPasswordAuth(),
            null,
            null,
            Instant.now(),
            Instant.now()
        );

        ShareBundle bundle = new ShareBundle(
            ShareBundle.CURRENT_SCHEMA_VERSION,
            new BundleMetadata(Instant.now(), "src", "0.0.0", false),
            List.of(h),
            List.of(),
            BundledVault.empty(),
            List.of()
        );
        ImportPlan plan = new ImportPlan(List.of(
            new ImportItem(ImportItem.Type.HOST, hid, "web", ImportItem.Status.NEW, ImportItem.Action.SKIP, h)
        ));

        ImportResult result = ImportExecutor.execute(bundle, plan, pathsIn(tmp), null, null);
        assertEquals(0, result.hostsImported());
        assertEquals(1, result.skipped());
    }
}
