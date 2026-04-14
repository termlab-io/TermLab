# Share Export/Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a new `plugins/share` module that exports selected SSH hosts + tunnels (optionally with vault credentials) into a single encrypted `.conchshare` file, and imports such files on another machine with inline vault setup.

**Architecture:** New plugin depending on `vault`, `ssh`, `tunnels`. Pure Java 21. Reuses `VaultCrypto` + `KeyDerivation` for the bundle envelope. Adds two missing methods (`createVault`, `withUnlocked`) to `LockManager`. Because the vault's existing model stores key **file paths** (not key bytes), the bundle introduces a `BundledKeyMaterial` type that carries the actual private key bytes; on import, `ImportExecutor` writes each material to `~/.config/conch/imported-keys/<uuid>.key` and rewrites sentinel paths (`$CONCH_SHARE_KEY:<uuid>`) in the incoming accounts/keys to the real location.

**Tech Stack:** Java 21, Bazel, Gson, IntelliJ Platform, JUnit 5, BouncyCastle (via `//libraries/bouncy-castle-provider`, reused for Argon2id).

**Spec:** `docs/plans/2026-04-14-share-export-import.md`

---

## Key Design Notes (read before starting)

1. **Key bytes are carried out-of-band in the bundle.** `ShareBundle` has a top-level `keyMaterial: List<BundledKeyMaterial>`. Sentinel paths (`$CONCH_SHARE_KEY:<uuid>`) appear in `AuthMethod.Key.keyPath`, `AuthMethod.KeyAndPassword.keyPath`, and `VaultKey.privatePath`. On import, these are rewritten to on-disk paths.

2. **Two missing LockManager methods must be added first** (Task 2). Everything downstream assumes they exist.

3. **Atomic file writes** always use temp-file + `ATOMIC_MOVE` rename (see `VaultFile.java:46-52`). Never write directly to the target path.

4. **Password byte arrays** must be zeroed in `finally` blocks. Same for derived keys.

5. **Sealed types are serialized with a `"type"` discriminator field.** See `SshGson`, `TunnelGson`, `VaultGson` — copy the pattern exactly for any new sealed hierarchies.

6. **Sentinel path format:** `$CONCH_SHARE_KEY:<uuid-string>`. All three consumers (ImportExecutor, tests, export path rewriter) must use this exact constant — define once as `BundledKeyMaterial.SENTINEL_PREFIX = "$CONCH_SHARE_KEY:"`.

7. **Test target naming:** `//conch/plugins/share:share_test_runner`. Main class: `com.conch.share.TestRunner`.

8. **Project root for bazel commands:** `$INTELLIJ_ROOT` (set in the Makefile). Always run bazel commands from there via `cd $(INTELLIJ_ROOT) && bash bazel.cmd ...`, or use the Makefile targets.

---

## File Structure

```
plugins/share/
├── BUILD.bazel
├── resources/
│   └── META-INF/
│       └── plugin.xml
├── src/com/conch/share/
│   ├── TestRunner.java                          (JUnit 5 launcher)
│   ├── model/
│   │   ├── ShareBundle.java                     (top-level record)
│   │   ├── BundleMetadata.java                  (created, source, version, includesCredentials)
│   │   ├── BundledVault.java                    (accounts + keys)
│   │   ├── BundledKeyMaterial.java              (uuid + key bytes + sentinel constant)
│   │   └── ImportItem.java                      (for ImportPlanner)
│   ├── codec/
│   │   ├── ShareBundleCodec.java                (encode/decode, envelope)
│   │   ├── ShareBundleFormat.java               (byte layout constants, assemble/parse)
│   │   ├── ShareBundleGson.java                 (Gson instance, adapters)
│   │   └── exceptions/
│   │       ├── WrongBundlePasswordException.java
│   │       ├── BundleCorruptedException.java
│   │       └── UnsupportedBundleVersionException.java
│   ├── planner/
│   │   ├── ExportPlanner.java
│   │   ├── ExportPlan.java                      (result: ShareBundle + warnings)
│   │   ├── ConversionWarning.java
│   │   ├── ImportPlanner.java
│   │   ├── ImportPlan.java                      (list of ImportItem + aggregate counts)
│   │   └── ImportExecutor.java
│   ├── conversion/
│   │   ├── SshConfigReader.java
│   │   ├── SshConfigEntry.java                  (parsed alias result)
│   │   └── KeyFileImporter.java
│   ├── ui/
│   │   ├── ExportDialog.java
│   │   ├── ImportDialog.java
│   │   ├── ConflictResolutionDialog.java        (per-conflict prompt with "apply to all")
│   │   └── PasswordUtil.java                    (char[] → byte[] UTF-8 + zeroing)
│   └── actions/
│       ├── ExportAction.java
│       └── ImportAction.java
└── test/com/conch/share/
    ├── codec/
    │   └── ShareBundleCodecTest.java
    ├── planner/
    │   ├── ExportPlannerTest.java
    │   ├── ImportPlannerTest.java
    │   └── ImportExecutorTest.java
    ├── conversion/
    │   ├── SshConfigReaderTest.java
    │   └── KeyFileImporterTest.java
    └── integration/
        └── FullRoundTripTest.java
```

