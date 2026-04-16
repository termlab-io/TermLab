# Tunnel Manager Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Tunnel Manager plugin that lets users create, manage, and activate SSH tunnels (local and remote port forwarding) through a right-sidebar tool window, with hosts sourced from both the SSH plugin's HostStore and `~/.ssh/config` aliases.

**Architecture:** Separate plugin at `plugins/tunnels/` depending on the SSH plugin for host definitions, credential resolution, and connection infrastructure. Data model follows the SSH plugin's patterns (sealed types, Gson adapters, versioned JSON). Connection engine establishes per-tunnel `ClientSession`s via `TermLabSshClient`, then calls MINA's `PortForwardingManager` API (which `ClientSession` directly implements) for local/remote forwarding. Tool window mirrors `HostsToolWindow`'s layout with added state indicators (connected/disconnected/error). Health monitoring via daemon threads watching `session.waitFor(CLOSED)`.

**Tech Stack:** Java 21 records + sealed types, Apache MINA SSHD 2.15 (`ClientSession.startLocalPortForwarding` / `startRemotePortForwarding`), IntelliJ Platform (`ToolWindowFactory`, `DialogWrapper`, `SearchEverywhereContributor`), Gson, JUnit 5.

**Reference spec:** `docs/specs/2026-04-12-tunnel-manager-design.md`

**Reference patterns:** The SSH plugin at `plugins/ssh/` is the canonical pattern for everything — model records, sealed types + Gson adapters, persistence to `~/.config/termlab/`, application services, tool windows, edit dialogs, SE contributors, and plugin.xml structure.

---

## Build & test commands

From `/Users/dustin/projects/termlab_workbench`:

```bash
# Full product build:
make termlab-build

# Tunnel plugin tests (once test runner exists):
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/tunnels:tunnels_test_runner

# SSH plugin tests (regression):
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/ssh:ssh_test_runner
```

---

## File Structure

### New files (`plugins/tunnels/`)

**Build:**
- `plugins/tunnels/BUILD.bazel` — Bazel targets for main, test, and test runner
- `plugins/tunnels/intellij.termlab.tunnels.iml` — IntelliJ module file
- `plugins/tunnels/resources/META-INF/plugin.xml` — plugin descriptor

**Model (`src/com/termlab/tunnels/model/`):**
- `SshTunnel.java` — record: id, label, type, host, bindPort, bindAddress, targetHost, targetPort, timestamps
- `TunnelType.java` — enum: LOCAL, REMOTE
- `TunnelHost.java` — sealed interface + `InternalHost(UUID)` + `SshConfigHost(String)`
- `TunnelState.java` — enum: DISCONNECTED, CONNECTING, ACTIVE, ERROR
- `TunnelStore.java` — application service holding the tunnel list, mirroring HostStore

**Persistence (`src/com/termlab/tunnels/persistence/`):**
- `TunnelPaths.java` — `~/.config/termlab/tunnels.json`
- `TunnelsFile.java` — atomic JSON save/load with versioned envelope
- `TunnelGson.java` — Gson with Instant + TunnelHost adapters

**Client (`src/com/termlab/tunnels/client/`):**
- `TunnelConnection.java` — handle for one active tunnel (session + bound address + state)
- `TunnelConnectionManager.java` — application service managing active tunnel connections
- `SshConfigParser.java` — lightweight `~/.ssh/config` Host alias reader

**Tool window (`src/com/termlab/tunnels/toolwindow/`):**
- `TunnelsToolWindow.java` — JPanel with toolbar + JBList + context menu
- `TunnelsToolWindowFactory.java` — ToolWindowFactory impl
- `TunnelCellRenderer.java` — two-line renderer with status icons

**UI (`src/com/termlab/tunnels/ui/`):**
- `TunnelEditDialog.java` — modal add/edit dialog

**Search Everywhere (`src/com/termlab/tunnels/palette/`):**
- `TunnelsSearchEverywhereContributor.java` — SE tab with connect/disconnect action

**Tests (`test/com/termlab/tunnels/`):**
- `TestRunner.java` — JUnit 5 standalone runner
- `model/SshTunnelTest.java` — record factory + with-methods
- `model/TunnelStoreTest.java` — CRUD + save/reload
- `persistence/TunnelHostJsonTest.java` — Gson adapter round-trips
- `persistence/TunnelsFileTest.java` — save/load/atomic-write
- `client/SshConfigParserTest.java` — Host alias extraction

### Modified files (SSH plugin + core)

- `plugins/ssh/src/com/termlab/ssh/client/TermLabSshClient.java` — add `connectSession()` method (auth without shell channel)
- `customization/resources/idea/TermLabApplicationInfo.xml` — add `<essential-plugin>com.termlab.tunnels</essential-plugin>`
- `build/src/org/jetbrains/intellij/build/termlab/TermLabProperties.kt` — add `"intellij.termlab.tunnels"` to bundledPluginModules
- `core/src/com/termlab/core/palette/TermLabTabsCustomizationStrategy.java` — add `"TermLabTunnels"` to ALLOWED_TAB_IDS

---

## Phase 1 — Data model, persistence, store

### Task 1.1: Bazel scaffolding

**Files:**
- Create: `plugins/tunnels/BUILD.bazel`
- Create: `plugins/tunnels/intellij.termlab.tunnels.iml`
- Create: `plugins/tunnels/resources/META-INF/plugin.xml` (placeholder)

- [ ] **Step 1: Create `BUILD.bazel`**

```bazel
load("@rules_java//java:defs.bzl", "java_binary")
load("@rules_jvm//:jvm.bzl", "jvm_library", "resourcegroup")

resourcegroup(
    name = "tunnels_resources",
    srcs = glob(["resources/**/*"]),
    strip_prefix = "resources",
)

jvm_library(
    name = "tunnels",
    module_name = "intellij.termlab.tunnels",
    visibility = ["//visibility:public"],
    srcs = glob(["src/**/*.java"], allow_empty = True),
    resources = [":tunnels_resources"],
    deps = [
        "//termlab/sdk",
        "//termlab/core",
        "//termlab/plugins/ssh",
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
        "//libraries/sshd-osgi",
        "//libraries/bouncy-castle-provider",
        "@lib//:jetbrains-annotations",
        "@lib//:gson",
        "@lib//:kotlin-stdlib",
    ],
)

jvm_library(
    name = "tunnels_test_lib",
    module_name = "intellij.termlab.tunnels.tests",
    visibility = ["//visibility:public"],
    srcs = glob(["test/**/*.java"], allow_empty = True),
    deps = [
        ":tunnels",
        "//termlab/sdk",
        "//termlab/plugins/ssh",
        "//libraries/junit5",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
        "//libraries/sshd-osgi",
        "@lib//:jetbrains-annotations",
        "@lib//:gson",
    ],
)

java_binary(
    name = "tunnels_test_runner",
    main_class = "com.termlab.tunnels.TestRunner",
    runtime_deps = [
        ":tunnels_test_lib",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
    ],
)
```

- [ ] **Step 2: Create `intellij.termlab.tunnels.iml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<module type="JAVA_MODULE" version="4">
  <component name="NewModuleRootManager" inherit-compiler-output="true">
    <content url="file://$MODULE_DIR$">
      <sourceFolder url="file://$MODULE_DIR$/src" isTestSource="false"/>
      <sourceFolder url="file://$MODULE_DIR$/test" isTestSource="true"/>
      <sourceFolder url="file://$MODULE_DIR$/resources" type="java-resource"/>
    </content>
    <orderEntry type="inheritedJdk"/>
    <orderEntry type="sourceFolder" forTests="false"/>
  </component>
</module>
```