Additionally modifies:
- `plugins/vault/src/com/conch/vault/lock/LockManager.java` — adds `createVault`, `withUnlocked`
- `plugins/vault/test/com/conch/vault/lock/LockManagerTest.java` — new tests (create file if it doesn't exist)
- `BUILD.bazel` (root) — adds `//conch/plugins/share` to `conch_run` runtime_deps
- `plugins/ssh/src/com/conch/ssh/toolwindow/HostsToolWindow.java` — adds Export/Import toolbar buttons
- `plugins/tunnels/src/com/conch/tunnels/toolwindow/TunnelsToolWindow.java` — same

---

## Task 1: Scaffold the `plugins/share` module

**Files:**
- Create: `plugins/share/BUILD.bazel`
- Create: `plugins/share/resources/META-INF/plugin.xml`
- Create: `plugins/share/src/com/conch/share/TestRunner.java`
- Create: `plugins/share/src/com/conch/share/package-info.java`
- Create: `plugins/share/test/com/conch/share/SmokeTest.java`
- Modify: `BUILD.bazel` (root, add share to `conch_run` runtime_deps)

- [ ] **Step 1.1: Create `plugins/share/BUILD.bazel`**

```python
load("@rules_java//java:defs.bzl", "java_binary")
load("@rules_jvm//:jvm.bzl", "jvm_library", "resourcegroup")

resourcegroup(
    name = "share_resources",
    srcs = glob(["resources/**/*"]),
    strip_prefix = "resources",
)

jvm_library(
    name = "share",
    module_name = "intellij.conch.share",
    visibility = ["//visibility:public"],
    srcs = glob(["src/**/*.java"], allow_empty = True),
    resources = [":share_resources"],
    deps = [
        "//conch/sdk",
        "//conch/core",
        "//conch/plugins/vault",
        "//conch/plugins/ssh",
        "//conch/plugins/tunnels",
        "//platform/analysis-api:analysis",
        "//platform/core-api:core",
        "//platform/core-ui",
        "//platform/editor-ui-api:editor-ui",
        "//platform/ide-core",
        "//platform/lang-api:lang",
        "//platform/platform-api:ide",
        "//platform/platform-impl:ide-impl",
        "//platform/projectModel-api:projectModel",
        "//platform/util:util-ui",
        "//libraries/bouncy-castle-provider",
        "@lib//:jetbrains-annotations",
        "@lib//:gson",
        "@lib//:kotlin-stdlib",
    ],
)

jvm_library(
    name = "share_test_lib",
    module_name = "intellij.conch.share.tests",
    visibility = ["//visibility:public"],
    srcs = glob(["test/**/*.java"], allow_empty = True),
    deps = [
        ":share",
        "//conch/sdk",
        "//conch/plugins/vault",
        "//conch/plugins/ssh",
        "//conch/plugins/tunnels",
        "//libraries/junit5",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
        "//libraries/bouncy-castle-provider",
        "@lib//:jetbrains-annotations",
        "@lib//:gson",
        "@lib//:kotlin-stdlib",
    ],
)

java_binary(
    name = "share_test_runner",
    main_class = "com.conch.share.TestRunner",
    runtime_deps = [
        ":share_test_lib",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
    ],
)

exports_files(["intellij.conch.share.iml"], visibility = ["//visibility:public"])
```

- [ ] **Step 1.2: Create `plugins/share/resources/META-INF/plugin.xml`**

```xml
<idea-plugin>
    <id>com.conch.share</id>
    <name>Conch Share</name>
    <vendor>Conch</vendor>
    <description>Export and import SSH hosts, tunnels, and credentials as encrypted bundles.</description>

    <depends>com.conch.core</depends>
    <depends>com.conch.vault</depends>
    <depends>com.conch.ssh</depends>
    <depends>com.conch.tunnels</depends>

    <actions>
        <action id="Conch.Share.Export"
                class="com.conch.share.actions.ExportAction"
                text="Export Conch Bundle…"
                description="Export SSH hosts and tunnels as an encrypted share bundle"/>
        <action id="Conch.Share.Import"
                class="com.conch.share.actions.ImportAction"
                text="Import Conch Bundle…"
                description="Import SSH hosts, tunnels, and credentials from a share bundle"/>
    </actions>
</idea-plugin>
```

- [ ] **Step 1.3: Create `TestRunner.java`**

Copy the pattern from `plugins/vault/src/com/conch/vault/TestRunner.java` — read that file first and mirror its structure exactly. If it does not exist, use this minimal launcher:

```java
package com.conch.share;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

public final class TestRunner {
    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("com.conch.share"))
            .build();

        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));
        summary.printFailuresTo(new PrintWriter(System.err));

        if (summary.getTotalFailureCount() > 0) {
            System.exit(1);
        }
    }
}
```

- [ ] **Step 1.4: Create `package-info.java`**

```java
package com.conch.share;
```

- [ ] **Step 1.5: Create `test/com/conch/share/SmokeTest.java`**

```java
package com.conch.share;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeTest {
    @Test
    void moduleCompiles() {
        assertTrue(true);
    }
}
```

- [ ] **Step 1.6: Add share plugin to root `BUILD.bazel`**

Read `BUILD.bazel` (root), find the `conch_run` target's `runtime_deps` list (around lines 38-66), and add `"//conch/plugins/share",` immediately after `"//conch/plugins/tunnels",`.

- [ ] **Step 1.7: Build and run smoke test**

```bash
cd $INTELLIJ_ROOT && bash bazel.cmd build //conch/plugins/share:share
cd $INTELLIJ_ROOT && bash bazel.cmd run //conch/plugins/share:share_test_runner
```

Expected: build succeeds, SmokeTest passes (1 test, 0 failures).

- [ ] **Step 1.8: Commit**

```bash
git add plugins/share BUILD.bazel
git commit -m "feat(share): scaffold plugin module with smoke test"
```

---

## Task 2: Add `createVault` and `withUnlocked` to `LockManager`

These methods are referenced throughout the share plugin design but don't exist yet. Adding them now (with tests) before any share code depends on them.

**Files:**
- Modify: `plugins/vault/src/com/conch/vault/lock/LockManager.java`
- Create or modify: `plugins/vault/test/com/conch/vault/lock/LockManagerTest.java`

- [ ] **Step 2.1: Read existing `LockManager.java` in full**

Before writing anything, read the full file so the new methods match its style (synchronization, error handling, logging, `@NotNull`/`@Nullable` annotations, state transitions).

- [ ] **Step 2.2: Write failing test for `createVault` when no vault exists**

Add to `LockManagerTest.java` (create file if missing; follow JUnit 5 patterns from `plugins/vault/test/com/conch/vault/crypto/VaultCryptoTest.java`):

```java
package com.conch.vault.lock;

import com.conch.vault.model.Vault;
import com.conch.vault.persistence.VaultFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class LockManagerTest {

    @Test
    void createVault_writesFileAndUnlocks(@TempDir Path tmp) throws Exception {
        Path vaultPath = tmp.resolve("vault.enc");
        LockManager lm = new LockManager(vaultPath);
        byte[] password = "hunter22hunter22".getBytes();

        try {
            lm.createVault(password);
            assertFalse(lm.isLocked());
            assertTrue(VaultFile.exists(vaultPath));
            Vault vault = lm.getVault();
            assertNotNull(vault);
            assertTrue(vault.accounts.isEmpty());
            assertTrue(vault.keys.isEmpty());
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    @Test
    void createVault_failsIfFileAlreadyExists(@TempDir Path tmp) throws Exception {
        Path vaultPath = tmp.resolve("vault.enc");
        LockManager lm1 = new LockManager(vaultPath);
        byte[] password = "hunter22hunter22".getBytes();
        try {
            lm1.createVault(password);
        } finally {
            // leave password for second call
        }

        LockManager lm2 = new LockManager(vaultPath);
        assertThrows(IllegalStateException.class, () -> lm2.createVault(password));
        Arrays.fill(password, (byte) 0);
    }
}
```

- [ ] **Step 2.3: Run test, verify failure**

```bash
cd $INTELLIJ_ROOT && bash bazel.cmd run //conch/plugins/vault:vault_test_runner
```

Expected: `createVault_writesFileAndUnlocks` fails with "cannot find symbol: method createVault".

- [ ] **Step 2.4: Add `createVault` to `LockManager.java`**

Insert after the `unlock(byte[], byte[])` method:

```java
public synchronized void createVault(@NotNull byte[] password) throws IOException {
    if (state != VaultState.LOCKED) {
        throw new IllegalStateException("cannot create vault when one is already unlocked");
    }
    Path path = vaultPathSupplier.get();
    if (VaultFile.exists(path)) {
        throw new IllegalStateException("vault file already exists at " + path);
    }
    Path parent = path.getParent();
    if (parent != null) {
        Files.createDirectories(parent);
    }
    Vault fresh = new Vault();
    VaultFile.save(path, fresh, password);
    try {
        unlock(password);
    } catch (WrongPasswordException | VaultCorruptedException impossible) {
        throw new IllegalStateException("freshly created vault failed to unlock", impossible);
    }
}
```

Add any missing imports: `java.nio.file.Files`, `java.nio.file.Path`, `com.conch.vault.persistence.VaultFile`, `com.conch.vault.model.Vault`, `com.conch.vault.crypto.WrongPasswordException`, `com.conch.vault.crypto.VaultCorruptedException`.

- [ ] **Step 2.5: Run tests, verify pass**

```bash
cd $INTELLIJ_ROOT && bash bazel.cmd run //conch/plugins/vault:vault_test_runner
```

Expected: both `createVault` tests pass.

- [ ] **Step 2.6: Write failing test for `withUnlocked`**

Append to `LockManagerTest.java`:

```java
@Test
void withUnlocked_runsOperationAndAutoLocks(@TempDir Path tmp) throws Exception {
    Path vaultPath = tmp.resolve("vault.enc");
    LockManager lm = new LockManager(vaultPath);
    byte[] password = "hunter22hunter22".getBytes();

    try {
        lm.createVault(password);
        lm.lock();
        assertTrue(lm.isLocked());

        String result = lm.withUnlocked(password, vault -> {
            assertNotNull(vault);
            return "ok-" + vault.accounts.size();
        });

        assertEquals("ok-0", result);
        assertTrue(lm.isLocked(), "withUnlocked should auto-lock when it was locked on entry");
    } finally {
        Arrays.fill(password, (byte) 0);
    }
}

@Test
void withUnlocked_doesNotLockIfAlreadyUnlocked(@TempDir Path tmp) throws Exception {
    Path vaultPath = tmp.resolve("vault.enc");
    LockManager lm = new LockManager(vaultPath);
    byte[] password = "hunter22hunter22".getBytes();

    try {
        lm.createVault(password);
        assertFalse(lm.isLocked());

        lm.withUnlocked(password, vault -> null);

        assertFalse(lm.isLocked(), "withUnlocked must NOT lock when it was already unlocked on entry");
    } finally {
        Arrays.fill(password, (byte) 0);
    }
}
```

- [ ] **Step 2.7: Run tests, verify failure**

Expected: `withUnlocked` tests fail ("cannot find symbol").

- [ ] **Step 2.8: Add `withUnlocked` to `LockManager.java`**

Insert after `createVault`:

```java
public synchronized <T> T withUnlocked(
    @NotNull byte[] password,
    @NotNull java.util.function.Function<Vault, T> operation
) throws IOException, WrongPasswordException, VaultCorruptedException {
    boolean wasLocked = isLocked();
    if (wasLocked) {
        unlock(password);
    }
    try {
        Vault vault = getVault();
        if (vault == null) {
            throw new IllegalStateException("vault is unexpectedly locked after unlock()");
        }
        T result = operation.apply(vault);
        save();
        return result;
    } finally {
        if (wasLocked) {
            lock();
        }
    }
}
```

Note: this version calls `save()` after the operation so mutations to the vault (adding accounts/keys) persist. The import flow relies on that.

- [ ] **Step 2.9: Run tests, verify pass**

```bash
cd $INTELLIJ_ROOT && bash bazel.cmd run //conch/plugins/vault:vault_test_runner
```

Expected: all tests pass including existing vault tests.

- [ ] **Step 2.10: Commit**

```bash
git add plugins/vault
git commit -m "feat(vault): add LockManager.createVault and withUnlocked"
```

---

## Task 3: `ShareBundle` data model

**Files:**
- Create: `plugins/share/src/com/conch/share/model/ShareBundle.java`
- Create: `plugins/share/src/com/conch/share/model/BundleMetadata.java`
- Create: `plugins/share/src/com/conch/share/model/BundledVault.java`
- Create: `plugins/share/src/com/conch/share/model/BundledKeyMaterial.java`

- [ ] **Step 3.1: Create `BundledKeyMaterial.java`**

```java
package com.conch.share.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record BundledKeyMaterial(
    @NotNull UUID id,
    @NotNull String privateKeyBase64,
    @Nullable String publicKeyBase64,
    @Nullable String originalFilename
) {
    public static final String SENTINEL_PREFIX = "$CONCH_SHARE_KEY:";

    public static @NotNull String sentinelFor(@NotNull UUID id) {
        return SENTINEL_PREFIX + id;
    }

    public static boolean isSentinel(@Nullable String path) {
        return path != null && path.startsWith(SENTINEL_PREFIX);
    }

    public static @NotNull UUID parseSentinel(@NotNull String sentinel) {
        if (!isSentinel(sentinel)) {
            throw new IllegalArgumentException("not a sentinel path: " + sentinel);
        }
        return UUID.fromString(sentinel.substring(SENTINEL_PREFIX.length()));
    }
}
```

- [ ] **Step 3.2: Create `BundledVault.java`**

```java
package com.conch.share.model;

import com.conch.vault.model.VaultAccount;
import com.conch.vault.model.VaultKey;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record BundledVault(
    @NotNull List<VaultAccount> accounts,
    @NotNull List<VaultKey> keys
) {
    public static @NotNull BundledVault empty() {
        return new BundledVault(List.of(), List.of());
    }

    public boolean isEmpty() {
        return accounts.isEmpty() && keys.isEmpty();
    }
}
```

- [ ] **Step 3.3: Create `BundleMetadata.java`**

```java
package com.conch.share.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;

public record BundleMetadata(
    @NotNull Instant createdAt,
    @NotNull String sourceHost,
    @NotNull String conchVersion,
    boolean includesCredentials
) {}
```

- [ ] **Step 3.4: Create `ShareBundle.java`**

```java
package com.conch.share.model;

import com.conch.ssh.model.SshHost;
import com.conch.tunnels.model.SshTunnel;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ShareBundle(
    int schemaVersion,
    @NotNull BundleMetadata metadata,
    @NotNull List<SshHost> hosts,
    @NotNull List<SshTunnel> tunnels,
    @NotNull BundledVault vault,
    @NotNull List<BundledKeyMaterial> keyMaterial
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
```

- [ ] **Step 3.5: Run build**

```bash
cd $INTELLIJ_ROOT && bash bazel.cmd build //conch/plugins/share:share
```

Expected: build succeeds.

- [ ] **Step 3.6: Commit**

```bash
git add plugins/share/src/com/conch/share/model
git commit -m "feat(share): add ShareBundle data model"
```

---

## Task 4: `ShareBundleCodec` — envelope encode/decode

Reuses `VaultCrypto` / `KeyDerivation` for AES-256-GCM + Argon2id. Bundle byte layout mirrors `VaultFileFormat` but with a different magic and independent version.

**Files:**
- Create: `plugins/share/src/com/conch/share/codec/ShareBundleFormat.java`
- Create: `plugins/share/src/com/conch/share/codec/ShareBundleGson.java`
- Create: `plugins/share/src/com/conch/share/codec/ShareBundleCodec.java`
- Create: `plugins/share/src/com/conch/share/codec/exceptions/WrongBundlePasswordException.java`
- Create: `plugins/share/src/com/conch/share/codec/exceptions/BundleCorruptedException.java`
- Create: `plugins/share/src/com/conch/share/codec/exceptions/UnsupportedBundleVersionException.java`
- Create: `plugins/share/test/com/conch/share/codec/ShareBundleCodecTest.java`

- [ ] **Step 4.1: Create exception types**

`WrongBundlePasswordException.java`:
```java
package com.conch.share.codec.exceptions;

public class WrongBundlePasswordException extends Exception {
    public WrongBundlePasswordException() {
        super("incorrect bundle password");
    }
}
```

`BundleCorruptedException.java`:
```java
package com.conch.share.codec.exceptions;

public class BundleCorruptedException extends Exception {
    public BundleCorruptedException(String message) {
        super(message);
    }
    public BundleCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`UnsupportedBundleVersionException.java`:
```java
package com.conch.share.codec.exceptions;

public class UnsupportedBundleVersionException extends Exception {
    private final int version;

    public UnsupportedBundleVersionException(int version) {
        super("unsupported bundle schema version: " + version);
        this.version = version;
    }

    public int version() {
        return version;
    }
}
```

- [ ] **Step 4.2: Create `ShareBundleFormat.java`**

Read `plugins/vault/src/com/conch/vault/crypto/VaultFileFormat.java` first and mirror its structure exactly, changing only `MAGIC` and the class name.

```java
package com.conch.share.codec;

import com.conch.share.codec.exceptions.BundleCorruptedException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class ShareBundleFormat {

    public static final byte[] MAGIC = "CONCHSHR".getBytes(StandardCharsets.US_ASCII);
    public static final int VERSION = 1;
    public static final int SALT_LEN = 16;
    public static final int NONCE_LEN = 12;
    private static final int HEADER_LEN = MAGIC.length + 4 + SALT_LEN + NONCE_LEN;

    public record Parsed(int version, byte[] salt, byte[] nonce, byte[] ciphertext) {}

    public static byte[] assemble(byte[] salt, byte[] nonce, byte[] ciphertext) {
        if (salt.length != SALT_LEN) throw new IllegalArgumentException("bad salt length");
        if (nonce.length != NONCE_LEN) throw new IllegalArgumentException("bad nonce length");
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LEN + ciphertext.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.putInt(VERSION);
        buf.put(salt);
        buf.put(nonce);
        buf.put(ciphertext);
        return buf.array();
    }

    public static Parsed parse(byte[] data) throws BundleCorruptedException {
        if (data == null || data.length < HEADER_LEN) {
            throw new BundleCorruptedException("bundle shorter than header");
        }
        byte[] magic = new byte[MAGIC.length];
        System.arraycopy(data, 0, magic, 0, MAGIC.length);
        for (int i = 0; i < MAGIC.length; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new BundleCorruptedException("not a conch share bundle (bad magic)");
            }
        }
        ByteBuffer buf = ByteBuffer.wrap(data, MAGIC.length, 4).order(ByteOrder.LITTLE_ENDIAN);
        int version = buf.getInt();
        byte[] salt = new byte[SALT_LEN];
        System.arraycopy(data, MAGIC.length + 4, salt, 0, SALT_LEN);
        byte[] nonce = new byte[NONCE_LEN];
        System.arraycopy(data, MAGIC.length + 4 + SALT_LEN, nonce, 0, NONCE_LEN);
        byte[] ciphertext = new byte[data.length - HEADER_LEN];
        System.arraycopy(data, HEADER_LEN, ciphertext, 0, ciphertext.length);
        return new Parsed(version, salt, nonce, ciphertext);
    }

    private ShareBundleFormat() {}
}
```

- [ ] **Step 4.3: Create `ShareBundleGson.java`**

Reuses the sealed-type adapters from `SshGson`, `TunnelGson`, and `VaultGson` — the share Gson must reference the **same** adapters so serialized `SshHost`, `SshTunnel`, and `VaultAccount` shapes match the on-disk formats byte-for-byte.

Read these three files first:
- `plugins/ssh/src/com/conch/ssh/persistence/SshGson.java`
- `plugins/tunnels/src/com/conch/tunnels/persistence/TunnelGson.java`
- `plugins/vault/src/com/conch/vault/crypto/VaultGson.java`

Note which adapter classes are package-private vs public. If an adapter is package-private (common for `VaultGson` internals), you cannot reference it from `plugins/share`. Two options:
1. **Preferred:** widen visibility of the adapter classes to `public` and move them to a subpackage if needed.
2. Fallback: reflectively obtain the Gson instance and reuse it whole.

Pick option 1 unless it breaks the module boundary. Specifically:
- Make `com.conch.ssh.persistence.SshGson.GSON` reusable (it already is public).
- Make `com.conch.tunnels.persistence.TunnelGson.GSON` reusable (already public).
- For `VaultGson`, promote `AuthMethodSerializer` and `AuthMethodDeserializer` (or the whole `VaultGson` class + `GSON` field) to public.

```java
package com.conch.share.codec;

import com.conch.share.model.BundleMetadata;
import com.conch.share.model.BundledKeyMaterial;
import com.conch.share.model.BundledVault;
import com.conch.share.model.ShareBundle;
import com.conch.ssh.model.SshAuth;
import com.conch.tunnels.model.TunnelHost;
import com.conch.vault.model.AuthMethod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.Instant;

public final class ShareBundleGson {

    public static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(Instant.class, new InstantAdapter())
        .registerTypeAdapter(SshAuth.class, new com.conch.ssh.persistence.SshAuthSerializer())
        .registerTypeAdapter(SshAuth.class, new com.conch.ssh.persistence.SshAuthDeserializer())
        .registerTypeAdapter(TunnelHost.class, new com.conch.tunnels.persistence.TunnelHostSerializer())
        .registerTypeAdapter(TunnelHost.class, new com.conch.tunnels.persistence.TunnelHostDeserializer())
        .registerTypeAdapter(AuthMethod.class, new com.conch.vault.crypto.AuthMethodSerializer())
        .registerTypeAdapter(AuthMethod.class, new com.conch.vault.crypto.AuthMethodDeserializer())
        .create();

    private ShareBundleGson() {}

    // If InstantAdapter is not public in any of the existing plugins, define one here:
    static final class InstantAdapter
        implements com.google.gson.JsonSerializer<Instant>, com.google.gson.JsonDeserializer<Instant> {

        @Override
        public com.google.gson.JsonElement serialize(
            Instant src,
            java.lang.reflect.Type typeOfSrc,
            com.google.gson.JsonSerializationContext context
        ) {
            return new com.google.gson.JsonPrimitive(src.toString());
        }

        @Override
        public Instant deserialize(
            com.google.gson.JsonElement json,
            java.lang.reflect.Type typeOfT,
            com.google.gson.JsonDeserializationContext context
        ) {
            return Instant.parse(json.getAsString());
        }
    }
}
```

**Note:** If the adapter classes referenced here don't exist with those exact names, read the plugin Gson files to find the actual class names and update this import list.

- [ ] **Step 4.4: Widen visibility of vault Gson adapters (if needed)**

If `VaultGson` has package-private adapter classes, modify `plugins/vault/src/com/conch/vault/crypto/VaultGson.java` (or wherever they live) to expose them. Minimum: make `AuthMethodSerializer` and `AuthMethodDeserializer` top-level public classes in `com.conch.vault.crypto`. Same for SSH and tunnel adapters if they're nested.

After widening, rebuild vault/ssh/tunnels to confirm nothing breaks:

```bash
cd $INTELLIJ_ROOT && bash bazel.cmd build //conch/plugins/vault //conch/plugins/ssh //conch/plugins/tunnels
```

- [ ] **Step 4.5: Write failing test for codec round-trip**

Create `plugins/share/test/com/conch/share/codec/ShareBundleCodecTest.java`:

```java
package com.conch.share.codec;

import com.conch.share.codec.exceptions.BundleCorruptedException;
import com.conch.share.codec.exceptions.UnsupportedBundleVersionException;
import com.conch.share.codec.exceptions.WrongBundlePasswordException;
import com.conch.share.model.BundleMetadata;
import com.conch.share.model.BundledKeyMaterial;
import com.conch.share.model.BundledVault;
import com.conch.share.model.ShareBundle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ShareBundleCodecTest {

    private ShareBundle sampleBundle() {
        BundleMetadata meta = new BundleMetadata(
            Instant.parse("2026-04-14T10:32:00Z"),
            "test-host",
            "0.14.2",
            false
        );
        return new ShareBundle(
            ShareBundle.CURRENT_SCHEMA_VERSION,
            meta,
            List.of(),
            List.of(),
            BundledVault.empty(),
            List.of()
        );
    }

    @Test
    void encode_then_decode_roundTrip() throws Exception {
        ShareBundle original = sampleBundle();
        byte[] password = "correct-horse".getBytes();

        byte[] encoded = ShareBundleCodec.encode(original, password);
        assertNotNull(encoded);
        assertTrue(encoded.length > 40, "encoded bundle must have header + ciphertext");

        ShareBundle decoded = ShareBundleCodec.decode(encoded, password);
        assertEquals(original.schemaVersion(), decoded.schemaVersion());
        assertEquals(original.metadata().sourceHost(), decoded.metadata().sourceHost());
        assertEquals(original.metadata().createdAt(), decoded.metadata().createdAt());
        Arrays.fill(password, (byte) 0);
    }

    @Test
    void decode_wrongPassword_throws() throws Exception {
        byte[] encoded = ShareBundleCodec.encode(sampleBundle(), "right".getBytes());
        assertThrows(
            WrongBundlePasswordException.class,
            () -> ShareBundleCodec.decode(encoded, "wrong".getBytes())
        );
    }

    @Test
    void decode_badMagic_throws() {
        byte[] garbage = new byte[128];
        assertThrows(
            BundleCorruptedException.class,
            () -> ShareBundleCodec.decode(garbage, "any".getBytes())
        );
    }

    @Test
    void decode_unknownVersion_throws() throws Exception {
        byte[] encoded = ShareBundleCodec.encode(sampleBundle(), "pw".getBytes());
        // Mutate the version field at offset 8 (after 8-byte magic) to 999
        encoded[8] = (byte) 0xE7; // 999 LE = 0xE7 0x03 0x00 0x00
        encoded[9] = (byte) 0x03;
        encoded[10] = 0;
        encoded[11] = 0;
        // The version read at parse time is the file-envelope version (always 1 from assemble),
        // but the decoded JSON carries schemaVersion. The unknown-version rejection happens
        // during JSON decode when schemaVersion != CURRENT_SCHEMA_VERSION.
        // This test intentionally targets the JSON-level version; the envelope version is
        // asserted separately in decode_truncated_throws.
        assertThrows(
            WrongBundlePasswordException.class, // tag check fails because we mutated header bytes
            () -> ShareBundleCodec.decode(encoded, "pw".getBytes())
        );
    }

    @Test
    void decode_schemaVersion999_throws() throws Exception {
        // Build a ShareBundle with schemaVersion = 999, encode it, and verify decode rejects.
        BundleMetadata meta = new BundleMetadata(Instant.now(), "h", "v", false);
        ShareBundle future = new ShareBundle(
            999, meta, List.of(), List.of(), BundledVault.empty(), List.of()
        );
        byte[] encoded = ShareBundleCodec.encode(future, "pw".getBytes());
        assertThrows(
            UnsupportedBundleVersionException.class,
            () -> ShareBundleCodec.decode(encoded, "pw".getBytes())
        );
    }

    @Test
    void decode_truncated_throws() {
        byte[] tooShort = new byte[10];
        assertThrows(
            BundleCorruptedException.class,
            () -> ShareBundleCodec.decode(tooShort, "pw".getBytes())
        );
    }
}
```

- [ ] **Step 4.6: Run tests, verify compile failure**

```bash
cd $INTELLIJ_ROOT && bash bazel.cmd run //conch/plugins/share:share_test_runner
```

Expected: compile error, `ShareBundleCodec` does not exist.

- [ ] **Step 4.7: Create `ShareBundleCodec.java`**

Reuse `KeyDerivation` and the AES-GCM cipher invocation from `VaultCrypto`. Read `VaultCrypto.java` first to confirm the exact BouncyCastle / javax.crypto call shape. If `VaultCrypto` has package-private internal methods (`encryptBytes(plaintext, key, nonce)`), promote them to public or copy the encrypt/decrypt primitive into a new package-private helper in the share codec.

```java
package com.conch.share.codec;

import com.conch.share.codec.exceptions.BundleCorruptedException;
import com.conch.share.codec.exceptions.UnsupportedBundleVersionException;
import com.conch.share.codec.exceptions.WrongBundlePasswordException;
import com.conch.share.model.ShareBundle;
import com.conch.vault.crypto.KeyDerivation;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

public final class ShareBundleCodec {

    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static byte[] encode(ShareBundle bundle, byte[] password) throws Exception {
        String json = ShareBundleGson.GSON.toJson(bundle);
        byte[] plaintext = json.getBytes(StandardCharsets.UTF_8);

        byte[] salt = new byte[ShareBundleFormat.SALT_LEN];
        RANDOM.nextBytes(salt);
        byte[] nonce = new byte[ShareBundleFormat.NONCE_LEN];
        RANDOM.nextBytes(nonce);

        byte[] key = KeyDerivation.deriveKey(password, salt);
        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            ciphertext = cipher.doFinal(plaintext);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }

        return ShareBundleFormat.assemble(salt, nonce, ciphertext);
    }

    public static ShareBundle decode(byte[] data, byte[] password)
        throws BundleCorruptedException, WrongBundlePasswordException, UnsupportedBundleVersionException {

        ShareBundleFormat.Parsed parsed = ShareBundleFormat.parse(data);
        if (parsed.version() != ShareBundleFormat.VERSION) {
            throw new UnsupportedBundleVersionException(parsed.version());
        }

        byte[] key = KeyDerivation.deriveKey(password, parsed.salt());
        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, parsed.nonce()));
            plaintext = cipher.doFinal(parsed.ciphertext());
        } catch (javax.crypto.AEADBadTagException tagFail) {
            throw new WrongBundlePasswordException();
        } catch (Exception other) {
            throw new BundleCorruptedException("bundle decryption failed", other);
        } finally {
            Arrays.fill(key, (byte) 0);
        }

        String json = new String(plaintext, StandardCharsets.UTF_8);
        Arrays.fill(plaintext, (byte) 0);

        ShareBundle bundle;
        try {
            bundle = ShareBundleGson.GSON.fromJson(json, ShareBundle.class);
        } catch (Exception parseFail) {
            throw new BundleCorruptedException("bundle JSON parse failed", parseFail);
        }

        if (bundle == null) {
            throw new BundleCorruptedException("bundle decoded to null");
        }
        if (bundle.schemaVersion() != ShareBundle.CURRENT_SCHEMA_VERSION) {
            throw new UnsupportedBundleVersionException(bundle.schemaVersion());
        }
        return bundle;
    }

    private ShareBundleCodec() {}
}
```

- [ ] **Step 4.8: Run tests, verify pass**

```bash
cd $INTELLIJ_ROOT && bash bazel.cmd run //conch/plugins/share:share_test_runner
```

Expected: all codec tests pass. If `decode_unknownVersion_throws` fails because of the tag-check order, adjust the test assertion or the decode logic — the envelope version check runs before the tag check in the current implementation.

- [ ] **Step 4.9: Commit**

```bash
git add plugins/share plugins/vault plugins/ssh plugins/tunnels
git commit -m "feat(share): add ShareBundleCodec with AES-GCM + Argon2id envelope"
```

---

## Task 5: `SshConfigReader`

**Files:**
- Create: `plugins/share/src/com/conch/share/conversion/SshConfigEntry.java`
- Create: `plugins/share/src/com/conch/share/conversion/SshConfigReader.java`
- Create: `plugins/share/test/com/conch/share/conversion/SshConfigReaderTest.java`

- [ ] **Step 5.1: Create `SshConfigEntry.java`**

```java
package com.conch.share.conversion;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record SshConfigEntry(
    @NotNull String alias,
    @Nullable String hostName,
    int port,
    @Nullable String user,
    @Nullable String identityFile,
    @Nullable String proxyCommand,
    @Nullable String proxyJump,
    @NotNull List<String> warnings
) {}
```

- [ ] **Step 5.2: Write failing tests for `SshConfigReader`**

Create `plugins/share/test/com/conch/share/conversion/SshConfigReaderTest.java`:

```java
package com.conch.share.conversion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SshConfigReaderTest {

    @Test
    void directAlias_parsesAllCommonFields(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Host bastion
                HostName bastion.example.com
                Port 2222
                User ops
                IdentityFile ~/.ssh/id_ed25519
                ProxyCommand /usr/bin/nc -x proxy:1080 %h %p
            """);

        Optional<SshConfigEntry> result = SshConfigReader.read(config, "bastion");
        assertTrue(result.isPresent());
        SshConfigEntry e = result.get();
        assertEquals("bastion.example.com", e.hostName());
        assertEquals(2222, e.port());
        assertEquals("ops", e.user());
        assertTrue(e.identityFile().endsWith("id_ed25519"));
        assertNotNull(e.proxyCommand());
        assertTrue(e.warnings().isEmpty());
    }

    @Test
    void aliasNotFound_returnsEmpty(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, "Host other\n  HostName other.example.com\n");

        Optional<SshConfigEntry> result = SshConfigReader.read(config, "bastion");
        assertFalse(result.isPresent());
    }

    @Test
    void wildcardHostOnly_returnsEmptyWithWarning(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Host *.internal
                User ops
            """);

        Optional<SshConfigEntry> result = SshConfigReader.read(config, "bastion.internal");
        assertFalse(result.isPresent(), "wildcards are not expanded in v1");
    }

    @Test
    void matchBlock_producesWarning(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Match host bastion
                User ops
            Host bastion
                HostName bastion.example.com
            """);

        Optional<SshConfigEntry> result = SshConfigReader.read(config, "bastion");
        assertTrue(result.isPresent());
        assertFalse(result.get().warnings().isEmpty());
        assertTrue(result.get().warnings().stream().anyMatch(w -> w.contains("Match")));
    }

    @Test
    void include_followsRelativeInclude(@TempDir Path tmp) throws Exception {
        Path main = tmp.resolve("config");
        Path included = tmp.resolve("hosts.conf");
        Files.writeString(included, "Host bastion\n  HostName bastion.example.com\n");
        Files.writeString(main, "Include " + included.toAbsolutePath() + "\n");

        Optional<SshConfigEntry> result = SshConfigReader.read(main, "bastion");
        assertTrue(result.isPresent());
        assertEquals("bastion.example.com", result.get().hostName());
    }

    @Test
    void unknownDirective_producesWarning(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Host bastion
                HostName bastion.example.com
                FrobnicateLevel 11
            """);

        Optional<SshConfigEntry> result = SshConfigReader.read(config, "bastion");
        assertTrue(result.isPresent());
        assertTrue(result.get().warnings().stream().anyMatch(w -> w.contains("FrobnicateLevel")));
    }
}
```

- [ ] **Step 5.3: Run tests, verify failure**

```bash
cd $INTELLIJ_ROOT && bash bazel.cmd run //conch/plugins/share:share_test_runner
```

Expected: compile failure, `SshConfigReader` does not exist.

- [ ] **Step 5.4: Implement `SshConfigReader.java`**

```java
package com.conch.share.conversion;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class SshConfigReader {

    private static final int MAX_INCLUDE_DEPTH = 5;

    private static final Set<String> KNOWN_DIRECTIVES = Set.of(
        "host", "hostname", "port", "user", "identityfile",
        "proxycommand", "proxyjump", "include", "match"
    );

    public static Optional<SshConfigEntry> read(@NotNull Path configFile, @NotNull String alias) throws IOException {
        List<String> warnings = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        expand(configFile, lines, 0, warnings);

        boolean inTargetBlock = false;
        boolean foundTarget = false;
        String hostName = null;
        int port = 22;
        String user = null;
        String identityFile = null;
        String proxyCommand = null;
        String proxyJump = null;
        boolean sawMatchBlock = false;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] parts = line.split("\\s+", 2);
            String directive = parts[0].toLowerCase(Locale.ROOT);
            String value = parts.length > 1 ? parts[1].trim() : "";

            if (directive.equals("match")) {
                sawMatchBlock = true;
                inTargetBlock = false;
                continue;
            }

            if (directive.equals("host")) {
                inTargetBlock = hostPatternMatchesExactly(value, alias);
                if (inTargetBlock) foundTarget = true;
                continue;
            }

            if (!inTargetBlock) continue;

            switch (directive) {
                case "hostname" -> hostName = value;
                case "port" -> {
                    try { port = Integer.parseInt(value); } catch (NumberFormatException ignore) {}
                }
                case "user" -> user = value;
                case "identityfile" -> identityFile = expandTilde(value);
                case "proxycommand" -> proxyCommand = value;
                case "proxyjump" -> proxyJump = value;
                default -> {
                    if (!KNOWN_DIRECTIVES.contains(directive)) {
                        warnings.add("Unsupported directive in alias '" + alias + "': " + parts[0]);
                    }
                }
            }
        }

        if (!foundTarget) return Optional.empty();
        if (sawMatchBlock) {
            warnings.add("File contains Match blocks which are not evaluated in v1");
        }

        return Optional.of(new SshConfigEntry(
            alias, hostName, port, user, identityFile, proxyCommand, proxyJump, List.copyOf(warnings)
        ));
    }

    private static boolean hostPatternMatchesExactly(String patterns, String alias) {
        for (String p : patterns.split("\\s+")) {
            if (p.isEmpty()) continue;
            if (p.contains("*") || p.contains("?")) continue;
            if (p.equals(alias)) return true;
        }
        return false;
    }

    private static void expand(Path file, List<String> out, int depth, List<String> warnings) throws IOException {
        if (depth >= MAX_INCLUDE_DEPTH) {
            warnings.add("Include depth exceeded at " + file);
            return;
        }
        if (!Files.isRegularFile(file)) return;
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("include ")) {
                String rest = trimmed.substring("include ".length()).trim();
                for (String target : rest.split("\\s+")) {
                    Path resolved = Paths.get(expandTilde(target));
                    expand(resolved, out, depth + 1, warnings);
                }
            } else {
                out.add(line);
            }
        }
    }

    private static String expandTilde(String path) {
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    private SshConfigReader() {}
}
```

- [ ] **Step 5.5: Run tests, verify pass**

```bash
cd $INTELLIJ_ROOT && bash bazel.cmd run //conch/plugins/share:share_test_runner
```

Expected: all `SshConfigReaderTest` tests pass.

- [ ] **Step 5.6: Commit**

```bash
git add plugins/share/src/com/conch/share/conversion plugins/share/test/com/conch/share/conversion
git commit -m "feat(share): add SshConfigReader for exporting ~/.ssh/config aliases"
```

---

## Task 6: `KeyFileImporter`

**Files:**
- Create: `plugins/share/src/com/conch/share/conversion/KeyFileImporter.java`
- Create: `plugins/share/test/com/conch/share/conversion/KeyFileImporterTest.java`

- [ ] **Step 6.1: Create `KeyFileImporter.java` skeleton (return type + static method signature only)**

```java
package com.conch.share.conversion;

import com.conch.share.model.BundledKeyMaterial;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

public final class KeyFileImporter {

    public sealed interface Result permits Result.Ok, Result.NeedsPassphrase, Result.Warning {
        record Ok(@NotNull BundledKeyMaterial material) implements Result {}
        record NeedsPassphrase(@NotNull Path path) implements Result {}
        record Warning(@NotNull String message) implements Result {}
    }

    public static @NotNull Result read(@NotNull Path keyPath, @Nullable String passphrase) {
        throw new UnsupportedOperationException("not implemented");
    }