- [ ] **Step 3: Create placeholder `plugin.xml`**

```xml
<idea-plugin>
    <id>com.termlab.tunnels</id>
    <name>TermLab Tunnels</name>
    <vendor>TermLab</vendor>
    <description>SSH tunnel manager. Creates local and remote port
    forwarding tunnels through hosts managed by the TermLab SSH plugin
    or defined in ~/.ssh/config.</description>

    <depends>com.termlab.core</depends>
    <depends>com.termlab.ssh</depends>
    <depends>com.termlab.vault</depends>
</idea-plugin>
```

- [ ] **Step 4: Create test runner**

Create `plugins/tunnels/test/com/termlab/tunnels/TestRunner.java`:

```java
package com.termlab.tunnels;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

public final class TestRunner {
    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectPackage("com.termlab.tunnels"))
            .build();

        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(request, listener);

        TestExecutionSummary summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));
        if (summary.getTotalFailureCount() > 0) System.exit(1);
    }
}
```

- [ ] **Step 5: Build to verify scaffolding compiles**

```bash
cd /Users/dustin/projects/termlab_workbench && make termlab-build
```

- [ ] **Step 6: Commit**

```bash
git add plugins/tunnels/
git commit -m "feat(tunnels): phase 1.1 — Bazel scaffolding and plugin shell

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 1.2: Data model — SshTunnel, TunnelType, TunnelHost, TunnelState

**Files:**
- Create: `plugins/tunnels/src/com/termlab/tunnels/model/TunnelType.java`
- Create: `plugins/tunnels/src/com/termlab/tunnels/model/TunnelHost.java`
- Create: `plugins/tunnels/src/com/termlab/tunnels/model/InternalHost.java`
- Create: `plugins/tunnels/src/com/termlab/tunnels/model/SshConfigHost.java`
- Create: `plugins/tunnels/src/com/termlab/tunnels/model/TunnelState.java`
- Create: `plugins/tunnels/src/com/termlab/tunnels/model/SshTunnel.java`
- Create: `plugins/tunnels/test/com/termlab/tunnels/model/SshTunnelTest.java`

- [ ] **Step 1: Write the failing test**

Create `plugins/tunnels/test/com/termlab/tunnels/model/SshTunnelTest.java`:

```java
package com.termlab.tunnels.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SshTunnelTest {

    @Test
    void create_populatesIdAndTimestamps() {
        SshTunnel tunnel = SshTunnel.create(
            "prod-db", TunnelType.LOCAL, new InternalHost(UUID.randomUUID()),
            3307, "localhost", "db.internal", 3306);
        assertNotNull(tunnel.id());
        assertEquals("prod-db", tunnel.label());
        assertEquals(TunnelType.LOCAL, tunnel.type());
        assertEquals(3307, tunnel.bindPort());
        assertEquals("localhost", tunnel.bindAddress());
        assertEquals("db.internal", tunnel.targetHost());
        assertEquals(3306, tunnel.targetPort());
        assertNotNull(tunnel.createdAt());
        assertEquals(tunnel.createdAt(), tunnel.updatedAt());
    }

    @Test
    void create_withSshConfigHost() {
        SshTunnel tunnel = SshTunnel.create(
            "bastion-redis", TunnelType.LOCAL, new SshConfigHost("bastion"),
            6380, "localhost", "redis.internal", 6379);
        assertInstanceOf(SshConfigHost.class, tunnel.host());
        assertEquals("bastion", ((SshConfigHost) tunnel.host()).alias());
    }

    @Test
    void create_remoteTunnel() {
        SshTunnel tunnel = SshTunnel.create(
            "expose-dev", TunnelType.REMOTE, new InternalHost(UUID.randomUUID()),
            9090, "0.0.0.0", "localhost", 8080);
        assertEquals(TunnelType.REMOTE, tunnel.type());
        assertEquals("0.0.0.0", tunnel.bindAddress());
    }

    @Test
    void withLabel_preservesIdentity() {
        SshTunnel original = SshTunnel.create(
            "old", TunnelType.LOCAL, new SshConfigHost("host"),
            3307, "localhost", "target", 3306);
        SshTunnel renamed = original.withLabel("new");
        assertEquals(original.id(), renamed.id());
        assertEquals(original.createdAt(), renamed.createdAt());
        assertEquals("new", renamed.label());
    }

    @Test
    void withEdited_replacesAllFields() {
        UUID hostId = UUID.randomUUID();
        SshTunnel original = SshTunnel.create(
            "old", TunnelType.LOCAL, new InternalHost(hostId),
            3307, "localhost", "old-target", 3306);
        UUID newHostId = UUID.randomUUID();
        SshTunnel edited = original.withEdited(
            "new", TunnelType.REMOTE, new InternalHost(newHostId),
            9090, "0.0.0.0", "new-target", 8080);
        assertEquals(original.id(), edited.id());
        assertEquals(original.createdAt(), edited.createdAt());
        assertEquals("new", edited.label());
        assertEquals(TunnelType.REMOTE, edited.type());
        assertEquals(9090, edited.bindPort());
        assertEquals("0.0.0.0", edited.bindAddress());
        assertEquals("new-target", edited.targetHost());
        assertEquals(8080, edited.targetPort());
    }

    @Test
    void defaultBindAddress() {
        assertEquals("localhost", SshTunnel.DEFAULT_BIND_ADDRESS);
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd build //termlab/plugins/tunnels:tunnels_test_lib
```

Expected: compile error on missing classes.

- [ ] **Step 3: Create `TunnelType.java`**

```java
package com.termlab.tunnels.model;

/**
 * SSH tunnel direction.
 *
 * <ul>
 *   <li>{@link #LOCAL} — binds a local port and forwards traffic to a
 *       remote target through the SSH connection ({@code -L}).</li>
 *   <li>{@link #REMOTE} — binds a port on the remote host and forwards
 *       traffic back to a local target ({@code -R}).</li>
 * </ul>
 */
public enum TunnelType {
    LOCAL, REMOTE
}
```

- [ ] **Step 4: Create `TunnelHost.java`, `InternalHost.java`, `SshConfigHost.java`**

`TunnelHost.java`:
```java
package com.termlab.tunnels.model;

/**
 * Which host a tunnel connects through. Two variants:
 * <ul>
 *   <li>{@link InternalHost} — references an {@code SshHost} in the SSH
 *       plugin's {@code HostStore} by UUID.</li>
 *   <li>{@link SshConfigHost} — references a {@code Host} alias from
 *       {@code ~/.ssh/config}. MINA resolves connection details from the
 *       config file at connect time.</li>
 * </ul>
 */
public sealed interface TunnelHost permits InternalHost, SshConfigHost {
}
```

`InternalHost.java`:
```java
package com.termlab.tunnels.model;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * References an {@code SshHost} in the SSH plugin's {@code HostStore}.
 *
 * @param hostId the host's stable UUID
 */
public record InternalHost(@NotNull UUID hostId) implements TunnelHost {
}
```

`SshConfigHost.java`:
```java
package com.termlab.tunnels.model;

import org.jetbrains.annotations.NotNull;

/**
 * References a {@code Host} alias from {@code ~/.ssh/config}. At
 * connect time, MINA resolves hostname, port, identity file, proxy
 * settings, etc. from the config file.
 *
 * @param alias the Host alias (e.g., "bastion", "prod-db")
 */
public record SshConfigHost(@NotNull String alias) implements TunnelHost {
}
```

- [ ] **Step 5: Create `TunnelState.java`**

```java
package com.termlab.tunnels.model;

/**
 * Lifecycle state of a tunnel.
 */
public enum TunnelState {
    /** Saved but not active. */
    DISCONNECTED,
    /** Connection in progress (credential resolution, TCP handshake, auth). */
    CONNECTING,
    /** Port forwarding established and healthy. */
    ACTIVE,
    /** Connection failed or tunnel dropped mid-session. */
    ERROR
}
```

- [ ] **Step 6: Create `SshTunnel.java`**

```java
package com.termlab.tunnels.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A saved SSH tunnel definition.
 *
 * <p>Persisted to {@code ~/.config/termlab/tunnels.json}. The actual
 * forwarding is managed by {@code TunnelConnectionManager} at runtime;
 * this record is the at-rest configuration.
 *
 * @param id           stable UUID
 * @param label        user-facing name ("prod-db-proxy")
 * @param type         LOCAL (-L) or REMOTE (-R)
 * @param host         which host to tunnel through
 * @param bindPort     local port (LOCAL) or remote port (REMOTE)
 * @param bindAddress  bind address (default "localhost")
 * @param targetHost   destination hostname
 * @param targetPort   destination port
 * @param createdAt    when the tunnel was created
 * @param updatedAt    when the tunnel was last edited
 */
public record SshTunnel(
    @NotNull UUID id,
    @NotNull String label,
    @NotNull TunnelType type,
    @NotNull TunnelHost host,
    int bindPort,
    @NotNull String bindAddress,
    @NotNull String targetHost,
    int targetPort,
    @NotNull Instant createdAt,
    @NotNull Instant updatedAt
) {
    public static final String DEFAULT_BIND_ADDRESS = "localhost";

    public SshTunnel withLabel(@NotNull String newLabel) {
        return new SshTunnel(id, newLabel, type, host, bindPort, bindAddress,
            targetHost, targetPort, createdAt, Instant.now());
    }

    public SshTunnel withEdited(
        @NotNull String newLabel,
        @NotNull TunnelType newType,
        @NotNull TunnelHost newHost,
        int newBindPort,
        @NotNull String newBindAddress,
        @NotNull String newTargetHost,
        int newTargetPort
    ) {
        return new SshTunnel(id, newLabel, newType, newHost, newBindPort,
            newBindAddress, newTargetHost, newTargetPort, createdAt, Instant.now());
    }

    public static @NotNull SshTunnel create(
        @NotNull String label,
        @NotNull TunnelType type,
        @NotNull TunnelHost host,
        int bindPort,
        @NotNull String bindAddress,
        @NotNull String targetHost,
        int targetPort
    ) {
        Instant now = Instant.now();
        return new SshTunnel(UUID.randomUUID(), label, type, host,
            bindPort, bindAddress, targetHost, targetPort, now, now);
    }
}
```

- [ ] **Step 7: Run tests**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/tunnels:tunnels_test_runner
```

Expected: 6/6 passing.

- [ ] **Step 8: Commit**

```bash
git add plugins/tunnels/src/com/termlab/tunnels/model/ plugins/tunnels/test/com/termlab/tunnels/model/
git commit -m "feat(tunnels): phase 1.2 — SshTunnel, TunnelType, TunnelHost, TunnelState

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 1.3: Persistence — TunnelGson, TunnelPaths, TunnelsFile, TunnelStore

**Files:**
- Create: `plugins/tunnels/src/com/termlab/tunnels/persistence/TunnelGson.java`
- Create: `plugins/tunnels/src/com/termlab/tunnels/persistence/TunnelPaths.java`
- Create: `plugins/tunnels/src/com/termlab/tunnels/persistence/TunnelsFile.java`
- Create: `plugins/tunnels/src/com/termlab/tunnels/model/TunnelStore.java`
- Create: `plugins/tunnels/test/com/termlab/tunnels/persistence/TunnelHostJsonTest.java`
- Create: `plugins/tunnels/test/com/termlab/tunnels/persistence/TunnelsFileTest.java`
- Create: `plugins/tunnels/test/com/termlab/tunnels/model/TunnelStoreTest.java`

- [ ] **Step 1: Write `TunnelHostJsonTest.java`**

```java
package com.termlab.tunnels.persistence;

import com.termlab.tunnels.model.InternalHost;
import com.termlab.tunnels.model.SshConfigHost;
import com.termlab.tunnels.model.TunnelHost;
import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TunnelHostJsonTest {

    @Test
    void internalHost_roundTrip() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String json = TunnelGson.GSON.toJson(new InternalHost(id), TunnelHost.class);
        assertTrue(json.contains("\"type\": \"internal\""), json);
        assertTrue(json.contains(id.toString()), json);

        TunnelHost parsed = TunnelGson.GSON.fromJson(json, TunnelHost.class);
        assertInstanceOf(InternalHost.class, parsed);
        assertEquals(id, ((InternalHost) parsed).hostId());
    }

    @Test
    void sshConfigHost_roundTrip() {
        String json = TunnelGson.GSON.toJson(new SshConfigHost("bastion"), TunnelHost.class);
        assertTrue(json.contains("\"type\": \"ssh_config\""), json);
        assertTrue(json.contains("\"alias\": \"bastion\""), json);

        TunnelHost parsed = TunnelGson.GSON.fromJson(json, TunnelHost.class);
        assertInstanceOf(SshConfigHost.class, parsed);
        assertEquals("bastion", ((SshConfigHost) parsed).alias());
    }

    @Test
    void unknownType_throws() {
        assertThrows(JsonParseException.class,
            () -> TunnelGson.GSON.fromJson("{\"type\":\"bogus\"}", TunnelHost.class));
    }
}
```

- [ ] **Step 2: Write `TunnelsFileTest.java`**

```java
package com.termlab.tunnels.persistence;

import com.termlab.tunnels.model.InternalHost;
import com.termlab.tunnels.model.SshConfigHost;
import com.termlab.tunnels.model.SshTunnel;
import com.termlab.tunnels.model.TunnelType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TunnelsFileTest {

    @Test
    void load_missingFileReturnsEmpty(@TempDir Path tmp) throws Exception {
        assertTrue(TunnelsFile.load(tmp.resolve("nope.json")).isEmpty());
    }

    @Test
    void saveAndLoad_roundTrips(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("tunnels.json");
        UUID hostId = UUID.randomUUID();
        SshTunnel tunnel = SshTunnel.create(
            "prod-db", TunnelType.LOCAL, new InternalHost(hostId),
            3307, "localhost", "db.internal", 3306);

        TunnelsFile.save(file, List.of(tunnel));
        List<SshTunnel> loaded = TunnelsFile.load(file);

        assertEquals(1, loaded.size());
        SshTunnel restored = loaded.get(0);
        assertEquals(tunnel.id(), restored.id());
        assertEquals("prod-db", restored.label());
        assertEquals(TunnelType.LOCAL, restored.type());
        assertInstanceOf(InternalHost.class, restored.host());
        assertEquals(hostId, ((InternalHost) restored.host()).hostId());
        assertEquals(3307, restored.bindPort());
        assertEquals("db.internal", restored.targetHost());
    }

    @Test
    void saveAndLoad_sshConfigHost(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("tunnels.json");
        SshTunnel tunnel = SshTunnel.create(
            "bastion", TunnelType.REMOTE, new SshConfigHost("bastion"),
            9090, "0.0.0.0", "localhost", 8080);

        TunnelsFile.save(file, List.of(tunnel));
        List<SshTunnel> loaded = TunnelsFile.load(file);

        assertEquals(1, loaded.size());
        assertInstanceOf(SshConfigHost.class, loaded.get(0).host());
        assertEquals("bastion", ((SshConfigHost) loaded.get(0).host()).alias());
    }

    @Test
    void save_isAtomic(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("tunnels.json");
        TunnelsFile.save(file, List.of());
        assertFalse(Files.exists(tmp.resolve("tunnels.json.tmp")));
        assertTrue(Files.exists(file));
    }

    @Test
    void load_malformedReturnsEmpty(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("tunnels.json");
        Files.writeString(file, "{ not valid json");
        assertTrue(TunnelsFile.load(file).isEmpty());
    }
}
```

- [ ] **Step 3: Write `TunnelStoreTest.java`**

```java
package com.termlab.tunnels.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TunnelStoreTest {

    @Test
    void addAndGet(@TempDir Path tmp) {
        TunnelStore store = new TunnelStore(tmp.resolve("tunnels.json"), List.of());
        SshTunnel t = SshTunnel.create("a", TunnelType.LOCAL, new SshConfigHost("h"),
            3307, "localhost", "target", 3306);
        store.addTunnel(t);
        assertEquals(1, store.getTunnels().size());
        assertEquals(t.id(), store.findById(t.id()).id());
    }

    @Test
    void remove(@TempDir Path tmp) {
        TunnelStore store = new TunnelStore(tmp.resolve("tunnels.json"), List.of());
        SshTunnel t = SshTunnel.create("a", TunnelType.LOCAL, new SshConfigHost("h"),
            3307, "localhost", "target", 3306);
        store.addTunnel(t);
        assertTrue(store.removeTunnel(t.id()));
        assertEquals(0, store.getTunnels().size());
    }

    @Test
    void update(@TempDir Path tmp) {
        TunnelStore store = new TunnelStore(tmp.resolve("tunnels.json"), List.of());
        SshTunnel t = SshTunnel.create("a", TunnelType.LOCAL, new SshConfigHost("h"),
            3307, "localhost", "target", 3306);
        store.addTunnel(t);
        SshTunnel edited = t.withLabel("b");
        assertTrue(store.updateTunnel(edited));
        assertEquals("b", store.findById(t.id()).label());
    }

    @Test
    void saveAndReload(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("tunnels.json");
        TunnelStore store = new TunnelStore(file, List.of());
        store.addTunnel(SshTunnel.create("a", TunnelType.LOCAL, new SshConfigHost("h"),
            3307, "localhost", "target", 3306));
        store.save();

        TunnelStore reloaded = new TunnelStore(file);
        assertEquals(1, reloaded.getTunnels().size());
        assertEquals("a", reloaded.getTunnels().get(0).label());
    }

    @Test
    void findById_missingReturnsNull(@TempDir Path tmp) {
        TunnelStore store = new TunnelStore(tmp.resolve("tunnels.json"), List.of());
        assertNull(store.findById(UUID.randomUUID()));
    }
}
```

- [ ] **Step 4: Create `TunnelPaths.java`**

```java
package com.termlab.tunnels.persistence;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class TunnelPaths {
    private TunnelPaths() {}

    public static Path tunnelsFile() {
        return Paths.get(System.getProperty("user.home"), ".config", "termlab", "tunnels.json");
    }
}
```

- [ ] **Step 5: Create `TunnelGson.java`**

```java
package com.termlab.tunnels.persistence;

import com.termlab.tunnels.model.InternalHost;
import com.termlab.tunnels.model.SshConfigHost;
import com.termlab.tunnels.model.TunnelHost;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

public final class TunnelGson {

    public static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(Instant.class, new InstantAdapter())
        .registerTypeAdapter(TunnelHost.class, new TunnelHostSerializer())
        .registerTypeAdapter(TunnelHost.class, new TunnelHostDeserializer())
        .create();

    private TunnelGson() {}

    private static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) { out.nullValue(); } else { out.value(value.toString()); }
        }
        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
            return Instant.parse(in.nextString());
        }
    }

    private static final class TunnelHostSerializer implements JsonSerializer<TunnelHost> {
        @Override
        public JsonElement serialize(TunnelHost src, java.lang.reflect.Type typeOfSrc,
                                     com.google.gson.JsonSerializationContext ctx) {
            JsonObject obj = new JsonObject();
            switch (src) {
                case InternalHost h -> {
                    obj.addProperty("type", "internal");
                    obj.addProperty("hostId", h.hostId().toString());
                }
                case SshConfigHost h -> {
                    obj.addProperty("type", "ssh_config");
                    obj.addProperty("alias", h.alias());
                }
            }
            return obj;
        }
    }

    private static final class TunnelHostDeserializer implements JsonDeserializer<TunnelHost> {
        @Override
        public TunnelHost deserialize(JsonElement json, java.lang.reflect.Type typeOfT,
                                      com.google.gson.JsonDeserializationContext ctx) {
            JsonObject obj = json.getAsJsonObject();
            String type = obj.get("type").getAsString();
            return switch (type) {
                case "internal" -> new InternalHost(UUID.fromString(obj.get("hostId").getAsString()));
                case "ssh_config" -> new SshConfigHost(obj.get("alias").getAsString());
                default -> throw new JsonParseException("unknown TunnelHost type: " + type);
            };
        }
    }
}
```

- [ ] **Step 6: Create `TunnelsFile.java`**

```java
package com.termlab.tunnels.persistence;