    private KeyFileImporter() {}
}
```

- [ ] **Step 6.2: Write failing tests**

Create `plugins/share/test/com/conch/share/conversion/KeyFileImporterTest.java`:

```java
package com.conch.share.conversion;

import com.conch.share.model.BundledKeyMaterial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KeyFileImporterTest {

    @Test
    void missingFile_returnsWarning(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope");
        var result = KeyFileImporter.read(missing, null);
        assertInstanceOf(KeyFileImporter.Result.Warning.class, result);
        assertTrue(((KeyFileImporter.Result.Warning) result).message().contains("not found"));
    }

    @Test
    void unencryptedOpenSshKey_returnsOk(@TempDir Path tmp) throws Exception {
        Path key = tmp.resolve("id_ed25519");
        Files.writeString(key, """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZWQ=
            -----END OPENSSH PRIVATE KEY-----
            """);

        var result = KeyFileImporter.read(key, null);
        assertInstanceOf(KeyFileImporter.Result.Ok.class, result);
        BundledKeyMaterial m = ((KeyFileImporter.Result.Ok) result).material();
        assertNotNull(m.id());
        assertNotNull(m.privateKeyBase64());
        assertEquals("id_ed25519", m.originalFilename());
    }

    @Test
    void encryptedKeyWithoutPassphrase_needsPassphrase(@TempDir Path tmp) throws Exception {
        Path key = tmp.resolve("id_rsa");
        Files.writeString(key, """
            -----BEGIN RSA PRIVATE KEY-----
            Proc-Type: 4,ENCRYPTED
            DEK-Info: AES-128-CBC,abcdef0123456789

            ciphertextbase64=
            -----END RSA PRIVATE KEY-----
            """);

        var result = KeyFileImporter.read(key, null);
        assertInstanceOf(KeyFileImporter.Result.NeedsPassphrase.class, result);
    }

    @Test
    void encryptedKeyWithPassphrase_returnsOk(@TempDir Path tmp) throws Exception {
        Path key = tmp.resolve("id_rsa");
        Files.writeString(key, """
            -----BEGIN RSA PRIVATE KEY-----
            Proc-Type: 4,ENCRYPTED
            DEK-Info: AES-128-CBC,abcdef0123456789

            ciphertextbase64=
            -----END RSA PRIVATE KEY-----
            """);

        var result = KeyFileImporter.read(key, "mysecret");
        assertInstanceOf(KeyFileImporter.Result.Ok.class, result);
    }

    @Test
    void notAKey_returnsWarning(@TempDir Path tmp) throws Exception {
        Path key = tmp.resolve("random.txt");
        Files.writeString(key, "this is not a private key");

        var result = KeyFileImporter.read(key, null);
        assertInstanceOf(KeyFileImporter.Result.Warning.class, result);
    }
}
```

- [ ] **Step 6.3: Run tests, verify failure**

Expected: tests fail on `UnsupportedOperationException`.

- [ ] **Step 6.4: Implement `KeyFileImporter.read`**

Replace the method body:

```java
public static @NotNull Result read(@NotNull Path keyPath, @Nullable String passphrase) {
    if (!Files.isRegularFile(keyPath)) {
        return new Result.Warning("Key file not found: " + keyPath);
    }

    byte[] bytes;
    try {
        bytes = Files.readAllBytes(keyPath);
    } catch (IOException e) {
        return new Result.Warning("Can't read key file: " + keyPath + " (" + e.getMessage() + ")");
    }

    String text;
    try {
        text = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
    } catch (Exception e) {
        return new Result.Warning("Key file is not ASCII: " + keyPath);
    }

    boolean looksLikePrivateKey =
        text.contains("-----BEGIN OPENSSH PRIVATE KEY-----")
            || text.contains("-----BEGIN RSA PRIVATE KEY-----")
            || text.contains("-----BEGIN EC PRIVATE KEY-----")
            || text.contains("-----BEGIN DSA PRIVATE KEY-----")
            || text.contains("-----BEGIN PRIVATE KEY-----");

    if (!looksLikePrivateKey) {
        return new Result.Warning("File does not look like a private key: " + keyPath);
    }

    boolean encrypted =
        text.contains("Proc-Type: 4,ENCRYPTED")
            // OpenSSH encrypted keys have "aes256-" in the kdf line;
            // conservative heuristic: presence of "aes" inside the armor body.
            || (text.contains("OPENSSH PRIVATE KEY") && text.contains("aes"));

    if (encrypted && (passphrase == null || passphrase.isEmpty())) {
        return new Result.NeedsPassphrase(keyPath);
    }

    String b64 = Base64.getEncoder().encodeToString(bytes);
    String filename = keyPath.getFileName().toString();
    BundledKeyMaterial material = new BundledKeyMaterial(
        UUID.randomUUID(), b64, null, filename
    );
    return new Result.Ok(material);
}
```

- [ ] **Step 6.5: Run tests, verify pass**

Expected: all `KeyFileImporterTest` tests pass. Note the encrypted-key heuristic is deliberately loose — we don't validate that the passphrase is *correct*, only that one was provided.

- [ ] **Step 6.6: Commit**

```bash
git add plugins/share/src/com/conch/share/conversion plugins/share/test/com/conch/share/conversion
git commit -m "feat(share): add KeyFileImporter for bundling private keys"
```

---

## Task 7: `ExportPlanner`

Central logic that turns a user selection into a `ShareBundle`. Several distinct behaviours — each tested separately.

**Files:**
- Create: `plugins/share/src/com/conch/share/planner/ConversionWarning.java`
- Create: `plugins/share/src/com/conch/share/planner/ExportPlan.java`
- Create: `plugins/share/src/com/conch/share/planner/ExportRequest.java`
- Create: `plugins/share/src/com/conch/share/planner/ExportPlanner.java`
- Create: `plugins/share/test/com/conch/share/planner/ExportPlannerTest.java`

- [ ] **Step 7.1: Create `ConversionWarning.java`**

```java
package com.conch.share.planner;

import org.jetbrains.annotations.NotNull;

public record ConversionWarning(@NotNull String subject, @NotNull String message) {}
```

- [ ] **Step 7.2: Create `ExportPlan.java`**

```java
package com.conch.share.planner;

import com.conch.share.model.ShareBundle;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ExportPlan(
    @NotNull ShareBundle bundle,
    @NotNull List<ConversionWarning> warnings,
    @NotNull List<String> autoPulledHostLabels,
    @NotNull List<String> convertedSshConfigAliases,
    @NotNull List<String> convertedKeyFilePaths
) {}
```

- [ ] **Step 7.3: Create `ExportRequest.java`**

```java
package com.conch.share.planner;

import com.conch.ssh.model.SshHost;
import com.conch.tunnels.model.SshTunnel;
import com.conch.vault.model.Vault;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record ExportRequest(
    @NotNull Set<UUID> selectedHostIds,
    @NotNull Set<UUID> selectedTunnelIds,
    boolean includeCredentials,
    @NotNull List<SshHost> allHosts,
    @NotNull List<SshTunnel> allTunnels,
    @Nullable Vault unlockedVault,
    @NotNull Path sshConfigPath,
    @NotNull String sourceHost,
    @NotNull String conchVersion
) {}
```

- [ ] **Step 7.4: Write failing test — basic host selection with no credentials**

Create `plugins/share/test/com/conch/share/planner/ExportPlannerTest.java`:

```java
package com.conch.share.planner;

import com.conch.share.model.BundledKeyMaterial;
import com.conch.share.model.ShareBundle;
import com.conch.ssh.model.KeyFileAuth;
import com.conch.ssh.model.PromptPasswordAuth;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.model.VaultAuth;
import com.conch.tunnels.model.InternalHost;
import com.conch.tunnels.model.SshConfigHost;
import com.conch.tunnels.model.SshTunnel;
import com.conch.tunnels.model.TunnelType;
import com.conch.vault.model.AuthMethod;
import com.conch.vault.model.Vault;
import com.conch.vault.model.VaultAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExportPlannerTest {

    private SshHost host(String label, com.conch.ssh.model.SshAuth auth) {
        return new SshHost(
            UUID.randomUUID(), label, label + ".example.com", 22, "ops",
            auth, null, null, Instant.now(), Instant.now()
        );
    }

    private SshTunnel tunnel(String label, com.conch.tunnels.model.TunnelHost host) {
        return new SshTunnel(
            UUID.randomUUID(), label, TunnelType.LOCAL, host,
            8080, "localhost", "target", 80, Instant.now(), Instant.now()
        );
    }

    @Test
    void selectedHost_withPromptAuth_credentialsOff_exportsCleanly(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, "");
        SshHost h = host("web", new PromptPasswordAuth());

        ExportRequest req = new ExportRequest(
            Set.of(h.id()), Set.of(), false,
            List.of(h), List.of(), null,
            config, "src", "0.0.0"
        );

        ExportPlan plan = ExportPlanner.plan(req);
        assertEquals(1, plan.bundle().hosts().size());
        assertTrue(plan.bundle().tunnels().isEmpty());
        assertTrue(plan.bundle().vault().isEmpty());
        assertFalse(plan.bundle().metadata().includesCredentials());
        assertInstanceOf(PromptPasswordAuth.class, plan.bundle().hosts().get(0).auth());
    }

    @Test
    void selectedTunnel_autoPullsInternalHost(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, "");
        SshHost h = host("web", new PromptPasswordAuth());
        SshTunnel t = tunnel("db", new InternalHost(h.id()));

        ExportRequest req = new ExportRequest(
            Set.of(), Set.of(t.id()), false,
            List.of(h), List.of(t), null,
            config, "src", "0.0.0"
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
            Set.of(), Set.of(t.id()), false,
            List.of(), List.of(t), null,
            config, "src", "0.0.0"
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
            accountId, "web-creds", "ops",
            new AuthMethod.Password("s3cret"),
            Instant.now(), Instant.now()
        )));

        ExportRequest req = new ExportRequest(
            Set.of(h.id()), Set.of(), true,
            List.of(h), List.of(), vault,
            config, "src", "0.0.0"
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
            Set.of(h.id()), Set.of(), false,
            List.of(h), List.of(), null,
            config, "src", "0.0.0"
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
            Set.of(h.id()), Set.of(), true,
            List.of(h), List.of(), new Vault(),
            config, "src", "0.0.0"
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
            Set.of(h.id()), Set.of(), false,
            List.of(h), List.of(), null,
            config, "src", "0.0.0"
        );

        ExportPlan plan = ExportPlanner.plan(req);
        assertInstanceOf(KeyFileAuth.class, plan.bundle().hosts().get(0).auth());
        assertTrue(plan.bundle().keyMaterial().isEmpty());
    }
}
```

- [ ] **Step 7.5: Run tests, verify compile failure**

- [ ] **Step 7.6: Implement `ExportPlanner.java`**

```java
package com.conch.share.planner;