import com.termlab.tunnels.model.SshTunnel;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TunnelsFile {

    public static final int VERSION = 1;

    private TunnelsFile() {}

    public static void save(@NotNull Path target, @NotNull List<SshTunnel> tunnels) throws IOException {
        Envelope envelope = new Envelope(VERSION, new ArrayList<>(tunnels));
        String json = TunnelGson.GSON.toJson(envelope);
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static @NotNull List<SshTunnel> load(@NotNull Path source) throws IOException {
        if (!Files.isRegularFile(source)) return Collections.emptyList();
        String json = Files.readString(source);
        try {
            Envelope envelope = TunnelGson.GSON.fromJson(json, Envelope.class);
            if (envelope == null || envelope.tunnels == null) return Collections.emptyList();
            return envelope.tunnels;
        } catch (Exception malformed) {
            return Collections.emptyList();
        }
    }

    static final class Envelope {
        int version;
        List<SshTunnel> tunnels;
        Envelope() {}
        Envelope(int version, List<SshTunnel> tunnels) {
            this.version = version;
            this.tunnels = tunnels;
        }
    }
}
```

- [ ] **Step 7: Create `TunnelStore.java`**

```java
package com.termlab.tunnels.model;

import com.termlab.tunnels.persistence.TunnelPaths;
import com.termlab.tunnels.persistence.TunnelsFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Application service holding the saved tunnel list. Mirrors
 * {@code com.termlab.ssh.model.HostStore}.
 */
public final class TunnelStore {

    private final Path filePath;
    private final List<SshTunnel> tunnels;

    /** Framework constructor — loads from default path. */
    public TunnelStore() {
        this(TunnelPaths.tunnelsFile());
    }

    /** Loads from the given path. */
    public TunnelStore(@NotNull Path filePath) {
        this.filePath = filePath;
        List<SshTunnel> loaded;
        try {
            loaded = new ArrayList<>(TunnelsFile.load(filePath));
        } catch (IOException e) {
            loaded = new ArrayList<>();
        }
        this.tunnels = loaded;
    }

    /** Test constructor with explicit initial list. */
    public TunnelStore(@NotNull Path filePath, @NotNull List<SshTunnel> initial) {
        this.filePath = filePath;
        this.tunnels = new ArrayList<>(initial);
    }

    public @NotNull List<SshTunnel> getTunnels() {
        return Collections.unmodifiableList(tunnels);
    }

    public @Nullable SshTunnel findById(@NotNull UUID id) {
        for (SshTunnel t : tunnels) {
            if (t.id().equals(id)) return t;
        }
        return null;
    }

    public void addTunnel(@NotNull SshTunnel tunnel) {
        tunnels.add(tunnel);
    }

    public boolean removeTunnel(@NotNull UUID id) {
        return tunnels.removeIf(t -> t.id().equals(id));
    }

    public boolean updateTunnel(@NotNull SshTunnel updated) {
        for (int i = 0; i < tunnels.size(); i++) {
            if (tunnels.get(i).id().equals(updated.id())) {
                tunnels.set(i, updated);
                return true;
            }
        }
        return false;
    }

    public void save() throws IOException {
        TunnelsFile.save(filePath, tunnels);
    }

    public void reload() throws IOException {
        tunnels.clear();
        tunnels.addAll(TunnelsFile.load(filePath));
    }
}
```

- [ ] **Step 8: Run all tests**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/tunnels:tunnels_test_runner
```

Expected: all tests pass (SshTunnelTest + TunnelHostJsonTest + TunnelsFileTest + TunnelStoreTest).

- [ ] **Step 9: Commit**

```bash
git add plugins/tunnels/src/com/termlab/tunnels/persistence/ plugins/tunnels/src/com/termlab/tunnels/model/TunnelStore.java plugins/tunnels/test/
git commit -m "feat(tunnels): phase 1.3 — Gson adapters, persistence, TunnelStore

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Phase 1 gate

```bash
cd /Users/dustin/projects/termlab_workbench && make termlab-build
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/tunnels:tunnels_test_runner
```

All tests pass, full product builds.

---

## Phase 2 — Connection engine

### Task 2.1: `TermLabSshClient.connectSession()` — auth without shell channel

**Files:**
- Modify: `plugins/ssh/src/com/termlab/ssh/client/TermLabSshClient.java`

- [ ] **Step 1: Add `connectSession()` method to `TermLabSshClient`**

Read the current `TermLabSshClient.java`. After the existing `connect()` method, add a new method that does TCP → auth but skips shell channel opening. This returns a raw `ClientSession` instead of an `SshConnection`:

```java
/**
 * Connect and authenticate without opening a shell channel. Returns
 * the raw {@link ClientSession} for callers that need to do something
 * other than a terminal — port forwarding, SFTP, etc.
 *
 * <p>The caller owns the returned session and MUST close it when done.
 */
public @NotNull ClientSession connectSession(
    @NotNull SshHost host,
    @NotNull SshResolvedCredential credential,
    @NotNull ServerKeyVerifier verifier
) throws SshConnectException {
    SshClient mina = ensureStarted();
    LOG.info("TermLab SSH: connectSession (no shell) host=" + host.host() + ":" + host.port());

    mina.setServerKeyVerifier(verifier);

    ClientSession session;
    try {
        ConnectFuture connectFuture = connectFutureFor(mina, host, credential);
        session = connectFuture.verify(CONNECT_TIMEOUT).getSession();
    } catch (IllegalArgumentException e) {
        throw new SshConnectException(
            SshConnectException.Kind.INVALID_PROXY_CONFIG,
            "Invalid SSH proxy configuration: " + e.getMessage(), e);
    } catch (IOException e) {
        throw new SshConnectException(
            SshConnectException.Kind.HOST_UNREACHABLE,
            "Could not reach " + host.host() + ":" + host.port() + " — " + e.getMessage(), e);
    }

    session.setServerKeyVerifier(verifier);

    try {
        attachIdentities(session, credential);
    } catch (IOException | GeneralSecurityException e) {
        safeClose(session);
        throw new SshConnectException(
            SshConnectException.Kind.AUTH_FAILED,
            "Could not load key material: " + e.getMessage(), e);
    }

    configureSessionAuthPreferences(session, credential);

    try {
        AuthFuture auth = session.auth();
        auth.verify(AUTH_TIMEOUT);
        if (!auth.isSuccess()) {
            safeClose(session);
            Throwable cause = auth.getException();
            throw new SshConnectException(
                SshConnectException.Kind.AUTH_FAILED,
                "SSH authentication failed",
                cause != null ? cause : new IOException("auth future did not succeed"));
        }
    } catch (IOException e) {
        safeClose(session);
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        SshConnectException.Kind kind =
            msg.contains("server key") || msg.contains("host key") || msg.contains("rejected")
                ? SshConnectException.Kind.HOST_KEY_REJECTED
                : SshConnectException.Kind.AUTH_FAILED;
        throw new SshConnectException(kind, "Authentication failed: " + e.getMessage(), e);
    }

    return session;
}
```

Note: this reuses the existing `connectFutureFor()`, `attachIdentities()`, `configureSessionAuthPreferences()`, and `safeClose()` private methods already in `TermLabSshClient`. The only difference from `connect()` is that it skips the shell channel opening.

- [ ] **Step 2: Build and run SSH tests**

```bash
cd /Users/dustin/projects/termlab_workbench && make termlab-build
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/ssh:ssh_test_runner
```

Expected: all SSH tests still pass (no behavioral change to existing code).

- [ ] **Step 3: Commit**

```bash
git add plugins/ssh/src/com/termlab/ssh/client/TermLabSshClient.java
git commit -m "feat(ssh): add connectSession() for auth-only without shell channel

Used by the tunnel plugin to get a ClientSession for port forwarding
without wasting a shell channel.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.2: `SshConfigParser` — `~/.ssh/config` host alias reader

**Files:**
- Create: `plugins/tunnels/src/com/termlab/tunnels/client/SshConfigParser.java`
- Create: `plugins/tunnels/test/com/termlab/tunnels/client/SshConfigParserTest.java`

- [ ] **Step 1: Write `SshConfigParserTest.java`**

```java
package com.termlab.tunnels.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SshConfigParserTest {

    @Test
    void parseHostAliases_basicHosts(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Host bastion
                HostName bastion.example.com
                User deploy
            
            Host prod-db
                HostName db.internal
                Port 2222
            """);
        List<String> aliases = SshConfigParser.parseHostAliases(config);
        assertEquals(List.of("bastion", "prod-db"), aliases);
    }

    @Test
    void parseHostAliases_skipsWildcards(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Host *
                ServerAliveInterval 60
            
            Host bastion
                HostName bastion.example.com
            
            Host *.internal
                User deploy
            """);
        List<String> aliases = SshConfigParser.parseHostAliases(config);
        assertEquals(List.of("bastion"), aliases);
    }

    @Test
    void parseHostAliases_multipleAliasesPerLine(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Host alpha bravo charlie
                HostName example.com
            """);
        List<String> aliases = SshConfigParser.parseHostAliases(config);
        assertEquals(List.of("alpha", "bravo", "charlie"), aliases);
    }

    @Test
    void parseHostAliases_skipsNegated(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Host bastion !internal
                HostName bastion.example.com
            """);
        List<String> aliases = SshConfigParser.parseHostAliases(config);
        assertEquals(List.of("bastion"), aliases);
    }

    @Test
    void parseHostAliases_missingFile(@TempDir Path tmp) {
        List<String> aliases = SshConfigParser.parseHostAliases(tmp.resolve("nonexistent"));
        assertTrue(aliases.isEmpty());
    }

    @Test
    void parseHostAliases_emptyFile(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, "");
        assertTrue(SshConfigParser.parseHostAliases(config).isEmpty());
    }
}
```

- [ ] **Step 2: Create `SshConfigParser.java`**

```java
package com.termlab.tunnels.client;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Lightweight parser for {@code ~/.ssh/config} that extracts
 * {@code Host} aliases for the tunnel edit dialog's host picker.
 * Does NOT parse connection details — MINA handles that at connect
 * time via {@code HostConfigEntryResolver}.
 */
public final class SshConfigParser {

    private SshConfigParser() {}

    public static @NotNull List<String> parseHostAliases() {
        return parseHostAliases(
            Paths.get(System.getProperty("user.home"), ".ssh", "config"));
    }

    public static @NotNull List<String> parseHostAliases(@NotNull Path configPath) {
        if (!Files.isRegularFile(configPath)) return List.of();
        List<String> lines;
        try {
            lines = Files.readAllLines(configPath);
        } catch (IOException e) {
            return List.of();
        }

        TreeSet<String> aliases = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.toLowerCase().startsWith("host ")) continue;
            if (trimmed.toLowerCase().startsWith("hostname ")) continue;

            String rest = trimmed.substring(5).trim();
            for (String token : rest.split("\\s+")) {
                token = token.trim();
                if (token.isEmpty()) continue;
                if (token.startsWith("!")) continue;
                if (token.contains("*") || token.contains("?")) continue;
                aliases.add(token);
            }
        }
        return new ArrayList<>(aliases);
    }
}
```

- [ ] **Step 3: Run tests**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/tunnels:tunnels_test_runner
```

Expected: all tests pass including the 6 new SshConfigParser tests.

- [ ] **Step 4: Commit**

```bash
git add plugins/tunnels/src/com/termlab/tunnels/client/SshConfigParser.java plugins/tunnels/test/com/termlab/tunnels/client/
git commit -m "feat(tunnels): phase 2.2 — SshConfigParser for ~/.ssh/config host aliases

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.3: `TunnelConnection` + `TunnelConnectionManager`

**Files:**
- Create: `plugins/tunnels/src/com/termlab/tunnels/client/TunnelConnection.java`
- Create: `plugins/tunnels/src/com/termlab/tunnels/client/TunnelConnectionManager.java`

- [ ] **Step 1: Create `TunnelConnection.java`**

```java
package com.termlab.tunnels.client;

import com.termlab.tunnels.model.TunnelState;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Handle for one active tunnel. Owns a {@link ClientSession} and the
 * bound forwarding address. Lifecycle: CONNECTING → ACTIVE → DISCONNECTED
 * (or ERROR at any point).
 */
public final class TunnelConnection implements AutoCloseable {

    private volatile ClientSession session;
    private volatile SshdSocketAddress boundAddress;
    private volatile TunnelState state;
    private volatile String errorMessage;

    public TunnelConnection(@NotNull ClientSession session) {
        this.session = session;
        this.state = TunnelState.CONNECTING;
    }

    public @Nullable ClientSession session() { return session; }
    public @Nullable SshdSocketAddress boundAddress() { return boundAddress; }
    public @NotNull TunnelState state() { return state; }
    public @Nullable String errorMessage() { return errorMessage; }

    public void markActive(@NotNull SshdSocketAddress bound) {
        this.boundAddress = bound;
        this.state = TunnelState.ACTIVE;
        this.errorMessage = null;
    }

    public void markError(@NotNull String message) {
        this.state = TunnelState.ERROR;
        this.errorMessage = message;
    }

    public void markDisconnected() {
        this.state = TunnelState.DISCONNECTED;
        this.errorMessage = null;
    }

    @Override
    public void close() {
        if (session != null) {
            try {
                session.close(true);
            } catch (IOException ignored) {
            }
            session = null;
        }
        if (state == TunnelState.ACTIVE || state == TunnelState.CONNECTING) {
            state = TunnelState.DISCONNECTED;
        }
    }
}
```

- [ ] **Step 2: Create `TunnelConnectionManager.java`**

```java
package com.termlab.tunnels.client;

import com.termlab.ssh.client.TermLabServerKeyVerifier;
import com.termlab.ssh.client.TermLabSshClient;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.client.SshResolvedCredential;
import com.termlab.ssh.credentials.SshCredentialPicker;
import com.termlab.ssh.credentials.SshCredentialResolver;
import com.termlab.ssh.model.*;
import com.termlab.ssh.ui.InlineCredentialPromptDialog;
import com.termlab.tunnels.model.*;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application service that manages active tunnel connections. Maps
 * tunnel UUID → {@link TunnelConnection}. Handles connect, disconnect,
 * and health monitoring.
 */
public final class TunnelConnectionManager {

    private static final Logger LOG = Logger.getInstance(TunnelConnectionManager.class);

    private final Map<UUID, TunnelConnection> connections = new ConcurrentHashMap<>();

    public TunnelConnectionManager() {}

    public @Nullable TunnelConnection getConnection(@NotNull UUID tunnelId) {
        return connections.get(tunnelId);
    }

    public @NotNull TunnelState getState(@NotNull UUID tunnelId) {
        TunnelConnection conn = connections.get(tunnelId);
        return conn != null ? conn.state() : TunnelState.DISCONNECTED;
    }

    /**
     * Connect a tunnel. Runs on the EDT — the blocking MINA connect
     * must be wrapped in a Task.Modal by the caller.
     */
    public void connect(
        @NotNull SshTunnel tunnel,
        @NotNull ClientSession session
    ) throws IOException {
        TunnelConnection conn = new TunnelConnection(session);
        connections.put(tunnel.id(), conn);

        try {
            SshdSocketAddress local = new SshdSocketAddress(tunnel.bindAddress(), tunnel.bindPort());
            SshdSocketAddress remote = new SshdSocketAddress(tunnel.targetHost(), tunnel.targetPort());

            SshdSocketAddress bound;
            if (tunnel.type() == TunnelType.LOCAL) {
                bound = session.startLocalPortForwarding(local, remote);
            } else {
                bound = session.startRemotePortForwarding(remote, local);
            }
            conn.markActive(bound);
            LOG.info("TermLab tunnel: activated '" + tunnel.label()
                + "' bound=" + bound + " type=" + tunnel.type());

            // Start health monitor
            startHealthMonitor(tunnel, conn, session);
        } catch (IOException e) {
            conn.markError(e.getMessage());
            conn.close();
            throw e;
        }
    }

    public void disconnect(@NotNull UUID tunnelId) {
        TunnelConnection conn = connections.remove(tunnelId);
        if (conn != null) {
            conn.close();
            conn.markDisconnected();
            LOG.info("TermLab tunnel: disconnected tunnel " + tunnelId);
        }
    }

    public void disconnectAll() {
        for (UUID id : new ArrayList<>(connections.keySet())) {
            disconnect(id);
        }
    }

    private void startHealthMonitor(
        @NotNull SshTunnel tunnel,
        @NotNull TunnelConnection conn,
        @NotNull ClientSession session
    ) {
        Thread monitor = new Thread(() -> {
            try {
                session.waitFor(
                    EnumSet.of(ClientSession.ClientSessionEvent.CLOSED),
                    0L);
            } catch (Exception ignored) {
            }
            if (conn.state() == TunnelState.ACTIVE) {
                conn.markError("Connection lost");
                connections.remove(tunnel.id());
                ApplicationManager.getApplication().invokeLater(() -> {
                    LOG.warn("TermLab tunnel: '" + tunnel.label() + "' disconnected unexpectedly");
                });
            }
        }, "TermLab-tunnel-monitor-" + tunnel.label());
        monitor.setDaemon(true);
        monitor.start();
    }
}
```

- [ ] **Step 3: Build**

```bash
cd /Users/dustin/projects/termlab_workbench && make termlab-build
```

- [ ] **Step 4: Commit**

```bash
git add plugins/tunnels/src/com/termlab/tunnels/client/TunnelConnection.java plugins/tunnels/src/com/termlab/tunnels/client/TunnelConnectionManager.java
git commit -m "feat(tunnels): phase 2.3 — TunnelConnection + TunnelConnectionManager

Per-tunnel ClientSession lifecycle, health monitoring via daemon
threads watching session.waitFor(CLOSED).

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Phase 2 gate

```bash
make termlab-build
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/tunnels:tunnels_test_runner
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/ssh:ssh_test_runner
```

---

## Phase 3 — Tool window UI

### Task 3.1: `TunnelCellRenderer`

**Files:**
- Create: `plugins/tunnels/src/com/termlab/tunnels/toolwindow/TunnelCellRenderer.java`

- [ ] **Step 1: Create the renderer**

```java
package com.termlab.tunnels.toolwindow;

import com.termlab.tunnels.client.TunnelConnectionManager;
import com.termlab.tunnels.model.SshTunnel;
import com.termlab.tunnels.model.TunnelState;
import com.termlab.tunnels.model.TunnelType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/**
 * Two-line cell renderer with status icons for tunnel entries.
 * Line 1: status icon + label.
 * Line 2: L/R prefix + bindAddress:bindPort → targetHost:targetPort.
 */
public final class TunnelCellRenderer extends JPanel implements ListCellRenderer<SshTunnel> {

    private final JLabel statusIcon = new JLabel();
    private final JLabel label = new JLabel();
    private final JLabel subtitle = new JLabel();

    public TunnelCellRenderer() {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(4, 8));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        top.setOpaque(false);
        top.add(statusIcon);
        top.add(label);

        JPanel lines = new JPanel(new GridLayout(2, 1));
        lines.setOpaque(false);
        lines.add(top);
        lines.add(subtitle);
        add(lines, BorderLayout.CENTER);

        subtitle.setFont(subtitle.getFont().deriveFont(subtitle.getFont().getSize() - 1f));
        subtitle.setForeground(UIManager.getColor("Label.disabledForeground"));
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends SshTunnel> list,
                                                  SshTunnel value, int index,
                                                  boolean isSelected, boolean cellHasFocus) {
        if (value == null) {
            label.setText("");
            subtitle.setText("");
            statusIcon.setText("");
            return this;
        }

        TunnelState state = getState(value);
        statusIcon.setText(switch (state) {
            case ACTIVE -> "●";
            case ERROR -> "⚠";
            default -> "○";
        });
        statusIcon.setForeground(switch (state) {
            case ACTIVE -> new Color(0, 180, 0);
            case ERROR -> new Color(220, 160, 0);
            default -> UIManager.getColor("Label.disabledForeground");
        });

        label.setText(value.label());

        String prefix = value.type() == TunnelType.LOCAL ? "L" : "R";
        subtitle.setText(prefix + "  " + value.bindAddress() + ":" + value.bindPort()
            + "  →  " + value.targetHost() + ":" + value.targetPort());

        Color bg = isSelected ? list.getSelectionBackground() : list.getBackground();
        Color fg = isSelected ? list.getSelectionForeground() : list.getForeground();
        setBackground(bg);
        label.setForeground(fg);
        subtitle.setForeground(isSelected ? fg : UIManager.getColor("Label.disabledForeground"));
        return this;
    }

    private static TunnelState getState(SshTunnel tunnel) {
        TunnelConnectionManager mgr = ApplicationManager.getApplication()
            .getService(TunnelConnectionManager.class);
        return mgr != null ? mgr.getState(tunnel.id()) : TunnelState.DISCONNECTED;
    }
}
```

- [ ] **Step 2: Build, commit**

```bash
make termlab-build
git add plugins/tunnels/src/com/termlab/tunnels/toolwindow/TunnelCellRenderer.java
git commit -m "feat(tunnels): phase 3.1 — TunnelCellRenderer with status icons

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 3.2: `TunnelEditDialog`