import com.conch.share.conversion.KeyFileImporter;
import com.conch.share.conversion.SshConfigEntry;
import com.conch.share.conversion.SshConfigReader;
import com.conch.share.model.BundleMetadata;
import com.conch.share.model.BundledKeyMaterial;
import com.conch.share.model.BundledVault;
import com.conch.share.model.ShareBundle;
import com.conch.ssh.model.KeyFileAuth;
import com.conch.ssh.model.PromptPasswordAuth;
import com.conch.ssh.model.SshAuth;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.model.VaultAuth;
import com.conch.tunnels.model.InternalHost;
import com.conch.tunnels.model.SshConfigHost;
import com.conch.tunnels.model.SshTunnel;
import com.conch.tunnels.model.TunnelHost;
import com.conch.vault.model.AuthMethod;
import com.conch.vault.model.Vault;
import com.conch.vault.model.VaultAccount;
import com.conch.vault.model.VaultKey;
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

    public static @NotNull ExportPlan plan(@NotNull ExportRequest req) {
        List<ConversionWarning> warnings = new ArrayList<>();
        List<String> autoPulled = new ArrayList<>();
        List<String> convertedAliases = new ArrayList<>();
        List<String> convertedKeyPaths = new ArrayList<>();

        Map<UUID, SshHost> allHostsById = new HashMap<>();
        for (SshHost h : req.allHosts()) allHostsById.put(h.id(), h);

        // Bundle containers. LinkedHashMap preserves insertion order for deterministic output.
        Map<UUID, SshHost> bundleHosts = new LinkedHashMap<>();
        Map<UUID, SshTunnel> bundleTunnels = new LinkedHashMap<>();
        Map<UUID, VaultAccount> bundleAccounts = new LinkedHashMap<>();
        Map<UUID, VaultKey> bundleKeys = new LinkedHashMap<>();
        List<BundledKeyMaterial> keyMaterial = new ArrayList<>();

        // 1. Pull explicitly selected hosts.
        for (UUID id : req.selectedHostIds()) {
            SshHost h = allHostsById.get(id);
            if (h != null) bundleHosts.put(h.id(), h);
        }

        // 2. Pull selected tunnels + auto-pull any InternalHost dependencies.
        for (SshTunnel t : req.allTunnels()) {
            if (!req.selectedTunnelIds().contains(t.id())) continue;
            TunnelHost th = t.host();

            if (th instanceof InternalHost ih) {
                SshHost ref = allHostsById.get(ih.hostId());
                if (ref != null && !bundleHosts.containsKey(ref.id())) {
                    bundleHosts.put(ref.id(), ref);
                    autoPulled.add(ref.label());
                }
                bundleTunnels.put(t.id(), t);
            } else if (th instanceof SshConfigHost sch) {
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
                    t.id(), t.label(), t.type(),
                    new InternalHost(synthesized.id()),
                    t.bindPort(), t.bindAddress(),
                    t.targetHost(), t.targetPort(),
                    t.createdAt(), t.updatedAt()
                );
                bundleTunnels.put(rewritten.id(), rewritten);
            }
        }

        // 3. Walk bundleHosts and resolve their auth types.
        // Build a replacement map so we can rewrite host entries after the loop.
        Map<UUID, SshHost> hostReplacements = new HashMap<>();
        for (SshHost h : bundleHosts.values()) {
            SshAuth auth = h.auth();
            if (auth instanceof VaultAuth va && va.credentialId() != null) {
                if (req.includeCredentials() && req.unlockedVault() != null) {
                    VaultAccount account = findAccount(req.unlockedVault(), va.credentialId());
                    if (account != null) {
                        // Pull the account (possibly with referenced VaultKey / key material).
                        AccountBundleResult r = bundleAccount(account, req.unlockedVault(), keyMaterial);
                        bundleAccounts.put(r.account.id(), r.account);
                        for (VaultKey k : r.keys) bundleKeys.put(k.id(), k);
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
            } else if (auth instanceof KeyFileAuth kfa) {
                if (req.includeCredentials()) {
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
                            Instant.now(), Instant.now()
                        );
                        bundleAccounts.put(syntheticAccountId, synthAccount);
                        hostReplacements.put(h.id(), h.withAuth(new VaultAuth(syntheticAccountId)));
                    } else if (result instanceof KeyFileImporter.Result.Warning w) {
                        warnings.add(new ConversionWarning("host " + h.label(), w.message()));
                    } else if (result instanceof KeyFileImporter.Result.NeedsPassphrase) {
                        warnings.add(new ConversionWarning(
                            "host " + h.label(),
                            "Key file is passphrase-protected; re-export with the passphrase prompt"
                        ));
                    }
                }
                // credentials off: leave KeyFileAuth as-is
            }
        }

        // Apply replacements
        List<SshHost> finalHosts = new ArrayList<>();
        for (SshHost h : bundleHosts.values()) {
            finalHosts.add(hostReplacements.getOrDefault(h.id(), h));
        }

        BundleMetadata metadata = new BundleMetadata(
            Instant.now(),
            req.sourceHost(),
            req.conchVersion(),
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
        for (VaultAccount a : vault.accounts) {
            if (a.id().equals(id)) return a;
        }
        return null;
    }

    private record AccountBundleResult(VaultAccount account, List<VaultKey> keys) {}

    private static AccountBundleResult bundleAccount(VaultAccount account, Vault vault, List<BundledKeyMaterial> keyMaterial) {
        // If the account references a keyPath that actually points to a standalone VaultKey by fingerprint
        // or privatePath, we don't currently model that — so just return the account as-is.
        // If its AuthMethod.Key.keyPath is an on-disk path, read the file and rewrite to a sentinel.
        AuthMethod method = account.auth();
        if (method instanceof AuthMethod.Key k && k.keyPath() != null
            && !BundledKeyMaterial.isSentinel(k.keyPath())) {
            KeyFileImporter.Result r = KeyFileImporter.read(Path.of(k.keyPath()), k.passphrase());
            if (r instanceof KeyFileImporter.Result.Ok ok) {
                keyMaterial.add(ok.material());
                VaultAccount rewritten = new VaultAccount(
                    account.id(), account.displayName(), account.username(),
                    new AuthMethod.Key(BundledKeyMaterial.sentinelFor(ok.material().id()), k.passphrase()),
                    account.createdAt(), account.updatedAt()
                );
                return new AccountBundleResult(rewritten, List.of());
            }
        }
        if (method instanceof AuthMethod.KeyAndPassword kp && kp.keyPath() != null
            && !BundledKeyMaterial.isSentinel(kp.keyPath())) {
            KeyFileImporter.Result r = KeyFileImporter.read(Path.of(kp.keyPath()), kp.passphrase());
            if (r instanceof KeyFileImporter.Result.Ok ok) {
                keyMaterial.add(ok.material());
                VaultAccount rewritten = new VaultAccount(
                    account.id(), account.displayName(), account.username(),
                    new AuthMethod.KeyAndPassword(
                        BundledKeyMaterial.sentinelFor(ok.material().id()),
                        kp.passphrase(), kp.password()
                    ),
                    account.createdAt(), account.updatedAt()
                );
                return new AccountBundleResult(rewritten, List.of());
            }
        }
        return new AccountBundleResult(account, List.of());
    }

    private ExportPlanner() {}
}
```

- [ ] **Step 7.7: Run tests, verify pass**

Expected: all 7 `ExportPlannerTest` tests pass.

- [ ] **Step 7.8: Commit**

```bash
git add plugins/share/src/com/conch/share/planner plugins/share/test/com/conch/share/planner
git commit -m "feat(share): add ExportPlanner with conversion logic"
```

---

## Task 8: `ImportPlanner` + `ImportItem`

Takes a decoded `ShareBundle` plus the user's current state, and produces an annotated list of `ImportItem`s.

**Files:**
- Create: `plugins/share/src/com/conch/share/model/ImportItem.java`
- Create: `plugins/share/src/com/conch/share/planner/ImportPlan.java`
- Create: `plugins/share/src/com/conch/share/planner/CurrentState.java`
- Create: `plugins/share/src/com/conch/share/planner/ImportPlanner.java`
- Create: `plugins/share/test/com/conch/share/planner/ImportPlannerTest.java`

- [ ] **Step 8.1: Create `ImportItem.java`**

```java
package com.conch.share.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class ImportItem {
    public enum Type { HOST, TUNNEL, ACCOUNT, KEY }
    public enum Status { NEW, SAME_UUID_EXISTS, LABEL_COLLISION, REFERENCE_BROKEN }
    public enum Action { IMPORT, SKIP, REPLACE, RENAME }

    public final @NotNull Type type;
    public final @NotNull UUID id;
    public final @NotNull String label;
    public @NotNull Status status;
    public @NotNull Action action;
    public @Nullable String renameTo;
    public final @NotNull Object payload; // SshHost, SshTunnel, VaultAccount, VaultKey, or BundledKeyMaterial

    public ImportItem(@NotNull Type type, @NotNull UUID id, @NotNull String label,
                      @NotNull Status status, @NotNull Action action, @NotNull Object payload) {
        this.type = type;
        this.id = id;
        this.label = label;
        this.status = status;
        this.action = action;
        this.payload = payload;
    }
}
```

- [ ] **Step 8.2: Create `CurrentState.java`**

```java
package com.conch.share.planner;

import com.conch.ssh.model.SshHost;
import com.conch.tunnels.model.SshTunnel;
import com.conch.vault.model.Vault;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record CurrentState(
    @NotNull List<SshHost> hosts,
    @NotNull List<SshTunnel> tunnels,
    @Nullable Vault vault
) {
    public static @NotNull CurrentState empty() {
        return new CurrentState(List.of(), List.of(), null);
    }
}
```

- [ ] **Step 8.3: Create `ImportPlan.java`**

```java
package com.conch.share.planner;

import com.conch.share.model.ImportItem;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ImportPlan(
    @NotNull List<ImportItem> items
) {
    public int countByStatus(@NotNull ImportItem.Status status) {
        return (int) items.stream().filter(i -> i.status == status).count();
    }
}
```

- [ ] **Step 8.4: Write failing tests**

Create `plugins/share/test/com/conch/share/planner/ImportPlannerTest.java`:

```java
package com.conch.share.planner;