**Files:**
- Create: `plugins/tunnels/src/com/termlab/tunnels/ui/TunnelEditDialog.java`

- [ ] **Step 1: Create the dialog**

The dialog has: Label, Type radio (Local/Remote), Host combo (HostStore + ~/.ssh/config entries), Bind address, Bind port, Target host, Target port.

The host combo is populated from two sources with separators:
1. `HostStore.getHosts()` — entries from the SSH plugin
2. `SshConfigParser.parseHostAliases()` — entries from `~/.ssh/config`

Each combo entry wraps either an `InternalHost(UUID)` or `SshConfigHost(String)`.

Full implementation follows the `HostEditDialog` pattern — `DialogWrapper` with `GridBagLayout`, `doOKAction` producing an `SshTunnel`, `doValidate` checking required fields and port ranges. Static `show(Project, SshTunnel)` factory. Combo entries are instances of an inner `HostEntry` class with `toString()` for display and a `toTunnelHost()` method.

Code is ~250 lines following the exact `HostEditDialog` structure. The subagent should reference `HostEditDialog.java` for the pattern and build `TunnelEditDialog` with the fields listed in the spec: Label (text), Type (radio: Local/Remote), Host (combo), Bind address (text, default "localhost"), Bind port (spinner 1-65535), Target host (text), Target port (spinner 1-65535).

- [ ] **Step 2: Build, commit**

```bash
make termlab-build
git add plugins/tunnels/src/com/termlab/tunnels/ui/TunnelEditDialog.java
git commit -m "feat(tunnels): phase 3.2 — TunnelEditDialog

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 3.3: `TunnelsToolWindow` + `TunnelsToolWindowFactory`

**Files:**
- Create: `plugins/tunnels/src/com/termlab/tunnels/toolwindow/TunnelsToolWindow.java`
- Create: `plugins/tunnels/src/com/termlab/tunnels/toolwindow/TunnelsToolWindowFactory.java`

- [ ] **Step 1: Create `TunnelsToolWindowFactory.java`**

```java
package com.termlab.tunnels.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class TunnelsToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        TunnelsToolWindow panel = new TunnelsToolWindow(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
```

- [ ] **Step 2: Create `TunnelsToolWindow.java`**

Mirrors `HostsToolWindow` exactly — `JPanel(BorderLayout)` with:
- Toolbar: Add, Edit, Delete, separator, Refresh actions
- JBList with `TunnelCellRenderer`
- Double-click toggles connect/disconnect
- Right-click context menu: Connect, Disconnect, Edit, Duplicate, Delete
- Connect action resolves credentials, runs `Task.Modal` for the MINA connect, calls `TunnelConnectionManager.connect()`, refreshes the list
- Disconnect calls `TunnelConnectionManager.disconnect()`

The connect flow in `TunnelsToolWindow`:
1. Resolve `TunnelHost` → get an `SshHost` (from HostStore) or build a synthetic one (from config alias)
2. Resolve credentials (same dispatch as `SshSessionProvider.authSourceFor`)
3. Run `Task.Modal` → call `TermLabSshClient.connectSession(host, credential, new TermLabServerKeyVerifier())`
4. On success → `TunnelConnectionManager.connect(tunnel, session)` which starts forwarding
5. Refresh list to show status icon change
6. On error → show error dialog, refresh list

Code is ~300 lines following `HostsToolWindow` structure. Subagent should reference `HostsToolWindow.java` directly.

- [ ] **Step 3: Build, commit**

```bash
make termlab-build
git add plugins/tunnels/src/com/termlab/tunnels/toolwindow/
git commit -m "feat(tunnels): phase 3.3 — TunnelsToolWindow + Factory

Right-sidebar tool window with connect/disconnect, edit, duplicate,
delete. Connect flow: credential resolution → Task.Modal →
connectSession → startLocalPortForwarding/startRemotePortForwarding.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 3.4: Plugin XML wiring

**Files:**
- Modify: `plugins/tunnels/resources/META-INF/plugin.xml`

- [ ] **Step 1: Update `plugin.xml`**

Replace the placeholder with full registrations:

```xml
<idea-plugin>
    <id>com.termlab.tunnels</id>
    <name>TermLab Tunnels</name>
    <vendor>TermLab</vendor>
    <description>SSH tunnel manager. Creates local and remote port
    forwarding tunnels through hosts managed by the TermLab SSH plugin
    or defined in ~/.ssh/config.</description>

    <depends>com.termlab.core</depends>
    <depends>com.termlab.ssh</depends>
    <depends>com.termlab.vault</depends>

    <extensions defaultExtensionNs="com.intellij">
        <applicationService
            serviceImplementation="com.termlab.tunnels.model.TunnelStore"/>

        <applicationService
            serviceImplementation="com.termlab.tunnels.client.TunnelConnectionManager"/>

        <toolWindow id="Tunnels"
                    anchor="right"
                    icon="AllIcons.Nodes.SecurityRole"
                    factoryClass="com.termlab.tunnels.toolwindow.TunnelsToolWindowFactory"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 2: Build**

```bash
make termlab-build
```

- [ ] **Step 3: Commit**

```bash
git add plugins/tunnels/resources/META-INF/plugin.xml
git commit -m "feat(tunnels): phase 3.4 — plugin.xml with services + tool window

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Phase 3 gate

```bash
make termlab-build
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/tunnels:tunnels_test_runner
```

---

## Phase 4 — Search Everywhere + product registration

### Task 4.1: `TunnelsSearchEverywhereContributor`

**Files:**
- Create: `plugins/tunnels/src/com/termlab/tunnels/palette/TunnelsSearchEverywhereContributor.java`
- Modify: `plugins/tunnels/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create the contributor**

Same pattern as `HostsSearchEverywhereContributor`. Tab name "Tunnels", weight 45, `getSearchProviderId()` → `"TermLabTunnels"`. `fetchElements` reads `TunnelStore`, filters by label/targetHost. `processSelectedItem` toggles connect/disconnect based on current state.

- [ ] **Step 2: Register in plugin.xml**

Add inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<searchEverywhereContributor
    implementation="com.termlab.tunnels.palette.TunnelsSearchEverywhereContributor$Factory"/>
```

- [ ] **Step 3: Build, commit**

---

### Task 4.2: Product registration

**Files:**
- Modify: `customization/resources/idea/TermLabApplicationInfo.xml`
- Modify: `build/src/org/jetbrains/intellij/build/termlab/TermLabProperties.kt`
- Modify: `core/src/com/termlab/core/palette/TermLabTabsCustomizationStrategy.java`

- [ ] **Step 1: Essential plugin**

Add to `TermLabApplicationInfo.xml`:
```xml
<essential-plugin>com.termlab.tunnels</essential-plugin>
```

- [ ] **Step 2: Bundled module**

Add to `TermLabProperties.kt` `bundledPluginModules`:
```kotlin
"intellij.termlab.tunnels",
```

- [ ] **Step 3: Tab allowlist**

Add `"TermLabTunnels"` to `TermLabTabsCustomizationStrategy.ALLOWED_TAB_IDS`.

- [ ] **Step 4: Build, commit**

```bash
make termlab-build
git add customization/ build/ core/ plugins/tunnels/
git commit -m "feat(tunnels): phase 4 — SE contributor + product registration

Tunnels tab in Search Everywhere, essential plugin pinning, bundled
module declaration, tab allowlist.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Phase 5 — Smoke test gate

- [ ] **Step 1: Full build**

```bash
make termlab-build
```

- [ ] **Step 2: All test suites**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/tunnels:tunnels_test_runner
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/ssh:ssh_test_runner
```

- [ ] **Step 3: Manual — tool window appears**

`make termlab`. Tunnels tool window should appear in the right sidebar below Hosts.

- [ ] **Step 4: Manual — add a local tunnel**

Add a tunnel: label "test-local", type Local, host = a reachable host from HostStore, bind port 13307, target host "localhost", target port 22 (or whatever's running on the remote). Save.

- [ ] **Step 5: Manual — connect**

Double-click the tunnel. Credential prompt (if needed) → progress dialog → status icon goes green. Verify: `nc -z localhost 13307` should succeed (port is listening).

- [ ] **Step 6: Manual — disconnect**

Double-click again → status icon goes gray. Port is no longer listening.

- [ ] **Step 7: Manual — Search Everywhere**

Cmd+Shift+P → Tunnels tab → tunnel appears → select → toggles connect/disconnect.

- [ ] **Step 8: Manual — error handling**

Add a tunnel pointing at a non-existent host. Double-click → should see error notification, status icon goes to ⚠.

- [ ] **Step 9: Manual — `~/.ssh/config` host**

Add a tunnel using a host from ~/.ssh/config (if you have entries). The combo should show config aliases under a separator. Connecting should work if the config entry resolves.

---

## Self-review checklist

**1. Spec coverage:**
- SshTunnel record with all fields → Task 1.2 ✓
- TunnelType LOCAL/REMOTE → Task 1.2 ✓
- TunnelHost sealed (InternalHost + SshConfigHost) → Task 1.2 ✓
- TunnelState enum → Task 1.2 ✓
- Persistence to ~/.config/termlab/tunnels.json → Task 1.3 ✓
- TunnelStore application service → Task 1.3 ✓
- Gson adapter with discriminator → Task 1.3 ✓
- TermLabSshClient.connectSession() → Task 2.1 ✓
- SshConfigParser host alias reader → Task 2.2 ✓
- TunnelConnection + TunnelConnectionManager → Task 2.3 ✓
- Health monitoring via daemon thread → Task 2.3 ✓
- TunnelCellRenderer with status icons → Task 3.1 ✓
- TunnelEditDialog with host combo (HostStore + config) → Task 3.2 ✓
- TunnelsToolWindow + Factory → Task 3.3 ✓
- Plugin.xml with services + tool window → Task 3.4 ✓
- TunnelsSearchEverywhereContributor → Task 4.1 ✓
- Essential plugin + bundled module + tab allowlist → Task 4.2 ✓
- Manual smoke test → Phase 5 ✓

No gaps.

**2. Type consistency:**
- `SshTunnel.create(label, type, host, bindPort, bindAddress, targetHost, targetPort)` — 7 params, used in all tests and dialog
- `TunnelStore` API: `getTunnels()`, `addTunnel()`, `removeTunnel()`, `updateTunnel()`, `save()`, `reload()` — consistent
- `TunnelConnectionManager.connect(SshTunnel, ClientSession)` — takes tunnel + pre-authenticated session
- `TunnelConnectionManager.getState(UUID)` — returns TunnelState, used by renderer and SE contributor
- `TermLabSshClient.connectSession(SshHost, SshResolvedCredential, ServerKeyVerifier)` → `ClientSession` — new method, distinct from `connect()` which returns `SshConnection`

**3. Placeholder scan:**
Tasks 3.2 and 3.3 describe structure rather than complete code due to the 250-300 line size of each file. They reference `HostEditDialog.java` and `HostsToolWindow.java` as patterns and specify exact fields/methods. The subagent implementer should read those reference files and build the tunnel equivalents. All other tasks have complete code blocks.