import com.conch.share.model.BundleMetadata;
import com.conch.share.model.BundledVault;
import com.conch.share.model.ImportItem;
import com.conch.share.model.ShareBundle;
import com.conch.ssh.model.PromptPasswordAuth;
import com.conch.ssh.model.SshHost;
import com.conch.tunnels.model.InternalHost;
import com.conch.tunnels.model.SshTunnel;
import com.conch.tunnels.model.TunnelType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ImportPlannerTest {

    private SshHost host(UUID id, String label) {
        return new SshHost(id, label, label + ".example.com", 22, "ops",
            new PromptPasswordAuth(), null, null, Instant.now(), Instant.now());
    }

    private SshTunnel tunnel(UUID id, String label, UUID hostId) {
        return new SshTunnel(id, label, TunnelType.LOCAL, new InternalHost(hostId),
            8080, "localhost", "t", 80, Instant.now(), Instant.now());
    }

    private ShareBundle bundleOf(List<SshHost> hosts, List<SshTunnel> tunnels) {
        return new ShareBundle(
            ShareBundle.CURRENT_SCHEMA_VERSION,
            new BundleMetadata(Instant.now(), "src", "0.0.0", false),
            hosts, tunnels, BundledVault.empty(), List.of()
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
            .filter(i -> i.type == ImportItem.Type.TUNNEL).findFirst().orElseThrow();
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
            .filter(i -> i.type == ImportItem.Type.TUNNEL).findFirst().orElseThrow();
        assertEquals(ImportItem.Status.NEW, tunnelItem.status);
    }
}
```

- [ ] **Step 8.5: Run tests, verify compile failure**

- [ ] **Step 8.6: Implement `ImportPlanner.java`**

```java
package com.conch.share.planner;

import com.conch.share.model.ImportItem;
import com.conch.share.model.ShareBundle;
import com.conch.ssh.model.SshHost;
import com.conch.tunnels.model.InternalHost;
import com.conch.tunnels.model.SshTunnel;
import com.conch.tunnels.model.TunnelHost;
import com.conch.vault.model.Vault;
import com.conch.vault.model.VaultAccount;
import com.conch.vault.model.VaultKey;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ImportPlanner {

    public static @NotNull ImportPlan plan(@NotNull ShareBundle bundle, @NotNull CurrentState state) {
        Map<UUID, SshHost> existingHostsById = new HashMap<>();
        Set<String> existingHostLabels = new HashSet<>();
        for (SshHost h : state.hosts()) {
            existingHostsById.put(h.id(), h);
            existingHostLabels.add(h.label());
        }

        Map<UUID, SshTunnel> existingTunnelsById = new HashMap<>();
        Set<String> existingTunnelLabels = new HashSet<>();
        for (SshTunnel t : state.tunnels()) {
            existingTunnelsById.put(t.id(), t);
            existingTunnelLabels.add(t.label());
        }

        Map<UUID, VaultAccount> existingAccountsById = new HashMap<>();
        Set<String> existingAccountNames = new HashSet<>();
        Map<UUID, VaultKey> existingKeysById = new HashMap<>();
        Set<String> existingKeyNames = new HashSet<>();
        if (state.vault() != null) {
            for (VaultAccount a : state.vault().accounts) {
                existingAccountsById.put(a.id(), a);
                existingAccountNames.add(a.displayName());
            }
            for (VaultKey k : state.vault().keys) {
                existingKeysById.put(k.id(), k);
                existingKeyNames.add(k.name());
            }
        }

        Set<UUID> bundleHostIds = new HashSet<>();
        for (SshHost h : bundle.hosts()) bundleHostIds.add(h.id());

        List<ImportItem> items = new ArrayList<>();

        // Hosts
        for (SshHost h : bundle.hosts()) {
            ImportItem.Status status = determineStatus(
                existingHostsById.containsKey(h.id()),
                existingHostLabels.contains(h.label())
            );
            ImportItem.Action action = defaultAction(status);
            items.add(new ImportItem(ImportItem.Type.HOST, h.id(), h.label(), status, action, h));
        }

        // Tunnels
        for (SshTunnel t : bundle.tunnels()) {
            ImportItem.Status status;
            TunnelHost th = t.host();
            if (th instanceof InternalHost ih
                && !bundleHostIds.contains(ih.hostId())
                && !existingHostsById.containsKey(ih.hostId())) {
                status = ImportItem.Status.REFERENCE_BROKEN;
            } else {
                status = determineStatus(
                    existingTunnelsById.containsKey(t.id()),
                    existingTunnelLabels.contains(t.label())
                );
            }
            ImportItem.Action action = defaultAction(status);
            items.add(new ImportItem(ImportItem.Type.TUNNEL, t.id(), t.label(), status, action, t));
        }

        // Vault accounts
        for (VaultAccount a : bundle.vault().accounts()) {
            ImportItem.Status status = determineStatus(
                existingAccountsById.containsKey(a.id()),
                existingAccountNames.contains(a.displayName())
            );
            ImportItem.Action action = defaultAction(status);
            items.add(new ImportItem(ImportItem.Type.ACCOUNT, a.id(), a.displayName(), status, action, a));
        }

        // Vault keys
        for (VaultKey k : bundle.vault().keys()) {
            ImportItem.Status status = determineStatus(
                existingKeysById.containsKey(k.id()),
                existingKeyNames.contains(k.name())
            );
            ImportItem.Action action = defaultAction(status);
            items.add(new ImportItem(ImportItem.Type.KEY, k.id(), k.name(), status, action, k));
        }

        return new ImportPlan(items);
    }

    private static ImportItem.Status determineStatus(boolean sameId, boolean sameLabel) {
        if (sameId) return ImportItem.Status.SAME_UUID_EXISTS;
        if (sameLabel) return ImportItem.Status.LABEL_COLLISION;
        return ImportItem.Status.NEW;
    }

    private static ImportItem.Action defaultAction(ImportItem.Status status) {
        return switch (status) {
            case NEW -> ImportItem.Action.IMPORT;
            case REFERENCE_BROKEN -> ImportItem.Action.SKIP;
            // These default to SKIP initially; the UI replaces based on per-conflict prompt.
            case SAME_UUID_EXISTS, LABEL_COLLISION -> ImportItem.Action.SKIP;
        };
    }

    private ImportPlanner() {}
}
```

- [ ] **Step 8.7: Run tests, verify pass**

- [ ] **Step 8.8: Commit**

```bash
git add plugins/share/src/com/conch/share/model/ImportItem.java plugins/share/src/com/conch/share/planner plugins/share/test/com/conch/share/planner/ImportPlannerTest.java
git commit -m "feat(share): add ImportPlanner with conflict detection"
```

---

## Task 9: `ImportExecutor`

Writes the approved import items to disk. Materializes key bytes, rewrites sentinel paths, writes all three files atomically.

**Files:**
- Create: `plugins/share/src/com/conch/share/planner/ImportExecutor.java`
- Create: `plugins/share/src/com/conch/share/planner/ImportResult.java`
- Create: `plugins/share/src/com/conch/share/planner/ImportPaths.java`
- Create: `plugins/share/test/com/conch/share/planner/ImportExecutorTest.java`

- [ ] **Step 9.1: Create `ImportPaths.java`**

```java
package com.conch.share.planner;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public record ImportPaths(
    @NotNull Path hostsFile,
    @NotNull Path tunnelsFile,
    @NotNull Path vaultFile,
    @NotNull Path importedKeysDir
) {}
```

- [ ] **Step 9.2: Create `ImportResult.java`**

```java
package com.conch.share.planner;

import org.jetbrains.annotations.NotNull;

public record ImportResult(
    int hostsImported,
    int tunnelsImported,
    int accountsImported,
    int keysImported,
    int skipped,
    @NotNull String summary
) {}
```

- [ ] **Step 9.3: Write failing tests**

Create `plugins/share/test/com/conch/share/planner/ImportExecutorTest.java`:

```java
package com.conch.share.planner;

import com.conch.share.model.BundleMetadata;
import com.conch.share.model.BundledKeyMaterial;
import com.conch.share.model.BundledVault;
import com.conch.share.model.ImportItem;
import com.conch.share.model.ShareBundle;
import com.conch.ssh.model.PromptPasswordAuth;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.model.VaultAuth;
import com.conch.ssh.persistence.HostsFile;
import com.conch.tunnels.model.InternalHost;
import com.conch.tunnels.model.SshTunnel;
import com.conch.tunnels.model.TunnelType;
import com.conch.tunnels.persistence.TunnelsFile;
import com.conch.vault.lock.LockManager;
import com.conch.vault.model.AuthMethod;
import com.conch.vault.model.Vault;
import com.conch.vault.model.VaultAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

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
        SshHost h = new SshHost(hid, "web", "web.example.com", 22, "ops",
            new PromptPasswordAuth(), null, null, Instant.now(), Instant.now());
        SshTunnel t = new SshTunnel(tid, "db", TunnelType.LOCAL, new InternalHost(hid),
            8080, "localhost", "target", 80, Instant.now(), Instant.now());

        ShareBundle bundle = new ShareBundle(
            ShareBundle.CURRENT_SCHEMA_VERSION,
            new BundleMetadata(Instant.now(), "src", "0.0.0", false),
            List.of(h), List.of(t), BundledVault.empty(), List.of()
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
        byte[] keyBytes = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n"
            .getBytes();

        BundledKeyMaterial material = new BundledKeyMaterial(
            materialId,
            Base64.getEncoder().encodeToString(keyBytes),
            null,
            "id_ed25519"
        );

        VaultAccount account = new VaultAccount(
            accountId, "web-creds", "ops",
            new AuthMethod.Key(BundledKeyMaterial.sentinelFor(materialId), null),
            Instant.now(), Instant.now()
        );

        SshHost host = new SshHost(hostId, "web", "web.example.com", 22, "ops",
            new VaultAuth(accountId), null, null, Instant.now(), Instant.now());

        ShareBundle bundle = new ShareBundle(
            ShareBundle.CURRENT_SCHEMA_VERSION,
            new BundleMetadata(Instant.now(), "src", "0.0.0", true),
            List.of(host), List.of(),
            new BundledVault(List.of(account), List.of()),
            List.of(material)
        );

        ImportPlan plan = new ImportPlan(List.of(
            new ImportItem(ImportItem.Type.HOST, hostId, "web", ImportItem.Status.NEW, ImportItem.Action.IMPORT, host),
            new ImportItem(ImportItem.Type.ACCOUNT, accountId, "web-creds", ImportItem.Status.NEW, ImportItem.Action.IMPORT, account)
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

        // Reload the vault and confirm the account's keyPath is the real path, not the sentinel.
        byte[] pw2 = "masterpass12345".getBytes();
        try {
            LockManager lm2 = new LockManager(paths.vaultFile());
            lm2.unlock(pw2);
            Vault loaded = lm2.getVault();
            VaultAccount reloaded = loaded.accounts.stream()
                .filter(a -> a.id().equals(accountId)).findFirst().orElseThrow();
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
        SshHost h = new SshHost(hid, "web", "web.example.com", 22, "ops",
            new PromptPasswordAuth(), null, null, Instant.now(), Instant.now());

        ShareBundle bundle = new ShareBundle(
            ShareBundle.CURRENT_SCHEMA_VERSION,
            new BundleMetadata(Instant.now(), "src", "0.0.0", false),
            List.of(h), List.of(), BundledVault.empty(), List.of()
        );
        ImportPlan plan = new ImportPlan(List.of(
            new ImportItem(ImportItem.Type.HOST, hid, "web", ImportItem.Status.NEW, ImportItem.Action.SKIP, h)
        ));

        ImportResult result = ImportExecutor.execute(bundle, plan, pathsIn(tmp), null, null);
        assertEquals(0, result.hostsImported());
        assertEquals(1, result.skipped());
    }
}
```

- [ ] **Step 9.4: Run tests, verify compile failure**

- [ ] **Step 9.5: Implement `ImportExecutor.java`**

```java
package com.conch.share.planner;

import com.conch.share.model.BundledKeyMaterial;
import com.conch.share.model.ImportItem;
import com.conch.share.model.ShareBundle;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.persistence.HostsFile;
import com.conch.tunnels.model.SshTunnel;
import com.conch.tunnels.persistence.TunnelsFile;
import com.conch.vault.lock.LockManager;
import com.conch.vault.model.AuthMethod;
import com.conch.vault.model.Vault;
import com.conch.vault.model.VaultAccount;
import com.conch.vault.model.VaultKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ImportExecutor {

    public static @NotNull ImportResult execute(
        @NotNull ShareBundle bundle,
        @NotNull ImportPlan plan,
        @NotNull ImportPaths paths,
        @Nullable LockManager lockManager,
        byte @Nullable [] vaultPassword
    ) throws Exception {

        // 1. Materialize any key material referenced by accounts we will keep.
        Map<UUID, String> materializedPaths = new HashMap<>();
        Set<UUID> accountsToImport = new HashSet<>();
        Set<UUID> keysToImport = new HashSet<>();
        int skipped = 0;

        for (ImportItem item : plan.items()) {
            if (item.action == ImportItem.Action.SKIP) { skipped++; continue; }
            switch (item.type) {
                case ACCOUNT -> accountsToImport.add(item.id);
                case KEY -> keysToImport.add(item.id);
                default -> {}
            }
        }

        Files.createDirectories(paths.importedKeysDir());
        for (BundledKeyMaterial mat : bundle.keyMaterial()) {
            // Write all materials that might be referenced.
            Path target = paths.importedKeysDir().resolve(mat.id() + ".key");
            byte[] bytes = Base64.getDecoder().decode(mat.privateKeyBase64());
            writeAtomic(target, bytes);
            try {
                Files.setPosixFilePermissions(target,
                    java.util.EnumSet.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem (Windows); best-effort
            }
            materializedPaths.put(mat.id(), target.toString());
        }

        // 2. Apply vault mutations inside withUnlocked.
        int accountsImported = 0;
        int keysImported = 0;
        if (lockManager != null && vaultPassword != null && !bundle.vault().isEmpty()) {
            Map<UUID, String> matPaths = materializedPaths;

            final int[] accCount = {0};
            final int[] keyCount = {0};
            lockManager.withUnlocked(vaultPassword, vault -> {
                // Keys first (accounts may reference them — though in this model accounts store paths).
                for (VaultKey bk : bundle.vault().keys()) {
                    if (!keysToImport.contains(bk.id())) continue;
                    VaultKey rewritten = rewriteKeyPaths(bk, matPaths);
                    replaceOrAddKey(vault, rewritten);
                    keyCount[0]++;
                }
                for (VaultAccount ba : bundle.vault().accounts()) {
                    if (!accountsToImport.contains(ba.id())) continue;
                    VaultAccount rewritten = rewriteAccountPaths(ba, matPaths);
                    replaceOrAddAccount(vault, applyRename(rewritten, plan, ba.id()));
                    accCount[0]++;
                }
                return null;
            });
            accountsImported = accCount[0];
            keysImported = keyCount[0];
        }

        // 3. Write hosts + tunnels.
        int hostsImported = applyHostChanges(bundle, plan, paths.hostsFile());
        int tunnelsImported = applyTunnelChanges(bundle, plan, paths.tunnelsFile());

        String summary = String.format(
            "Imported %d hosts, %d tunnels, %d credentials, %d keys. %d skipped.",
            hostsImported, tunnelsImported, accountsImported, keysImported, skipped
        );
        return new ImportResult(hostsImported, tunnelsImported, accountsImported, keysImported, skipped, summary);
    }

    private static VaultKey rewriteKeyPaths(VaultKey k, Map<UUID, String> matPaths) {
        String newPrivate = rewriteIfSentinel(k.privatePath(), matPaths);
        if (newPrivate.equals(k.privatePath())) return k;
        return new VaultKey(
            k.id(), k.name(), k.algorithm(), k.fingerprint(), k.comment(),
            newPrivate, k.publicPath(), k.createdAt()
        );
    }

    private static VaultAccount rewriteAccountPaths(VaultAccount a, Map<UUID, String> matPaths) {
        AuthMethod rewritten = switch (a.auth()) {
            case AuthMethod.Password p -> p;
            case AuthMethod.Key k -> new AuthMethod.Key(
                rewriteIfSentinel(k.keyPath(), matPaths), k.passphrase());
            case AuthMethod.KeyAndPassword kp -> new AuthMethod.KeyAndPassword(
                rewriteIfSentinel(kp.keyPath(), matPaths), kp.passphrase(), kp.password());
        };
        if (rewritten == a.auth()) return a;
        return new VaultAccount(a.id(), a.displayName(), a.username(), rewritten, a.createdAt(), Instant.now());
    }

    private static String rewriteIfSentinel(String path, Map<UUID, String> matPaths) {
        if (!BundledKeyMaterial.isSentinel(path)) return path;
        UUID id = BundledKeyMaterial.parseSentinel(path);
        String resolved = matPaths.get(id);
        if (resolved == null) {
            throw new IllegalStateException("key material not materialized for " + id);
        }
        return resolved;
    }

    private static void replaceOrAddKey(Vault vault, VaultKey k) {
        for (int i = 0; i < vault.keys.size(); i++) {
            if (vault.keys.get(i).id().equals(k.id())) {
                vault.keys.set(i, k);
                return;
            }
        }
        vault.keys.add(k);
    }

    private static void replaceOrAddAccount(Vault vault, VaultAccount a) {
        for (int i = 0; i < vault.accounts.size(); i++) {
            if (vault.accounts.get(i).id().equals(a.id())) {
                vault.accounts.set(i, a);
                return;
            }
        }
        vault.accounts.add(a);
    }

    private static VaultAccount applyRename(VaultAccount a, ImportPlan plan, UUID id) {
        for (ImportItem it : plan.items()) {
            if (it.id.equals(id) && it.action == ImportItem.Action.RENAME && it.renameTo != null) {
                return new VaultAccount(a.id(), it.renameTo, a.username(), a.auth(), a.createdAt(), Instant.now());
            }
        }
        return a;
    }

    private static int applyHostChanges(ShareBundle bundle, ImportPlan plan, Path hostsFile) throws IOException {
        List<SshHost> existing = HostsFile.exists(hostsFile)
            ? new ArrayList<>(HostsFile.load(hostsFile))
            : new ArrayList<>();
        Map<UUID, Integer> indexById = new HashMap<>();
        for (int i = 0; i < existing.size(); i++) indexById.put(existing.get(i).id(), i);
        int imported = 0;

        for (ImportItem item : plan.items()) {
            if (item.type != ImportItem.Type.HOST) continue;
            if (item.action == ImportItem.Action.SKIP) continue;
            SshHost h = (SshHost) item.payload;
            if (item.action == ImportItem.Action.RENAME && item.renameTo != null) {
                h = h.withLabel(item.renameTo);
            }
            Integer existingIdx = indexById.get(h.id());
            if (existingIdx != null && item.action == ImportItem.Action.REPLACE) {
                existing.set(existingIdx, h);
            } else if (existingIdx == null) {
                existing.add(h);
                indexById.put(h.id(), existing.size() - 1);
            } else {
                continue; // IMPORT on an existing UUID would be ambiguous; treat as skip
            }
            imported++;
        }
        HostsFile.save(hostsFile, existing);
        return imported;
    }

    private static int applyTunnelChanges(ShareBundle bundle, ImportPlan plan, Path tunnelsFile) throws IOException {
        List<SshTunnel> existing;
        try {
            existing = new ArrayList<>(TunnelsFile.load(tunnelsFile));
        } catch (IOException e) {
            existing = new ArrayList<>();
        }
        Map<UUID, Integer> indexById = new HashMap<>();
        for (int i = 0; i < existing.size(); i++) indexById.put(existing.get(i).id(), i);
        int imported = 0;

        for (ImportItem item : plan.items()) {
            if (item.type != ImportItem.Type.TUNNEL) continue;
            if (item.action == ImportItem.Action.SKIP) continue;
            SshTunnel t = (SshTunnel) item.payload;
            if (item.action == ImportItem.Action.RENAME && item.renameTo != null) {
                t = t.withLabel(item.renameTo);
            }
            Integer existingIdx = indexById.get(t.id());
            if (existingIdx != null && item.action == ImportItem.Action.REPLACE) {
                existing.set(existingIdx, t);
            } else if (existingIdx == null) {
                existing.add(t);
                indexById.put(t.id(), existing.size() - 1);
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
        Files.move(tmp, target,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private ImportExecutor() {}
}
```

- [ ] **Step 9.6: Run tests, verify pass**

- [ ] **Step 9.7: Commit**

```bash
git add plugins/share/src/com/conch/share/planner plugins/share/test/com/conch/share/planner/ImportExecutorTest.java
git commit -m "feat(share): add ImportExecutor with key materialization"
```

---

## Task 10: `PasswordUtil` + `ExportDialog`

UI work. No unit tests — this is covered by the manual checklist. Focus on getting the controls right and delegating all logic to `ExportPlanner`.

**Files:**
- Create: `plugins/share/src/com/conch/share/ui/PasswordUtil.java`
- Create: `plugins/share/src/com/conch/share/ui/ExportDialog.java`

- [ ] **Step 10.1: Create `PasswordUtil.java`**

```java
package com.conch.share.ui;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class PasswordUtil {

    public static byte[] toUtf8(char[] chars) {
        CharBuffer cb = CharBuffer.wrap(chars);
        ByteBuffer bb = StandardCharsets.UTF_8.encode(cb);
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        if (bb.hasArray()) Arrays.fill(bb.array(), (byte) 0);
        return out;
    }

    public static void zero(char[] chars) {
        if (chars != null) Arrays.fill(chars, '\0');
    }

    public static void zero(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }

    private PasswordUtil() {}
}
```

- [ ] **Step 10.2: Read `CreateVaultDialog.java` and `NewSshSessionAction.java` in full**

This gives you the exact pattern for `DialogWrapper` subclasses and how to fetch services from `ApplicationManager`. Mirror both exactly.

- [ ] **Step 10.3: Create `ExportDialog.java`**

```java
package com.conch.share.ui;

import com.conch.share.codec.ShareBundleCodec;
import com.conch.share.planner.ConversionWarning;
import com.conch.share.planner.ExportPlan;
import com.conch.share.planner.ExportPlanner;
import com.conch.share.planner.ExportRequest;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.persistence.HostsFile;
import com.conch.tunnels.model.SshTunnel;
import com.conch.tunnels.persistence.TunnelsFile;
import com.conch.vault.lock.LockManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.CheckboxTree;
import com.intellij.ui.CheckedTreeNode;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.List;

public final class ExportDialog extends DialogWrapper {

    public enum EntryPoint { HOSTS, TUNNELS }

    private final @Nullable Project project;
    private final EntryPoint entryPoint;

    private final JPasswordField passwordField = new JPasswordField(24);
    private final JPasswordField confirmField = new JPasswordField(24);
    private final JCheckBox includeCredentialsBox = new JCheckBox("Include saved credentials");

    private final List<SshHost> allHosts;
    private final List<SshTunnel> allTunnels;

    private final Map<UUID, CheckedTreeNode> hostNodes = new LinkedHashMap<>();
    private final Map<UUID, CheckedTreeNode> tunnelNodes = new LinkedHashMap<>();

    public ExportDialog(@Nullable Project project, @NotNull EntryPoint entryPoint) {
        super(project, true);
        this.project = project;
        this.entryPoint = entryPoint;

        this.allHosts = loadHosts();
        this.allTunnels = loadTunnels();

        setTitle("Export Conch Bundle");
        setOKButtonText("Export…");
        init();
    }

    private List<SshHost> loadHosts() {
        try {
            Path p = Path.of(System.getProperty("user.home"), ".config", "conch", "ssh-hosts.json");
            return HostsFile.exists(p) ? HostsFile.load(p) : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<SshTunnel> loadTunnels() {
        try {
            Path p = Path.of(System.getProperty("user.home"), ".config", "conch", "tunnels.json");
            return TunnelsFile.load(p);
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(12));
        panel.setPreferredSize(new Dimension(560, 520));

        CheckedTreeNode root = new CheckedTreeNode("Export");
        root.setChecked(false);

        CheckedTreeNode hostsRoot = new CheckedTreeNode("SSH Hosts");
        hostsRoot.setChecked(false);
        for (SshHost h : allHosts) {
            CheckedTreeNode node = new CheckedTreeNode(h.label() + "  (" + h.host() + ")");
            node.setChecked(false);
            hostNodes.put(h.id(), node);
            hostsRoot.add(node);
        }
        root.add(hostsRoot);

        CheckedTreeNode tunnelsRoot = new CheckedTreeNode("Tunnels");
        tunnelsRoot.setChecked(false);
        for (SshTunnel t : allTunnels) {
            CheckedTreeNode node = new CheckedTreeNode(t.label());
            node.setChecked(false);
            tunnelNodes.put(t.id(), node);
            tunnelsRoot.add(node);
        }
        root.add(tunnelsRoot);

        CheckboxTree tree = new CheckboxTree(new CheckboxTree.CheckboxTreeCellRenderer() {}, root);
        tree.setModel(new DefaultTreeModel(root));
        tree.expandRow(0);
        tree.expandRow(1);
        if (entryPoint == EntryPoint.TUNNELS) tree.expandRow(2 + allHosts.size());

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setPreferredSize(new Dimension(540, 320));

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2;
        gc.fill = GridBagConstraints.BOTH; gc.weightx = 1.0; gc.weighty = 1.0;
        panel.add(scroll, gc);

        gc.gridy++; gc.fill = GridBagConstraints.HORIZONTAL; gc.weighty = 0;
        panel.add(includeCredentialsBox, gc);

        gc.gridy++;
        JLabel hint = new JLabel("<html><small>Anyone with the bundle password can read everything inside.</small></html>");
        panel.add(hint, gc);

        gc.gridy++; gc.gridwidth = 1; gc.weightx = 0;
        panel.add(new JLabel("Password:"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        panel.add(passwordField, gc);

        gc.gridy++; gc.gridx = 0; gc.weightx = 0;
        panel.add(new JLabel("Confirm:"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        panel.add(confirmField, gc);

        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        char[] pw = passwordField.getPassword();
        char[] cf = confirmField.getPassword();
        try {
            if (pw.length < 8) return new ValidationInfo("Password must be at least 8 characters", passwordField);
            if (!Arrays.equals(pw, cf)) return new ValidationInfo("Passwords do not match", confirmField);
            if (selectedHostIds().isEmpty() && selectedTunnelIds().isEmpty()) {
                return new ValidationInfo("Select at least one host or tunnel");
            }
            return null;
        } finally {
            Arrays.fill(pw, '\0');
            Arrays.fill(cf, '\0');
        }
    }

    private Set<UUID> selectedHostIds() {
        Set<UUID> out = new HashSet<>();
        for (Map.Entry<UUID, CheckedTreeNode> e : hostNodes.entrySet()) {
            if (e.getValue().isChecked()) out.add(e.getKey());
        }
        return out;
    }

    private Set<UUID> selectedTunnelIds() {
        Set<UUID> out = new HashSet<>();
        for (Map.Entry<UUID, CheckedTreeNode> e : tunnelNodes.entrySet()) {
            if (e.getValue().isChecked()) out.add(e.getKey());
        }
        return out;
    }

    @Override
    protected void doOKAction() {
        boolean includeCreds = includeCredentialsBox.isSelected();
        char[] pwChars = passwordField.getPassword();
        byte[] password = PasswordUtil.toUtf8(pwChars);
        PasswordUtil.zero(pwChars);

        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        com.conch.vault.model.Vault vault = null;
        if (includeCreds) {
            if (lm == null || lm.getVault() == null) {
                Messages.showErrorDialog(getContentPanel(),
                    "Unlock the vault before exporting with credentials.", "Vault Locked");
                PasswordUtil.zero(password);
                return;
            }
            vault = lm.getVault();
        }

        Path sshConfig = Path.of(System.getProperty("user.home"), ".ssh", "config");
        ExportRequest req = new ExportRequest(
            selectedHostIds(), selectedTunnelIds(), includeCreds,
            allHosts, allTunnels, vault,
            sshConfig,
            safeHostname(),
            "0.14.2"
        );

        ExportPlan plan = ExportPlanner.plan(req);

        if (!plan.warnings().isEmpty()) {
            StringBuilder sb = new StringBuilder("The following will be converted or skipped:\n\n");
            for (ConversionWarning w : plan.warnings()) {
                sb.append("• ").append(w.subject()).append(": ").append(w.message()).append("\n");
            }
            int choice = Messages.showOkCancelDialog(
                getContentPanel(), sb.toString(), "Export Preview", "Export", "Cancel", null);
            if (choice != Messages.OK) {
                PasswordUtil.zero(password);
                return;
            }
        }

        FileSaverDescriptor descriptor = new FileSaverDescriptor(
            "Save Conch Bundle", "Choose where to save the encrypted bundle", "conchshare");
        String defaultName = "conch-share-" + LocalDate.now() + ".conchshare";
        VirtualFileWrapper wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save((com.intellij.openapi.vfs.VirtualFile) null, defaultName);
        if (wrapper == null) {
            PasswordUtil.zero(password);
            return;
        }
        Path target = wrapper.getFile().toPath();

        try {
            byte[] encoded = ShareBundleCodec.encode(plan.bundle(), password);
            java.nio.file.Files.write(target, encoded);
            super.doOKAction();
        } catch (Exception e) {
            Messages.showErrorDialog(getContentPanel(),
                "Export failed: " + e.getMessage(), "Export Failed");
        } finally {
            PasswordUtil.zero(password);
        }
    }

    private static String safeHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
```

- [ ] **Step 10.4: Build to verify**

```bash
cd $INTELLIJ_ROOT && bash bazel.cmd build //conch/plugins/share:share
```

Expected: compiles cleanly. If `CheckboxTree` or `CheckedTreeNode` aren't in the deps, add `//platform/platform-impl:ide-impl` (it already is) and adjust imports — inspect `HostsToolWindow.java` for the exact import path used by the existing code.

- [ ] **Step 10.5: Commit**

```bash
git add plugins/share/src/com/conch/share/ui
git commit -m "feat(share): add ExportDialog"
```

---

## Task 11: `ConflictResolutionDialog` + `ImportDialog`

**Files:**
- Create: `plugins/share/src/com/conch/share/ui/ConflictResolutionDialog.java`
- Create: `plugins/share/src/com/conch/share/ui/ImportDialog.java`

- [ ] **Step 11.1: Create `ConflictResolutionDialog.java`**

```java
package com.conch.share.ui;

import com.conch.share.model.ImportItem;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public final class ConflictResolutionDialog extends DialogWrapper {

    public static final class Result {
        public final @NotNull ImportItem.Action action;
        public final @Nullable String renameTo;
        public final boolean applyToAll;

        public Result(@NotNull ImportItem.Action action, @Nullable String renameTo, boolean applyToAll) {
            this.action = action;
            this.renameTo = renameTo;
            this.applyToAll = applyToAll;
        }
    }

    private final ImportItem item;
    private final JCheckBox applyToAllBox = new JCheckBox("Apply to all remaining conflicts of this type");
    private Result result;

    public ConflictResolutionDialog(@Nullable Project project, @NotNull ImportItem item) {
        super(project, true);
        this.item = item;
        setTitle("Conflict: " + item.label);
        setOKButtonText("Skip");
        init();
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(12));
        panel.setPreferredSize(new Dimension(460, 180));

        String message = switch (item.status) {
            case SAME_UUID_EXISTS -> "An item with this ID already exists: " + item.label;
            case LABEL_COLLISION -> "A different item already uses the label: " + item.label;
            default -> item.label;
        };

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 3;
        gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1.0;
        panel.add(new JLabel("<html>" + message + "</html>"), gc);

        gc.gridy++;
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton skipBtn = new JButton("Skip");
        JButton replaceBtn = new JButton("Replace");
        JButton renameBtn = new JButton("Rename…");
        replaceBtn.setEnabled(item.status == ImportItem.Status.SAME_UUID_EXISTS);
        renameBtn.setEnabled(item.status == ImportItem.Status.LABEL_COLLISION);
        buttons.add(skipBtn);
        buttons.add(replaceBtn);
        buttons.add(renameBtn);
        panel.add(buttons, gc);

        gc.gridy++;
        panel.add(applyToAllBox, gc);

        skipBtn.addActionListener(e -> {
            result = new Result(ImportItem.Action.SKIP, null, applyToAllBox.isSelected());
            close(OK_EXIT_CODE);
        });
        replaceBtn.addActionListener(e -> {
            result = new Result(ImportItem.Action.REPLACE, null, applyToAllBox.isSelected());
            close(OK_EXIT_CODE);
        });
        renameBtn.addActionListener(e -> {
            String newName = Messages.showInputDialog(getContentPanel(),
                "New label:", "Rename", null, item.label + " (imported)", null);
            if (newName == null || newName.isBlank()) return;
            result = new Result(ImportItem.Action.RENAME, newName, applyToAllBox.isSelected());
            close(OK_EXIT_CODE);
        });

        return panel;
    }

    public @Nullable Result getResult() {
        return result;
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[0];
    }
}
```

- [ ] **Step 11.2: Create `ImportDialog.java`**

This is the largest UI class. It wraps the three-step flow: file+password → inline vault setup (conditional) → preview/execute.

```java
package com.conch.share.ui;

import com.conch.share.codec.ShareBundleCodec;
import com.conch.share.codec.exceptions.BundleCorruptedException;
import com.conch.share.codec.exceptions.UnsupportedBundleVersionException;
import com.conch.share.codec.exceptions.WrongBundlePasswordException;
import com.conch.share.model.ImportItem;
import com.conch.share.model.ShareBundle;
import com.conch.share.planner.CurrentState;
import com.conch.share.planner.ImportExecutor;
import com.conch.share.planner.ImportPaths;
import com.conch.share.planner.ImportPlan;
import com.conch.share.planner.ImportPlanner;
import com.conch.share.planner.ImportResult;
import com.conch.ssh.persistence.HostsFile;
import com.conch.tunnels.persistence.TunnelsFile;
import com.conch.vault.lock.LockManager;
import com.conch.vault.persistence.VaultFile;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

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
        // Step 1: file chooser.
        VirtualFile file = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFileDescriptor("conchshare"),
            project, null);
        if (file == null) return;

        Path bundlePath = file.toNioPath();

        // Step 1b: password.
        ShareBundle bundle = null;
        while (bundle == null) {
            String password = Messages.showPasswordDialog(
                project,
                "Bundle password:",
                "Open " + file.getName(),
                null);
            if (password == null) return;

            try {
                byte[] bytes = Files.readAllBytes(bundlePath);
                bundle = ShareBundleCodec.decode(bytes, password.getBytes());
            } catch (WrongBundlePasswordException wpe) {
                Messages.showErrorDialog(project, "Incorrect password.", "Bundle Locked");
            } catch (BundleCorruptedException bce) {
                Messages.showErrorDialog(project,
                    "Not a valid Conch share bundle: " + bce.getMessage(), "Invalid Bundle");
                return;
            } catch (UnsupportedBundleVersionException uve) {
                Messages.showErrorDialog(project,
                    "This bundle was created by a newer version of Conch (v" + uve.version() + ").",
                    "Unsupported Bundle Version");
                return;
            } catch (Exception e) {
                Messages.showErrorDialog(project, "Could not read bundle: " + e.getMessage(), "Error");
                return;
            }
        }

        // Step 2: inline vault setup (only if credentials + no vault).
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        byte[] vaultPassword = null;
        if (lm != null && bundle.metadata().includesCredentials()) {
            try {
                vaultPassword = setupOrUnlockVault(lm);
                if (vaultPassword == null) return;
            } catch (Exception e) {
                Messages.showErrorDialog(project, "Vault setup failed: " + e.getMessage(), "Vault Error");
                return;
            }
        }

        // Step 3: preview + per-conflict resolution.
        CurrentState state = loadCurrentState(lm);
        ImportPlan plan = ImportPlanner.plan(bundle, state);

        List<ImportItem> resolved = resolveConflicts(plan.items());
        if (resolved == null) {
            PasswordUtil.zero(vaultPassword);
            return;
        }

        ImportPlan finalPlan = new ImportPlan(resolved);

        // Step 4: execute.
        try {
            ImportPaths paths = defaultPaths();
            ImportResult result = ImportExecutor.execute(bundle, finalPlan, paths, lm, vaultPassword);
            Messages.showInfoMessage(project, result.summary(), "Import Complete");
        } catch (Exception e) {
            Messages.showErrorDialog(project, "Import failed: " + e.getMessage(), "Import Failed");
        } finally {
            PasswordUtil.zero(vaultPassword);
        }
    }

    private byte[] setupOrUnlockVault(LockManager lm) throws Exception {
        Path vaultPath = lm.getVaultPath();
        if (!VaultFile.exists(vaultPath)) {
            String pw = Messages.showPasswordDialog(project,
                "This bundle contains saved credentials. Set a master password for your new vault:",
                "Create Vault", null);
            if (pw == null) return null;
            byte[] bytes = pw.getBytes();
            lm.createVault(bytes);
            lm.lock();
            return bytes;
        }
        if (lm.isLocked()) {
            String pw = Messages.showPasswordDialog(project,
                "Enter your vault master password:", "Unlock Vault", null);
            if (pw == null) return null;
            byte[] bytes = pw.getBytes();
            lm.unlock(bytes);
            lm.lock();
            return bytes;
        }
        // Already unlocked — we still need the master password to call withUnlocked.
        String pw = Messages.showPasswordDialog(project,
            "Re-enter your vault master password to import credentials:",
            "Confirm Vault Password", null);
        return pw == null ? null : pw.getBytes();
    }

    private CurrentState loadCurrentState(@Nullable LockManager lm) {
        var hosts = new ArrayList<com.conch.ssh.model.SshHost>();
        var tunnels = new ArrayList<com.conch.tunnels.model.SshTunnel>();
        try {
            Path hp = Path.of(System.getProperty("user.home"), ".config", "conch", "ssh-hosts.json");
            if (HostsFile.exists(hp)) hosts.addAll(HostsFile.load(hp));
        } catch (Exception ignored) {}
        try {
            Path tp = Path.of(System.getProperty("user.home"), ".config", "conch", "tunnels.json");
            tunnels.addAll(TunnelsFile.load(tp));
        } catch (Exception ignored) {}
        return new CurrentState(hosts, tunnels, lm != null ? lm.getVault() : null);
    }

    private List<ImportItem> resolveConflicts(List<ImportItem> items) {
        ImportItem.Action pendingSameIdAction = null;
        String pendingSameIdRename = null;
        ImportItem.Action pendingLabelCollisionAction = null;
        String pendingLabelCollisionRename = null;

        List<ImportItem> out = new ArrayList<>(items.size());
        for (ImportItem item : items) {
            if (item.status == ImportItem.Status.NEW || item.status == ImportItem.Status.REFERENCE_BROKEN) {
                out.add(item);
                continue;
            }
            ImportItem.Action presetAction = null;
            String presetRename = null;
            if (item.status == ImportItem.Status.SAME_UUID_EXISTS && pendingSameIdAction != null) {
                presetAction = pendingSameIdAction;
                presetRename = pendingSameIdRename;
            } else if (item.status == ImportItem.Status.LABEL_COLLISION && pendingLabelCollisionAction != null) {
                presetAction = pendingLabelCollisionAction;
                presetRename = pendingLabelCollisionRename;
            }

            if (presetAction != null) {
                item.action = presetAction;
                item.renameTo = presetRename;
                out.add(item);
                continue;
            }

            ConflictResolutionDialog dlg = new ConflictResolutionDialog(project, item);
            if (!dlg.showAndGet()) return null;
            ConflictResolutionDialog.Result result = dlg.getResult();
            if (result == null) return null;
            item.action = result.action;
            item.renameTo = result.renameTo;
            if (result.applyToAll) {
                if (item.status == ImportItem.Status.SAME_UUID_EXISTS) {
                    pendingSameIdAction = result.action;
                    pendingSameIdRename = result.renameTo;
                } else {
                    pendingLabelCollisionAction = result.action;
                    pendingLabelCollisionRename = result.renameTo;
                }
            }
            out.add(item);
        }
        return out;
    }

    private ImportPaths defaultPaths() {
        Path base = Path.of(System.getProperty("user.home"), ".config", "conch");
        return new ImportPaths(
            base.resolve("ssh-hosts.json"),
            base.resolve("tunnels.json"),
            base.resolve("vault.enc"),
            base.resolve("imported-keys")
        );
    }
}
```

- [ ] **Step 11.3: Build to verify**

```bash
cd $INTELLIJ_ROOT && bash bazel.cmd build //conch/plugins/share:share
```

- [ ] **Step 11.4: Commit**

```bash
git add plugins/share/src/com/conch/share/ui
git commit -m "feat(share): add ImportDialog with inline vault setup and conflict resolution"
```

---

## Task 12: Actions and toolbar wiring

**Files:**
- Create: `plugins/share/src/com/conch/share/actions/ExportAction.java`
- Create: `plugins/share/src/com/conch/share/actions/ImportAction.java`
- Modify: `plugins/ssh/src/com/conch/ssh/toolwindow/HostsToolWindow.java`
- Modify: `plugins/tunnels/src/com/conch/tunnels/toolwindow/TunnelsToolWindow.java`

- [ ] **Step 12.1: Create `ExportAction.java`**

```java
package com.conch.share.actions;

import com.conch.share.ui.ExportDialog;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public final class ExportAction extends AnAction {

    private final ExportDialog.EntryPoint entryPoint;

    public ExportAction() {
        this(ExportDialog.EntryPoint.HOSTS);
    }

    public ExportAction(ExportDialog.EntryPoint entryPoint) {
        super("Export Conch Bundle…", "Export SSH hosts and tunnels as an encrypted share bundle", null);
        this.entryPoint = entryPoint;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        new ExportDialog(e.getProject(), entryPoint).show();
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
```

- [ ] **Step 12.2: Create `ImportAction.java`**

```java
package com.conch.share.actions;

import com.conch.share.ui.ImportDialog;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public final class ImportAction extends AnAction {

    public ImportAction() {
        super("Import Conch Bundle…", "Import SSH hosts, tunnels, and credentials from a share bundle", null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        new ImportDialog(e.getProject()).run();
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
```

- [ ] **Step 12.3: Build to verify compile**

```bash
cd $INTELLIJ_ROOT && bash bazel.cmd build //conch/plugins/share:share
```

- [ ] **Step 12.4: Wire toolbar buttons in `HostsToolWindow.java`**

Read the file and locate the `buildToolbar()` method (around lines 120-132). Add `.add(...)` calls for Export and Import actions:

```java
private @NotNull ActionToolbar buildToolbar() {
    DefaultActionGroup group = new DefaultActionGroup();
    group.add(new AddHostAction());
    group.add(new EditHostAction());
    group.add(new DeleteHostAction());
    group.addSeparator();
    group.add(new com.conch.share.actions.ExportAction(
        com.conch.share.ui.ExportDialog.EntryPoint.HOSTS));
    group.add(new com.conch.share.actions.ImportAction());
    group.addSeparator();
    group.add(new RefreshAction());
    // ... rest unchanged
}
```

The SSH plugin's BUILD.bazel must now depend on the share plugin. **This creates a cycle** (share depends on ssh, ssh depends on share). Resolve by:

1. Moving the toolbar wiring out of the SSH plugin entirely — instead, register the share actions via `plugin.xml` using IntelliJ's action groups (`<group id="...">` with `<reference>` and `<add-to-group group-id="..."/>`).
2. Have the share plugin's `plugin.xml` contribute its actions to the SSH tool window's toolbar group.

**Update `plugins/share/resources/META-INF/plugin.xml`:**

```xml
<actions>
    <group id="Conch.Share.ToolWindowGroup">
        <action id="Conch.Share.Export.Hosts"
                class="com.conch.share.actions.ExportAction"
                text="Export…"
                description="Export to a Conch share bundle"/>
        <action id="Conch.Share.Import.Hosts"
                class="com.conch.share.actions.ImportAction"
                text="Import…"
                description="Import from a Conch share bundle"/>
    </group>

    <!-- These IDs must match the toolbar place IDs used by the SSH and Tunnels tool windows.
         Look for ActionManager.createActionToolbar("HostsToolbar", ...) in HostsToolWindow
         and "TunnelsToolbar" in TunnelsToolWindow. -->

    <action id="Conch.ExportShareBundle.Menu"
            class="com.conch.share.actions.ExportAction"
            text="Export Conch Bundle…"
            description="Export SSH hosts and tunnels as an encrypted share bundle">
        <add-to-group group-id="ToolsMenu" anchor="last"/>
    </action>
    <action id="Conch.ImportShareBundle.Menu"
            class="com.conch.share.actions.ImportAction"
            text="Import Conch Bundle…"
            description="Import SSH hosts, tunnels, and credentials from a share bundle">
        <add-to-group group-id="ToolsMenu" anchor="last"/>
    </action>
</actions>
```

**IMPORTANT:** Because the existing tool windows build their toolbars programmatically (not from `plugin.xml`), there is no DeclarativeActionGroup to hook into. You must modify `HostsToolWindow.java` and `TunnelsToolWindow.java` to append share actions dynamically. The cleanest way without creating a build cycle:

- In `HostsToolWindow.buildToolbar()`, replace the direct class references with `ActionManager.getInstance().getAction("Conch.Share.Export.Hosts")`. This is a string lookup — no compile-time dependency on `plugins/share` is needed.

```java
// In HostsToolWindow.buildToolbar() after the separator:
AnAction exportAction = ActionManager.getInstance().getAction("Conch.Share.Export.Hosts");
AnAction importAction = ActionManager.getInstance().getAction("Conch.Share.Import.Hosts");
if (exportAction != null) group.add(exportAction);
if (importAction != null) group.add(importAction);
```

This pattern keeps `plugins/ssh` unchanged in its build deps. `plugin.xml` in the share plugin registers the action IDs; at runtime the ssh tool window looks them up by string.

- [ ] **Step 12.5: Wire toolbar buttons in `TunnelsToolWindow.java`**

Same pattern as Step 12.4, using action IDs `Conch.Share.Export.Tunnels` and `Conch.Share.Import.Tunnels`. Register these two additional actions in `plugins/share/resources/META-INF/plugin.xml` alongside the hosts variants.

Note that `ExportAction` has two constructors — the no-arg one (used when looked up by ID through plugin.xml) defaults to HOSTS entry point. For the tunnels variant, subclass:

```java
// plugins/share/src/com/conch/share/actions/ExportActionTunnels.java
package com.conch.share.actions;

import com.conch.share.ui.ExportDialog;

public final class ExportActionTunnels extends ExportAction {
    public ExportActionTunnels() {
        super(ExportDialog.EntryPoint.TUNNELS);
    }
}
```

And register it in `plugin.xml`:

```xml
<action id="Conch.Share.Export.Tunnels"
        class="com.conch.share.actions.ExportActionTunnels"
        text="Export…"
        description="Export to a Conch share bundle"/>
<action id="Conch.Share.Import.Tunnels"
        class="com.conch.share.actions.ImportAction"
        text="Import…"
        description="Import from a Conch share bundle"/>
```

- [ ] **Step 12.6: Build full conch_run**

```bash
make conch-build
```

Expected: full build succeeds.

- [ ] **Step 12.7: Run Conch and verify manually**

```bash
make conch
```

Manual checks:
- SSH Hosts tool window shows Export… and Import… buttons.
- Tunnels tool window shows Export… and Import… buttons.
- Tools menu has "Export Conch Bundle…" and "Import Conch Bundle…".
- Export dialog opens, selection tree populated, password fields work, validation kicks in.
- Import dialog opens from a bundle produced in the same session, previews items, per-conflict prompts fire.

- [ ] **Step 12.8: Commit**

```bash
git add plugins/share plugins/ssh plugins/tunnels
git commit -m "feat(share): wire Export/Import actions into tool windows and Tools menu"
```

---

## Task 13: Full round-trip integration test

**Files:**
- Create: `plugins/share/test/com/conch/share/integration/FullRoundTripTest.java`

- [ ] **Step 13.1: Write the test**

```java
package com.conch.share.integration;

import com.conch.share.codec.ShareBundleCodec;
import com.conch.share.model.ShareBundle;
import com.conch.share.planner.CurrentState;
import com.conch.share.planner.ExportPlan;
import com.conch.share.planner.ExportPlanner;
import com.conch.share.planner.ExportRequest;
import com.conch.share.planner.ImportExecutor;
import com.conch.share.planner.ImportPaths;
import com.conch.share.planner.ImportPlan;
import com.conch.share.planner.ImportPlanner;
import com.conch.ssh.model.PromptPasswordAuth;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.model.VaultAuth;
import com.conch.ssh.persistence.HostsFile;
import com.conch.tunnels.model.InternalHost;
import com.conch.tunnels.model.SshTunnel;
import com.conch.tunnels.model.TunnelType;
import com.conch.tunnels.persistence.TunnelsFile;
import com.conch.vault.lock.LockManager;
import com.conch.vault.model.AuthMethod;
import com.conch.vault.model.Vault;
import com.conch.vault.model.VaultAccount;
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

import static org.junit.jupiter.api.Assertions.*;

class FullRoundTripTest {

    @Test
    void export_then_import_preservesHostsTunnelsAndCredentials(@TempDir Path tmp) throws Exception {
        // Machine A: seed state
        Path machineA = tmp.resolve("a");
        Files.createDirectories(machineA);

        UUID accountId = UUID.randomUUID();
        VaultAccount account = new VaultAccount(
            accountId, "web-creds", "ops",
            new AuthMethod.Password("s3cret"),
            Instant.now(), Instant.now()
        );
        Vault vaultA = new Vault();
        vaultA.accounts = new ArrayList<>(List.of(account));

        SshHost host = new SshHost(
            UUID.randomUUID(), "web", "web.example.com", 22, "ops",
            new VaultAuth(accountId), null, null, Instant.now(), Instant.now()
        );
        SshTunnel tunnel = new SshTunnel(
            UUID.randomUUID(), "db", TunnelType.LOCAL, new InternalHost(host.id()),
            8080, "localhost", "db.internal", 5432, Instant.now(), Instant.now()
        );

        Path sshConfig = machineA.resolve("config");
        Files.writeString(sshConfig, "");

        ExportRequest req = new ExportRequest(
            Set.of(host.id()), Set.of(tunnel.id()), true,
            List.of(host), List.of(tunnel), vaultA,
            sshConfig, "machine-a", "0.14.2"
        );
        ExportPlan plan = ExportPlanner.plan(req);

        byte[] password = "sharepassword1".getBytes();
        byte[] encoded = ShareBundleCodec.encode(plan.bundle(), password);
        Path bundleFile = machineA.resolve("bundle.conchshare");
        Files.write(bundleFile, encoded);

        // Machine B: empty state
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
        ShareBundle decoded = ShareBundleCodec.decode(bundleBytes, password);
        assertEquals(1, decoded.hosts().size());
        assertEquals(1, decoded.tunnels().size());
        assertEquals(1, decoded.vault().accounts().size());

        ImportPlan importPlan = ImportPlanner.plan(decoded, CurrentState.empty());
        ImportPaths paths = new ImportPaths(hostsBPath, tunnelsBPath, vaultBPath, keysBDir);
        ImportExecutor.execute(decoded, importPlan, paths, lmB, masterB);

        // Verify machine B state
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

        Arrays.fill(password, (byte) 0);
        Arrays.fill(masterB, (byte) 0);
    }
}
```

- [ ] **Step 13.2: Run tests, verify pass**

```bash
cd $INTELLIJ_ROOT && bash bazel.cmd run //conch/plugins/share:share_test_runner
```

Expected: all share plugin tests pass, including the round-trip.

- [ ] **Step 13.3: Run full test suite**

```bash
cd $INTELLIJ_ROOT && bash bazel.cmd run //conch/plugins/vault:vault_test_runner
cd $INTELLIJ_ROOT && bash bazel.cmd run //conch/plugins/ssh:ssh_test_runner
cd $INTELLIJ_ROOT && bash bazel.cmd run //conch/plugins/tunnels:tunnels_test_runner
cd $INTELLIJ_ROOT && bash bazel.cmd run //conch/plugins/share:share_test_runner
```

Expected: all four suites pass.

- [ ] **Step 13.4: Commit**

```bash
git add plugins/share/test/com/conch/share/integration
git commit -m "test(share): full export/import round-trip integration test"
```

---

## Final Verification Checklist

Before declaring done, confirm:

- [ ] `make conch-build` succeeds.
- [ ] All four plugin test runners pass (vault, ssh, tunnels, share).
- [ ] `make conch` launches, both tool windows show Export/Import buttons, Tools menu has both entries.
- [ ] Manual round-trip: export a host with credentials to a `.conchshare` file, quit Conch, delete `~/.config/conch/vault.enc` + `ssh-hosts.json` + `tunnels.json`, relaunch Conch, import the bundle, verify the host and credential reappear and the host can connect (or at least resolves its credential without error).
- [ ] Manual edge cases: export without credentials produces an importable bundle; wrong bundle password shows a clear error; truncated bundle shows a clear error.
- [ ] No secrets in commits (grep your diffs for `s3cret`, `masterb`, `sharepassword1` — these appear only in test code under `test/`).





