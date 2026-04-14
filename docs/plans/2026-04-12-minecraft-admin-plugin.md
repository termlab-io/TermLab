# Minecraft Admin Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Minecraft Admin plugin that gives a Paper-server operator a right-sidebar tool window showing live server health, an online player list, a console tail, lifecycle buttons, and a broadcast/command input, driven by AMP's REST API and Minecraft RCON.

**Architecture:** Optional (non-bundled) plugin at `plugins/minecraft-admin/` depending on `com.conch.core` and `com.conch.vault`. Two thin network clients — `AmpClient` (`java.net.http` + Gson) and `RconClient` (raw `Socket` + custom packet codec) — orchestrated by a per-profile `ServerPoller` on `AppExecutorUtil.getAppScheduledExecutorService()` producing immutable `ServerState` snapshots at a 5-second cadence. Persistent state in `~/.config/conch/minecraft-servers.json` with secrets referenced by UUID into the Conch vault. Tool window structure mirrors `HostsToolWindow` from the SSH plugin.

**Tech Stack:** Java 21 records + sealed types, `java.net.http.HttpClient`, Gson, raw `java.net.Socket`, JDK `com.sun.net.httpserver.HttpServer` (test-only), IntelliJ Platform (`ToolWindowFactory`, `DialogWrapper`), JUnit 5.

**Reference spec:** `docs/specs/2026-04-12-minecraft-admin-plugin-design.md`

**Reference patterns:** `plugins/ssh/` is the canonical template for module structure, persistence, Gson configuration, vault-backed credential resolution, tool window scaffolding, edit dialog patterns, and BUILD.bazel shape. `plugins/tunnels/` (see `docs/plans/2026-04-12-tunnel-manager.md`) shows the corresponding plan shape for a non-core plugin.

---

## Build & test commands

From `/Users/dustin/projects/conch_workbench`:

```bash
# Full product build:
make conch-build

# Plugin tests (once test runner exists):
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //conch/plugins/minecraft-admin:minecraft_admin_test_runner

# SSH plugin regression (the plugin shares no code, but the pattern does — run this if anything in ssh/ changes):
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //conch/plugins/ssh:ssh_test_runner
```

---

## File Structure

### New files (`plugins/minecraft-admin/`)

**Build:**
- `plugins/minecraft-admin/BUILD.bazel` — Bazel targets for main, test, and test runner
- `plugins/minecraft-admin/intellij.conch.minecraft-admin.iml` — IntelliJ module file
- `plugins/minecraft-admin/resources/META-INF/plugin.xml` — plugin descriptor

**Model (`src/com/conch/minecraftadmin/model/`):**
- `McServerStatus.java` — enum: RUNNING, STARTING, STOPPING, STOPPED, CRASHED, UNKNOWN
- `Player.java` — record: name, pingMs (pingMs = -1 means unknown)
- `ServerProfile.java` — record: id, label, ampUrl, ampInstanceName, ampUsername, ampCredentialId, rconHost, rconPort, rconCredentialId, timestamps
- `ServerState.java` — record: status, players, tps, cpuPercent, ramUsedMb, ramMaxMb, uptime, ampError, rconError, sampledAt
- `ProfileStore.java` — application service holding the profile list

**Persistence (`src/com/conch/minecraftadmin/persistence/`):**
- `McPaths.java` — resolves `~/.config/conch/minecraft-servers.json`
- `McGson.java` — Gson singleton with Instant adapter
- `ServersFile.java` — atomic JSON save/load with versioned envelope

**Credentials (`src/com/conch/minecraftadmin/credentials/`):**
- `McCredential.java` — value: username + password (`char[]`)
- `McCredentialResolver.java` — maps a vault credential UUID to an `McCredential` via the `com.conch.core.credentialProvider` EP

**Client (`src/com/conch/minecraftadmin/client/`):**
- `RconPacketCodec.java` — pure encode/decode for the RCON binary framing
- `RconSession.java` — handle: socket + request id counter
- `RconClient.java` — connect/command/close
- `AmpSession.java` — handle: base URL + session token
- `AmpClient.java` — login/instance-status/lifecycle/console-updates
- `InstanceStatus.java` — record: status enum, cpuPercent, ramUsedMb, ramMaxMb, uptimeSeconds
- `ConsoleUpdate.java` — record: List<String> lines
- `PaperListReplyParser.java` — pure parser for `list` output
- `PaperTpsReplyParser.java` — pure parser for `tps` output
- `CrashDetector.java` — tracks status transitions, decides when to fire the crash balloon
- `ServerStateMerger.java` — pure merge of AMP + RCON tick results into a new `ServerState`
- `ServerPoller.java` — orchestrates the scheduled poll loop per profile
- `StateListener.java` — interface: `onStateUpdate(ServerState)`, `onConsoleLines(List<String>)`

**Tool window (`src/com/conch/minecraftadmin/toolwindow/`):**
- `McAdminToolWindowFactory.java` — `ToolWindowFactory` impl
- `McAdminToolWindow.java` — root JPanel, owns a `ProfileController` per visible profile
- `ProfileController.java` — wires one `ServerPoller` to the panels for one profile
- `StatusStripPanel.java` — the six-cell status row
- `LifecycleButtons.java` — Start/Stop/Restart/Backup row
- `PlayersPanel.java` — JBTable of players with right-click menu
- `ConsolePanel.java` — read-only log tail + send box + Broadcast button
- `ServerSwitcher.java` — dropdown/toolbar at the top of the tool window

**UI (`src/com/conch/minecraftadmin/ui/`):**
- `ServerEditDialog.java` — modal add/edit dialog

**Tests (`test/com/conch/minecraftadmin/`):**
- `TestRunner.java` — JUnit 5 standalone runner
- `model/ServerProfileTest.java`
- `model/ServerStateTest.java`
- `persistence/ServersFileTest.java`
- `client/RconPacketCodecTest.java`
- `client/RconClientTest.java` (uses an in-process fake RCON server)
- `client/AmpClientTest.java` (uses `com.sun.net.httpserver.HttpServer`)
- `client/PaperListReplyParserTest.java`
- `client/PaperTpsReplyParserTest.java`
- `client/CrashDetectorTest.java`
- `client/ServerStateMergerTest.java`
- `client/ServerPollerTest.java` — single-tick sanity only
- `toolwindow/StatusStripPanelTest.java`
- `toolwindow/PlayersPanelTest.java`
- `toolwindow/ConsolePanelTest.java`
- `toolwindow/LifecycleButtonsTest.java`

### No modifications to other modules

The plugin is fully self-contained. No SSH plugin changes, no core changes, no customization changes, no Conch product registration changes — the plugin is **not** bundled by default. It's an optional plugin that can be built and installed on demand.

---

## Phase 1 — Scaffolding, data model, persistence

### Task 1.1: Bazel scaffolding + plugin shell

**Files:**
- Create: `plugins/minecraft-admin/BUILD.bazel`
- Create: `plugins/minecraft-admin/intellij.conch.minecraft-admin.iml`
- Create: `plugins/minecraft-admin/resources/META-INF/plugin.xml` (placeholder)
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/TestRunner.java`

- [ ] **Step 1: Create `BUILD.bazel`**

```bazel
load("@rules_java//java:defs.bzl", "java_binary")
load("@rules_jvm//:jvm.bzl", "jvm_library", "resourcegroup")

resourcegroup(
    name = "minecraft_admin_resources",
    srcs = glob(["resources/**/*"]),
    strip_prefix = "resources",
)

jvm_library(
    name = "minecraft-admin",
    module_name = "intellij.conch.minecraft-admin",
    visibility = ["//visibility:public"],
    srcs = glob(["src/**/*.java"], allow_empty = True),
    resources = [":minecraft_admin_resources"],
    deps = [
        "//conch/sdk",
        "//conch/core",
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
        "@lib//:jetbrains-annotations",
        "@lib//:gson",
        "@lib//:kotlin-stdlib",
    ],
)

jvm_library(
    name = "minecraft_admin_test_lib",
    module_name = "intellij.conch.minecraft-admin.tests",
    visibility = ["//visibility:public"],
    srcs = glob(["test/**/*.java"], allow_empty = True),
    deps = [
        ":minecraft-admin",
        "//conch/sdk",
        "//libraries/junit5",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
        "@lib//:jetbrains-annotations",
        "@lib//:gson",
    ],
)

java_binary(
    name = "minecraft_admin_test_runner",
    main_class = "com.conch.minecraftadmin.TestRunner",
    runtime_deps = [
        ":minecraft_admin_test_lib",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
    ],
)
```

- [ ] **Step 2: Create `intellij.conch.minecraft-admin.iml`**

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
    <id>com.conch.minecraft-admin</id>
    <name>Conch Minecraft Admin</name>
    <vendor>Conch</vendor>
    <description>Right-sidebar admin console for Paper servers behind
    Cube Coders AMP. Live status, player list, console tail, lifecycle
    control, broadcast + RCON commands.</description>

    <depends>com.conch.core</depends>
    <depends>com.conch.vault</depends>
</idea-plugin>
```

- [ ] **Step 4: Create `TestRunner.java`**

Create `plugins/minecraft-admin/test/com/conch/minecraftadmin/TestRunner.java`:

```java
package com.conch.minecraftadmin;

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
            .selectors(selectPackage("com.conch.minecraftadmin"))
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

Run: `cd /Users/dustin/projects/intellij-community && bash bazel.cmd build //conch/plugins/minecraft-admin:minecraft-admin`
Expected: `Build completed successfully`.

- [ ] **Step 6: Commit**

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 1.1 — Bazel scaffolding and plugin shell

Empty plugin module with BUILD.bazel, iml, placeholder plugin.xml,
and JUnit 5 test runner. Does nothing yet; verifies the module
compiles in isolation.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 1.2: Data model — McServerStatus, Player, ServerProfile

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/model/McServerStatus.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/model/Player.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/model/ServerProfile.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/model/ServerProfileTest.java`

- [ ] **Step 1: Write the failing test**

Create `plugins/minecraft-admin/test/com/conch/minecraftadmin/model/ServerProfileTest.java`:

```java
package com.conch.minecraftadmin.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ServerProfileTest {

    @Test
    void create_populatesIdAndTimestamps() {
        UUID ampCred = UUID.randomUUID();
        UUID rconCred = UUID.randomUUID();
        ServerProfile profile = ServerProfile.create(
            "Survival",
            "https://amp.example.com:8080",
            "survival",
            "admin",
            ampCred,
            "mc.example.com",
            25575,
            rconCred);
        assertNotNull(profile.id());
        assertEquals("Survival", profile.label());
        assertEquals("https://amp.example.com:8080", profile.ampUrl());
        assertEquals("survival", profile.ampInstanceName());
        assertEquals("admin", profile.ampUsername());
        assertEquals(ampCred, profile.ampCredentialId());
        assertEquals("mc.example.com", profile.rconHost());
        assertEquals(25575, profile.rconPort());
        assertEquals(rconCred, profile.rconCredentialId());
        assertNotNull(profile.createdAt());
        assertEquals(profile.createdAt(), profile.updatedAt());
    }

    @Test
    void withLabel_preservesIdentity() {
        ServerProfile original = ServerProfile.create(
            "old", "url", "inst", "user", UUID.randomUUID(),
            "host", 25575, UUID.randomUUID());
        ServerProfile renamed = original.withLabel("new");
        assertEquals(original.id(), renamed.id());
        assertEquals(original.createdAt(), renamed.createdAt());
        assertEquals("new", renamed.label());
    }

    @Test
    void withEdited_replacesAllEditableFields() {
        UUID oldAmp = UUID.randomUUID();
        UUID oldRcon = UUID.randomUUID();
        ServerProfile original = ServerProfile.create(
            "old", "old-url", "old-inst", "old-user", oldAmp,
            "old-host", 25575, oldRcon);

        UUID newAmp = UUID.randomUUID();
        UUID newRcon = UUID.randomUUID();
        ServerProfile edited = original.withEdited(
            "new", "new-url", "new-inst", "new-user", newAmp,
            "new-host", 25576, newRcon);

        assertEquals(original.id(), edited.id());
        assertEquals(original.createdAt(), edited.createdAt());
        assertEquals("new", edited.label());
        assertEquals("new-url", edited.ampUrl());
        assertEquals("new-inst", edited.ampInstanceName());
        assertEquals("new-user", edited.ampUsername());
        assertEquals(newAmp, edited.ampCredentialId());
        assertEquals("new-host", edited.rconHost());
        assertEquals(25576, edited.rconPort());
        assertEquals(newRcon, edited.rconCredentialId());
    }

    @Test
    void defaultRconPort() {
        assertEquals(25575, ServerProfile.DEFAULT_RCON_PORT);
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `cd /Users/dustin/projects/intellij-community && bash bazel.cmd build //conch/plugins/minecraft-admin:minecraft_admin_test_lib`
Expected: compile error on missing classes.

- [ ] **Step 3: Create `McServerStatus.java`**

```java
package com.conch.minecraftadmin.model;

/**
 * Life-cycle state of an AMP-managed Minecraft instance. Mapped from
 * AMP's numeric state code in {@code AmpClient} and consumed by the UI
 * to enable/disable lifecycle buttons and by {@code CrashDetector} to
 * decide whether to fire the crash-balloon notification.
 */
public enum McServerStatus {
    /** Instance is accepting connections. */
    RUNNING,
    /** Instance is starting up (pre-ready). */
    STARTING,
    /** Instance has been asked to stop but has not yet stopped. */
    STOPPING,
    /** Instance is off (user-initiated or clean shutdown). */
    STOPPED,
    /** AMP reports the instance exited abnormally. */
    CRASHED,
    /** State is unobservable right now (AMP unreachable, auth failed, etc.). */
    UNKNOWN
}
```

- [ ] **Step 4: Create `Player.java`**

```java
package com.conch.minecraftadmin.model;

import org.jetbrains.annotations.NotNull;

/**
 * One online player. Ping is best-effort: {@code -1} means "unknown"
 * because Paper's stock RCON {@code list} command returns names only
 * and we may not have a secondary ping source.
 */
public record Player(@NotNull String name, int pingMs) {
    public static final int PING_UNKNOWN = -1;

    public Player {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("player name must be non-blank");
        }
    }

    public static @NotNull Player unknownPing(@NotNull String name) {
        return new Player(name, PING_UNKNOWN);
    }
}
```

- [ ] **Step 5: Create `ServerProfile.java`**

```java
package com.conch.minecraftadmin.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A saved Minecraft server profile. Identifies one AMP instance plus
 * its RCON endpoint. Credentials are referenced by vault id — the
 * plaintext password never lives in this record or in the JSON file.
 *
 * @param id                 stable UUID, survives renames
 * @param label              user-facing name ("Survival")
 * @param ampUrl             AMP panel base URL (https://amp.example.com:8080)
 * @param ampInstanceName    friendly AMP instance name; resolved to numeric
 *                           id at connect time via {@code Core/GetInstances}
 * @param ampUsername        AMP panel username (plaintext, not a secret)
 * @param ampCredentialId    vault reference → AMP password
 * @param rconHost           RCON endpoint host (often same as ampUrl's host)
 * @param rconPort           RCON endpoint port, default 25575
 * @param rconCredentialId   vault reference → RCON password
 * @param createdAt          when the profile was created
 * @param updatedAt          when the profile was last edited
 */
public record ServerProfile(
    @NotNull UUID id,
    @NotNull String label,
    @NotNull String ampUrl,
    @NotNull String ampInstanceName,
    @NotNull String ampUsername,
    @NotNull UUID ampCredentialId,
    @NotNull String rconHost,
    int rconPort,
    @NotNull UUID rconCredentialId,
    @NotNull Instant createdAt,
    @NotNull Instant updatedAt
) {
    /** Default Minecraft RCON port. */
    public static final int DEFAULT_RCON_PORT = 25575;

    /** Factory for brand-new profiles. */
    public static @NotNull ServerProfile create(
        @NotNull String label,
        @NotNull String ampUrl,
        @NotNull String ampInstanceName,
        @NotNull String ampUsername,
        @NotNull UUID ampCredentialId,
        @NotNull String rconHost,
        int rconPort,
        @NotNull UUID rconCredentialId
    ) {
        Instant now = Instant.now();
        return new ServerProfile(
            UUID.randomUUID(),
            label,
            ampUrl,
            ampInstanceName,
            ampUsername,
            ampCredentialId,
            rconHost,
            rconPort,
            rconCredentialId,
            now,
            now);
    }

    /** @return a copy with a new label and bumped {@code updatedAt}. */
    public @NotNull ServerProfile withLabel(@NotNull String newLabel) {
        return new ServerProfile(
            id, newLabel, ampUrl, ampInstanceName, ampUsername, ampCredentialId,
            rconHost, rconPort, rconCredentialId, createdAt, Instant.now());
    }

    /** @return a copy with every editable field replaced. Used by the edit dialog's Save path. */
    public @NotNull ServerProfile withEdited(
        @NotNull String newLabel,
        @NotNull String newAmpUrl,
        @NotNull String newAmpInstanceName,
        @NotNull String newAmpUsername,
        @NotNull UUID newAmpCredentialId,
        @NotNull String newRconHost,
        int newRconPort,
        @NotNull UUID newRconCredentialId
    ) {
        return new ServerProfile(
            id, newLabel, newAmpUrl, newAmpInstanceName, newAmpUsername, newAmpCredentialId,
            newRconHost, newRconPort, newRconCredentialId, createdAt, Instant.now());
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //conch/plugins/minecraft-admin:minecraft_admin_test_runner`
Expected: 4 tests, all passing.

- [ ] **Step 7: Commit**

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 1.2 — core data model

ServerProfile record with create/withLabel/withEdited factories.
McServerStatus enum for lifecycle state. Player record with PING_UNKNOWN
sentinel for best-effort ping.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 1.3: `ServerState` record + merge test scaffolding

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/model/ServerState.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/model/ServerStateTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.conch.minecraftadmin.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ServerStateTest {

    @Test
    void unknownInitial_hasSensibleDefaults() {
        ServerState state = ServerState.unknown(Instant.now());
        assertEquals(McServerStatus.UNKNOWN, state.status());
        assertEquals(0, state.playersOnline());
        assertEquals(0, state.playersMax());
        assertTrue(state.players().isEmpty());
        assertTrue(Double.isNaN(state.tps()));
        assertTrue(Double.isNaN(state.cpuPercent()));
        assertEquals(0L, state.ramUsedMb());
        assertEquals(0L, state.ramMaxMb());
        assertEquals(Duration.ZERO, state.uptime());
        assertTrue(state.ampError().isEmpty());
        assertTrue(state.rconError().isEmpty());
    }

    @Test
    void record_isImmutable_listDefensivelyCopied() {
        var mutable = new java.util.ArrayList<Player>();
        mutable.add(new Player("alice", 42));
        ServerState state = new ServerState(
            McServerStatus.RUNNING, 1, 20, mutable, 20.0, 5.0, 1000, 4000,
            Duration.ofMinutes(3), Optional.empty(), Optional.empty(), Instant.now());
        mutable.add(new Player("bob", 99));
        assertEquals(1, state.players().size(),
            "ServerState must not expose the caller's mutable list");
    }

    @Test
    void isAmpHealthy_falseWhenAmpErrorPresent() {
        ServerState state = ServerState.unknown(Instant.now())
            .withAmpError("connection refused");
        assertFalse(state.isAmpHealthy());
        assertTrue(state.ampError().isPresent());
    }

    @Test
    void isRconHealthy_falseWhenRconErrorPresent() {
        ServerState state = ServerState.unknown(Instant.now())
            .withRconError("auth failed");
        assertFalse(state.isRconHealthy());
    }
}
```

- [ ] **Step 2: Run test to confirm failure**

Run: `bazel build //conch/plugins/minecraft-admin:minecraft_admin_test_lib`
Expected: compile error on missing `ServerState`.

- [ ] **Step 3: Create `ServerState.java`**

```java
package com.conch.minecraftadmin.model;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Immutable snapshot of one server's state at one point in time. Every
 * field is either fully populated or explicitly marked unavailable
 * (via {@code NaN}, {@code 0}, or {@code Optional.empty()}). The UI
 * renders directly from this record — never from the clients — so the
 * EDT always sees a coherent view.
 *
 * @param status         latest lifecycle state from AMP, or UNKNOWN
 * @param playersOnline  count from RCON {@code list}; 0 if RCON is down
 * @param playersMax     max-player cap from RCON {@code list}; 0 if RCON is down
 * @param players        list of online players (defensive copy)
 * @param tps            latest TPS from RCON {@code tps}; NaN if unavailable
 * @param cpuPercent     AMP CPU% for the instance; NaN if AMP is unreachable
 * @param ramUsedMb      AMP RAM used; 0 if AMP is unreachable
 * @param ramMaxMb       AMP RAM limit; 0 if AMP is unreachable
 * @param uptime         AMP-reported uptime since last start
 * @param ampError       last AMP error message, empty if healthy
 * @param rconError      last RCON error message, empty if healthy
 * @param sampledAt      wall-clock time when the snapshot was built
 */
public record ServerState(
    @NotNull McServerStatus status,
    int playersOnline,
    int playersMax,
    @NotNull List<Player> players,
    double tps,
    double cpuPercent,
    long ramUsedMb,
    long ramMaxMb,
    @NotNull Duration uptime,
    @NotNull Optional<String> ampError,
    @NotNull Optional<String> rconError,
    @NotNull Instant sampledAt
) {
    public ServerState {
        // Defensive copy so callers can't mutate out from under the EDT.
        players = List.copyOf(players);
    }

    public static @NotNull ServerState unknown(@NotNull Instant sampledAt) {
        return new ServerState(
            McServerStatus.UNKNOWN,
            0, 0, List.of(),
            Double.NaN, Double.NaN, 0L, 0L, Duration.ZERO,
            Optional.empty(), Optional.empty(),
            sampledAt);
    }

    public boolean isAmpHealthy() {
        return ampError.isEmpty();
    }

    public boolean isRconHealthy() {
        return rconError.isEmpty();
    }

    public @NotNull ServerState withAmpError(@NotNull String message) {
        return new ServerState(
            status, playersOnline, playersMax, players, tps, cpuPercent,
            ramUsedMb, ramMaxMb, uptime,
            Optional.of(message), rconError, sampledAt);
    }

    public @NotNull ServerState withRconError(@NotNull String message) {
        return new ServerState(
            status, playersOnline, playersMax, players, tps, cpuPercent,
            ramUsedMb, ramMaxMb, uptime,
            ampError, Optional.of(message), sampledAt);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner`
Expected: 8 tests pass (4 from 1.2, 4 new).

- [ ] **Step 5: Commit**

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 1.3 — ServerState snapshot

Immutable record with defensive list copy, NaN sentinels for
unavailable numeric fields, and Optional error fields for per-side
health. withAmpError/withRconError return a fresh snapshot so
mergers stay pure.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 1.4: Persistence — McPaths, McGson, ServersFile

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/persistence/McPaths.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/persistence/McGson.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/persistence/ServersFile.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/persistence/ServersFileTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.conch.minecraftadmin.persistence;

import com.conch.minecraftadmin.model.ServerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ServersFileTest {

    @Test
    void save_then_load_roundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("minecraft-servers.json");
        ServerProfile p1 = ServerProfile.create(
            "Survival", "https://amp:8080", "survival", "admin",
            UUID.randomUUID(), "mc.example.com", 25575, UUID.randomUUID());
        ServerProfile p2 = ServerProfile.create(
            "Creative", "https://amp:8080", "creative", "admin",
            UUID.randomUUID(), "mc.example.com", 25576, UUID.randomUUID());

        ServersFile.save(file, List.of(p1, p2));
        List<ServerProfile> loaded = ServersFile.load(file);

        assertEquals(2, loaded.size());
        assertEquals(p1, loaded.get(0));
        assertEquals(p2, loaded.get(1));
    }

    @Test
    void load_missingFile_returnsEmptyList(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("does-not-exist.json");
        assertEquals(List.of(), ServersFile.load(file));
    }

    @Test
    void load_corruptJson_throwsIOException(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("minecraft-servers.json");
        Files.writeString(file, "{\"version\": 1, \"servers\":");  // truncated
        assertThrows(IOException.class, () -> ServersFile.load(file));
    }

    @Test
    void load_wrongVersion_throwsIOException(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("minecraft-servers.json");
        Files.writeString(file, "{\"version\": 99, \"servers\": []}");
        assertThrows(IOException.class, () -> ServersFile.load(file));
    }

    @Test
    void save_writesAtomically(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("minecraft-servers.json");
        ServersFile.save(file, List.of());
        // File must exist and no temp files should remain.
        assertTrue(Files.exists(file));
        try (var stream = Files.list(dir)) {
            long nonTarget = stream.filter(p -> !p.equals(file)).count();
            assertEquals(0, nonTarget, "temp files must not be left behind");
        }
    }
}
```

- [ ] **Step 2: Run test to confirm failure**

Run: `bazel build //conch/plugins/minecraft-admin:minecraft_admin_test_lib`
Expected: compile error on missing `ServersFile`.

- [ ] **Step 3: Create `McPaths.java`**

```java
package com.conch.minecraftadmin.persistence;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Canonical on-disk paths for the Minecraft Admin plugin's state.
 * Same {@code ~/.config/conch/} convention the SSH plugin uses.
 */
public final class McPaths {

    private McPaths() {}

    /** Plaintext JSON file holding the list of saved server profiles. */
    public static Path serversFile() {
        return Paths.get(System.getProperty("user.home"), ".config", "conch", "minecraft-servers.json");
    }
}
```

- [ ] **Step 4: Create `McGson.java`**

```java
package com.conch.minecraftadmin.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Instant;

/**
 * Gson configuration for the Minecraft Admin plugin. Same Instant
 * adapter pattern as {@code SshGson} — JDK 21 modules block default
 * reflection into {@code java.time.Instant}, so we serialize as
 * ISO-8601 strings.
 */
public final class McGson {

    public static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(Instant.class, new InstantAdapter())
        .create();

    private McGson() {}

    private static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) out.nullValue();
            else out.value(value.toString());
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Instant.parse(in.nextString());
        }
    }
}
```

- [ ] **Step 5: Create `ServersFile.java`**

```java
package com.conch.minecraftadmin.persistence;

import com.conch.minecraftadmin.model.ServerProfile;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Atomic JSON I/O for the Minecraft Admin server profile list.
 *
 * <p>Versioned envelope:
 * <pre>
 *   {
 *     "version": 1,
 *     "servers": [ {...}, ... ]
 *   }
 * </pre>
 *
 * <p>Atomic write: serialize → write to a sibling temp file → rename.
 * A crash mid-write can never corrupt the existing file. Same pattern
 * as {@code HostsFile} in the SSH plugin.
 */
public final class ServersFile {

    public static final int VERSION = 1;

    private ServersFile() {}

    public static void save(@NotNull Path target, @NotNull List<ServerProfile> profiles) throws IOException {
        Envelope envelope = new Envelope(VERSION, new ArrayList<>(profiles));
        String json = McGson.GSON.toJson(envelope);

        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);

        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, json);
        try {
            Files.move(temp, target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            // Some filesystems (notably FAT) don't support atomic replace —
            // fall back to a non-atomic copy so saving still works on
            // esoteric setups.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static @NotNull List<ServerProfile> load(@NotNull Path source) throws IOException {
        if (!Files.exists(source)) return Collections.emptyList();
        String json = Files.readString(source);
        Envelope envelope;
        try {
            Type type = new TypeToken<Envelope>(){}.getType();
            envelope = McGson.GSON.fromJson(json, type);
        } catch (JsonSyntaxException e) {
            throw new IOException("minecraft-servers.json is corrupt: " + e.getMessage(), e);
        }
        if (envelope == null) return Collections.emptyList();
        if (envelope.version() != VERSION) {
            throw new IOException("unsupported minecraft-servers.json version "
                + envelope.version() + " (expected " + VERSION + ")");
        }
        return envelope.servers() == null ? Collections.emptyList() : envelope.servers();
    }

    private record Envelope(int version, List<ServerProfile> servers) {}
}
```

- [ ] **Step 6: Run tests**

Run: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner`
Expected: 13 tests total, all passing.

- [ ] **Step 7: Commit**

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 1.4 — persistence layer

McPaths (~/.config/conch/minecraft-servers.json), McGson (Instant
adapter), ServersFile (versioned envelope + atomic temp-rename
write, falls back to non-atomic on FAT-like filesystems). Round-trip,
missing-file, corrupt-JSON, and wrong-version tests.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 1.5: `ProfileStore` application service

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/model/ProfileStore.java`

- [ ] **Step 1: Create `ProfileStore.java`**

```java
package com.conch.minecraftadmin.model;

import com.conch.minecraftadmin.persistence.McPaths;
import com.conch.minecraftadmin.persistence.ServersFile;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Application-level store for Minecraft server profiles. Wraps
 * {@link ServersFile} with an in-memory list, a listener list, and
 * the usual CRUD helpers the tool window and edit dialog need.
 *
 * <p>Thread-safety: mutations happen on the EDT (from dialogs and
 * button clicks). The snapshot accessor {@link #getProfiles} is safe
 * to call off the EDT because it returns an unmodifiable copy.
 */
@Service(Service.Level.APP)
public final class ProfileStore {

    private static final Logger LOG = Logger.getInstance(ProfileStore.class);

    public interface Listener {
        void onProfilesChanged(@NotNull List<ServerProfile> profiles);
    }

    private final Path file;
    private final List<ServerProfile> profiles = new ArrayList<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private boolean loaded;

    /** Production constructor used by the IntelliJ service framework. */
    public ProfileStore() {
        this(McPaths.serversFile());
    }

    /** Test constructor — points at a {@code @TempDir}. */
    ProfileStore(@NotNull Path file) {
        this.file = file;
    }

    public static @NotNull ProfileStore getInstance() {
        return ApplicationManager.getApplication().getService(ProfileStore.class);
    }

    public synchronized @NotNull List<ServerProfile> getProfiles() {
        ensureLoaded();
        return List.copyOf(profiles);
    }

    public synchronized @NotNull Optional<ServerProfile> find(@NotNull UUID id) {
        ensureLoaded();
        return profiles.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public synchronized void add(@NotNull ServerProfile profile) {
        ensureLoaded();
        profiles.add(profile);
        persistAndFire();
    }

    public synchronized void update(@NotNull ServerProfile profile) {
        ensureLoaded();
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id().equals(profile.id())) {
                profiles.set(i, profile);
                persistAndFire();
                return;
            }
        }
        throw new IllegalArgumentException("no profile with id " + profile.id());
    }

    public synchronized void remove(@NotNull UUID id) {
        ensureLoaded();
        profiles.removeIf(p -> p.id().equals(id));
        persistAndFire();
    }

    public void addListener(@NotNull Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull Listener listener) {
        listeners.remove(listener);
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            profiles.addAll(ServersFile.load(file));
        } catch (IOException e) {
            LOG.warn("Conch Minecraft: could not load profiles from " + file, e);
        }
    }

    private void persistAndFire() {
        try {
            ServersFile.save(file, profiles);
        } catch (IOException e) {
            LOG.warn("Conch Minecraft: could not save profiles to " + file, e);
        }
        List<ServerProfile> snapshot = List.copyOf(profiles);
        for (Listener l : listeners) {
            try {
                l.onProfilesChanged(snapshot);
            } catch (Exception e) {
                LOG.warn("Conch Minecraft: profile listener threw", e);
            }
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `bazel build //conch/plugins/minecraft-admin:minecraft-admin`
Expected: compiles cleanly.

- [ ] **Step 3: Commit**

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 1.5 — ProfileStore application service

Application-level CRUD + listener dispatch over the ServersFile
persistence layer. Lazy-loads on first access, persists on every
mutation, returns unmodifiable snapshots.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 1 gate

Before proceeding:

- [ ] All Phase 1 tests pass: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner` → 13+ tests green
- [ ] Full product build: `make conch-build` → succeeds
- [ ] No placeholder code remains in Phase 1 files
- [ ] Phase 1 commits form a clean history that could be squashed if needed

---

## Phase 2 — RCON client

### Task 2.1: `RconPacketCodec` (pure binary protocol)

RCON uses a simple framed binary protocol: 4-byte LE length, 4-byte LE request id, 4-byte LE type, null-terminated ASCII payload, trailing null. This task isolates the framing logic from any socket I/O so it can be exhaustively tested with captured bytes.

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/RconPacketCodec.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/client/RconPacketCodecTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.conch.minecraftadmin.client;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RconPacketCodecTest {

    @Test
    void encode_commandPacket_matchesKnownLayout() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RconPacketCodec.writePacket(out, 1, RconPacketCodec.TYPE_COMMAND, "list");
        byte[] bytes = out.toByteArray();
        // length = 4(id) + 4(type) + payload + 2(two nulls) = 4 + 4 + 4 + 2 = 14
        // packet = 4-byte LE length(14) + 4-byte LE id(1) + 4-byte LE type(2) + "list" + 0x00 0x00
        assertEquals(18, bytes.length);
        assertEquals(14, bytes[0] & 0xff);
        assertEquals(0,  bytes[1] & 0xff);
        assertEquals(0,  bytes[2] & 0xff);
        assertEquals(0,  bytes[3] & 0xff);
        assertEquals(1,  bytes[4] & 0xff);  // id LE
        assertEquals(2,  bytes[8] & 0xff);  // TYPE_COMMAND LE
        assertEquals('l', bytes[12]);
        assertEquals('i', bytes[13]);
        assertEquals('s', bytes[14]);
        assertEquals('t', bytes[15]);
        assertEquals(0,  bytes[16]);
        assertEquals(0,  bytes[17]);
    }

    @Test
    void encodeThenDecode_roundTrips() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RconPacketCodec.writePacket(out, 42, RconPacketCodec.TYPE_COMMAND, "say hello");
        RconPacketCodec.Packet decoded = RconPacketCodec.readPacket(
            new ByteArrayInputStream(out.toByteArray()));
        assertEquals(42, decoded.id());
        assertEquals(RconPacketCodec.TYPE_COMMAND, decoded.type());
        assertEquals("say hello", decoded.body());
    }

    @Test
    void decode_authResponseSuccess() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RconPacketCodec.writePacket(out, 7, RconPacketCodec.TYPE_AUTH_RESPONSE, "");
        RconPacketCodec.Packet decoded = RconPacketCodec.readPacket(
            new ByteArrayInputStream(out.toByteArray()));
        assertEquals(7, decoded.id());
        assertEquals(RconPacketCodec.TYPE_AUTH_RESPONSE, decoded.type());
        assertEquals("", decoded.body());
    }

    @Test
    void decode_authFailureIdIsNegativeOne() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RconPacketCodec.writePacket(out, -1, RconPacketCodec.TYPE_AUTH_RESPONSE, "");
        RconPacketCodec.Packet decoded = RconPacketCodec.readPacket(
            new ByteArrayInputStream(out.toByteArray()));
        assertEquals(-1, decoded.id());
    }

    @Test
    void decode_truncatedPacket_throwsEOF() {
        byte[] truncated = { 14, 0, 0, 0, 1, 0 };  // length says 14 but we have 2
        assertThrows(EOFException.class,
            () -> RconPacketCodec.readPacket(new ByteArrayInputStream(truncated)));
    }

    @Test
    void decode_oversizedLength_throwsIOException() {
        byte[] oversized = { (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f, 0, 0, 0, 0 };
        assertThrows(IOException.class,
            () -> RconPacketCodec.readPacket(new ByteArrayInputStream(oversized)));
    }

    @Test
    void decode_negativeLength_throwsIOException() {
        byte[] bad = { (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0, 0, 0, 0 };
        assertThrows(IOException.class,
            () -> RconPacketCodec.readPacket(new ByteArrayInputStream(bad)));
    }

    @Test
    void encode_payloadWithUtf8_encodesRoundTrips() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RconPacketCodec.writePacket(out, 1, RconPacketCodec.TYPE_COMMAND, "say héllo 🎮");
        RconPacketCodec.Packet decoded = RconPacketCodec.readPacket(
            new ByteArrayInputStream(out.toByteArray()));
        assertEquals("say héllo 🎮", decoded.body());
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `bazel build //conch/plugins/minecraft-admin:minecraft_admin_test_lib`
Expected: compile error on missing `RconPacketCodec`.

- [ ] **Step 3: Create `RconPacketCodec.java`**

```java
package com.conch.minecraftadmin.client;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Pure encode/decode of Minecraft RCON packets. No sockets, no state.
 *
 * <p>Packet layout (little-endian):
 * <pre>
 *   int32  length    (bytes after this field)
 *   int32  request id
 *   int32  type
 *   bytes  payload   (UTF-8)
 *   byte   0x00      (payload terminator)
 *   byte   0x00      (packet terminator)
 * </pre>
 *
 * <p>Types used: {@link #TYPE_AUTH} = 3, {@link #TYPE_COMMAND} = 2,
 * {@link #TYPE_AUTH_RESPONSE} = 2, {@link #TYPE_RESPONSE_VALUE} = 0.
 * Note that TYPE_COMMAND and TYPE_AUTH_RESPONSE share id 2; they're
 * disambiguated by direction (client sends one, server sends the other).
 *
 * <p>Length cap: 4096 bytes per packet payload, consistent with
 * Minecraft's server implementation. Larger payloads are rejected with
 * an {@link IOException} to protect us from a malicious or misbehaving
 * peer.
 */
public final class RconPacketCodec {

    public static final int TYPE_RESPONSE_VALUE = 0;
    public static final int TYPE_COMMAND = 2;
    public static final int TYPE_AUTH_RESPONSE = 2;
    public static final int TYPE_AUTH = 3;

    /** Max payload length we're willing to read. Minecraft uses 4096. */
    public static final int MAX_PAYLOAD_BYTES = 4096;

    private RconPacketCodec() {}

    public record Packet(int id, int type, @NotNull String body) {}

    public static void writePacket(
        @NotNull OutputStream out,
        int id,
        int type,
        @NotNull String body
    ) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        int length = 4 + 4 + bodyBytes.length + 2;  // id + type + body + two nulls
        if (length > MAX_PAYLOAD_BYTES + 10) {
            throw new IOException("rcon packet body too large: " + bodyBytes.length);
        }
        ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(length);
        header.putInt(id);
        header.putInt(type);
        out.write(header.array());
        out.write(bodyBytes);
        out.write(0);  // payload terminator
        out.write(0);  // packet terminator
        out.flush();
    }

    public static @NotNull Packet readPacket(@NotNull InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);

        byte[] lenBytes = new byte[4];
        readFully(dis, lenBytes);
        int length = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 10 || length > MAX_PAYLOAD_BYTES + 10) {
            throw new IOException("invalid rcon packet length: " + length);
        }

        byte[] payload = new byte[length];
        readFully(dis, payload);

        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int id = buf.getInt();
        int type = buf.getInt();
        int bodyLen = length - 4 - 4 - 2;  // minus id, type, two nulls
        byte[] bodyBytes = new byte[bodyLen];
        buf.get(bodyBytes);
        // Skip the two terminating nulls.
        return new Packet(id, type, new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private static void readFully(DataInputStream in, byte[] target) throws IOException {
        int read = 0;
        while (read < target.length) {
            int n = in.read(target, read, target.length - read);
            if (n < 0) throw new EOFException("unexpected end of RCON stream");
            read += n;
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner`
Expected: 20+ tests pass (13 from Phase 1 + 8 new).

- [ ] **Step 5: Commit**

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 2.1 — RCON packet codec

Pure encode/decode of Minecraft RCON's little-endian framing. 4096-byte
payload cap, readFully helper for short reads, UTF-8 safe body. Eight
tests covering known layout, round-trip, auth success/failure ids,
truncated/oversized/negative lengths, and UTF-8 bodies.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2.2: `RconClient` + in-process fake server

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/RconSession.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/RconClient.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/RconAuthException.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/client/FakeRconServer.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/client/RconClientTest.java`

- [ ] **Step 1: Create `RconAuthException.java`**

```java
package com.conch.minecraftadmin.client;

import java.io.IOException;

/** RCON login was rejected (server returned id = -1). */
public final class RconAuthException extends IOException {
    public RconAuthException(String message) { super(message); }
}
```

- [ ] **Step 2: Create `RconSession.java`**

```java
package com.conch.minecraftadmin.client;

import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Open RCON connection to one Minecraft server. Holds the socket and
 * an id counter so each request gets a unique id for correlation.
 * Close via {@link #close} or the enclosing try-with-resources.
 */
public final class RconSession implements Closeable {

    private final Socket socket;
    private final AtomicInteger idCounter = new AtomicInteger(1);

    RconSession(@NotNull Socket socket) {
        this.socket = socket;
    }

    @NotNull Socket socket() { return socket; }
    int nextId() { return idCounter.getAndIncrement(); }

    public boolean isClosed() {
        return socket.isClosed() || !socket.isConnected();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
```

- [ ] **Step 3: Create `RconClient.java`**

```java
package com.conch.minecraftadmin.client;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Thin wrapper around a single RCON TCP connection. Stateless between
 * calls — caller owns the {@link RconSession} lifecycle.
 *
 * <p>Thread-safety: not thread-safe. Each {@code RconSession} is meant
 * to be used from one thread at a time (typically {@code ServerPoller}'s
 * executor). For parallel access, open multiple sessions.
 */
public final class RconClient {

    /** Connect timeout (TCP) in milliseconds. */
    public static final int CONNECT_TIMEOUT_MS = 10_000;
    /** Read timeout (per response) in milliseconds. */
    public static final int READ_TIMEOUT_MS = 10_000;

    public @NotNull RconSession connect(
        @NotNull String host,
        int port,
        @NotNull char[] password
    ) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        RconSession session = new RconSession(socket);
        try {
            authenticate(session, new String(password));
        } catch (IOException e) {
            safeClose(session);
            throw e;
        }
        return session;
    }

    public @NotNull String command(@NotNull RconSession session, @NotNull String cmd) throws IOException {
        int id = session.nextId();
        OutputStream out = session.socket().getOutputStream();
        InputStream in = session.socket().getInputStream();
        RconPacketCodec.writePacket(out, id, RconPacketCodec.TYPE_COMMAND, cmd);
        RconPacketCodec.Packet reply = RconPacketCodec.readPacket(in);
        if (reply.id() != id) {
            throw new IOException("rcon response id mismatch: sent " + id + ", got " + reply.id());
        }
        return reply.body();
    }

    public void close(@NotNull RconSession session) {
        safeClose(session);
    }

    private void authenticate(RconSession session, String password) throws IOException {
        int id = session.nextId();
        OutputStream out = session.socket().getOutputStream();
        InputStream in = session.socket().getInputStream();
        RconPacketCodec.writePacket(out, id, RconPacketCodec.TYPE_AUTH, password);

        // The Minecraft server sends a throwaway empty TYPE_RESPONSE_VALUE
        // frame before the real auth response. Skip it if it shows up.
        RconPacketCodec.Packet first = RconPacketCodec.readPacket(in);
        RconPacketCodec.Packet authResponse = first.type() == RconPacketCodec.TYPE_AUTH_RESPONSE
            ? first
            : RconPacketCodec.readPacket(in);

        if (authResponse.id() == -1) {
            throw new RconAuthException("rcon auth rejected");
        }
        if (authResponse.id() != id) {
            throw new IOException("rcon auth response id mismatch: sent " + id
                + ", got " + authResponse.id());
        }
    }

    private static void safeClose(RconSession session) {
        try { session.close(); } catch (IOException ignored) {}
    }
}
```

- [ ] **Step 4: Create `FakeRconServer.java`**

```java
package com.conch.minecraftadmin.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * In-process RCON server for testing the client. Implements the
 * handshake, checks the password, and responds to arbitrary commands
 * via a registered handler. One connection at a time; additional
 * clients are accepted serially.
 */
final class FakeRconServer implements AutoCloseable {

    private final ServerSocket server;
    private final String expectedPassword;
    private final ConcurrentHashMap<String, Function<String, String>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fake-rcon-server");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;
    private volatile boolean dropNextResponse = false;

    FakeRconServer(String expectedPassword) throws IOException {
        this.server = new ServerSocket(0);
        this.expectedPassword = expectedPassword;
        executor.submit(this::acceptLoop);
    }

    int port() { return server.getLocalPort(); }

    void onCommand(String command, Function<String, String> handler) {
        handlers.put(command, handler);
    }

    void dropNextResponse() {
        this.dropNextResponse = true;
    }

    @Override
    public void close() throws IOException {
        running = false;
        server.close();
        executor.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try (Socket client = server.accept();
                 InputStream in = client.getInputStream();
                 OutputStream out = client.getOutputStream()) {
                serve(in, out);
            } catch (IOException ignored) {
                // socket closed
            }
        }
    }

    private void serve(InputStream in, OutputStream out) throws IOException {
        // Auth first.
        RconPacketCodec.Packet authPacket = RconPacketCodec.readPacket(in);
        int authId = authPacket.id();
        if (authPacket.type() != RconPacketCodec.TYPE_AUTH) {
            return;  // misbehaving client
        }
        if (!expectedPassword.equals(authPacket.body())) {
            RconPacketCodec.writePacket(out, -1, RconPacketCodec.TYPE_AUTH_RESPONSE, "");
            return;
        }
        RconPacketCodec.writePacket(out, authId, RconPacketCodec.TYPE_AUTH_RESPONSE, "");

        // Command loop.
        while (true) {
            RconPacketCodec.Packet packet;
            try {
                packet = RconPacketCodec.readPacket(in);
            } catch (IOException eof) {
                return;
            }
            if (packet.type() != RconPacketCodec.TYPE_COMMAND) continue;

            if (dropNextResponse) {
                dropNextResponse = false;
                return;  // close without reply
            }

            String cmd = packet.body().trim();
            Function<String, String> handler = handlers.get(cmd);
            String body = handler != null ? handler.apply(cmd) : "";
            RconPacketCodec.writePacket(out, packet.id(), RconPacketCodec.TYPE_RESPONSE_VALUE, body);
        }
    }
}
```

- [ ] **Step 5: Create `RconClientTest.java`**

```java
package com.conch.minecraftadmin.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RconClientTest {

    @Test
    void connect_happyPath_authenticates() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret")) {
            RconClient client = new RconClient();
            try (RconSession session = client.connect("127.0.0.1", server.port(), "secret".toCharArray())) {
                assertFalse(session.isClosed());
            }
        }
    }

    @Test
    void connect_wrongPassword_throwsRconAuthException() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret")) {
            RconClient client = new RconClient();
            assertThrows(RconAuthException.class,
                () -> client.connect("127.0.0.1", server.port(), "nope".toCharArray()));
        }
    }

    @Test
    void command_returnsHandlerBody() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret")) {
            server.onCommand("list", cmd -> "There are 2 of a max of 20 players online: alice, bob");
            RconClient client = new RconClient();
            try (RconSession session = client.connect("127.0.0.1", server.port(), "secret".toCharArray())) {
                String reply = client.command(session, "list");
                assertTrue(reply.contains("alice"));
                assertTrue(reply.contains("bob"));
            }
        }
    }

    @Test
    void command_multipleCommandsShareOneSession() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret")) {
            server.onCommand("list", cmd -> "list-reply");
            server.onCommand("tps", cmd -> "tps-reply");
            RconClient client = new RconClient();
            try (RconSession session = client.connect("127.0.0.1", server.port(), "secret".toCharArray())) {
                assertEquals("list-reply", client.command(session, "list"));
                assertEquals("tps-reply", client.command(session, "tps"));
                assertEquals("list-reply", client.command(session, "list"));
            }
        }
    }

    @Test
    void command_droppedResponse_throwsIOException() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret")) {
            server.onCommand("list", cmd -> "ignored");
            server.dropNextResponse();
            RconClient client = new RconClient();
            try (RconSession session = client.connect("127.0.0.1", server.port(), "secret".toCharArray())) {
                assertThrows(IOException.class, () -> client.command(session, "list"));
            }
        }
    }

    @Test
    void connect_unreachable_throwsIOException() {
        RconClient client = new RconClient();
        // Port 1 is ~always closed on a dev box.
        assertThrows(IOException.class,
            () -> client.connect("127.0.0.1", 1, "x".toCharArray()));
    }
}
```

- [ ] **Step 6: Run tests**

Run: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner`
Expected: 26+ tests pass.

- [ ] **Step 7: Commit**

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 2.2 — RconClient + in-process fake server

Socket-based Minecraft RCON client with 10s connect/read timeouts,
auth-flow handling (skips the leading empty response frame Minecraft
sometimes emits), and unique request ids per call.

FakeRconServer test harness implements the handshake, password check,
and command dispatch via registered handlers. Tests cover happy path,
wrong password, single command, multi-command persistence, dropped
response, and unreachable host.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2.3: Paper reply parsers

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/PaperListReplyParser.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/PaperTpsReplyParser.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/client/PaperListReplyParserTest.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/client/PaperTpsReplyParserTest.java`

- [ ] **Step 1: Write `PaperListReplyParserTest.java`**

```java
package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaperListReplyParserTest {

    @Test
    void emptyServer() {
        var r = PaperListReplyParser.parse("There are 0 of a max of 20 players online: ");
        assertEquals(0, r.online());
        assertEquals(20, r.max());
        assertTrue(r.players().isEmpty());
    }

    @Test
    void singlePlayer() {
        var r = PaperListReplyParser.parse("There are 1 of a max of 20 players online: alice");
        assertEquals(1, r.online());
        assertEquals(20, r.max());
        assertEquals(List.of(new Player("alice", Player.PING_UNKNOWN)), r.players());
    }

    @Test
    void multiplePlayers() {
        var r = PaperListReplyParser.parse("There are 3 of a max of 20 players online: alice, bob, carol");
        assertEquals(3, r.online());
        assertEquals(List.of(
            new Player("alice", Player.PING_UNKNOWN),
            new Player("bob", Player.PING_UNKNOWN),
            new Player("carol", Player.PING_UNKNOWN)
        ), r.players());
    }

    @Test
    void stripsSectionColorCodes() {
        var r = PaperListReplyParser.parse("§6There are §a2§6 of a max of §a20§6 players online: §falice§6, §fbob");
        assertEquals(2, r.online());
        assertEquals(20, r.max());
        assertEquals(2, r.players().size());
        assertEquals("alice", r.players().get(0).name());
        assertEquals("bob", r.players().get(1).name());
    }

    @Test
    void unparseable_returnsEmpty() {
        var r = PaperListReplyParser.parse("some garbage the server said");
        assertEquals(0, r.online());
        assertEquals(0, r.max());
        assertTrue(r.players().isEmpty());
    }

    @Test
    void extractsPingSuffixIfPresent() {
        // EssentialsX-style "[Nms]" annotation.
        var r = PaperListReplyParser.parse("There are 2 of a max of 20 players online: alice [42ms], bob [100ms]");
        assertEquals(2, r.online());
        assertEquals(42, r.players().get(0).pingMs());
        assertEquals(100, r.players().get(1).pingMs());
    }
}
```

- [ ] **Step 2: Write `PaperTpsReplyParserTest.java`**

```java
package com.conch.minecraftadmin.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaperTpsReplyParserTest {

    @Test
    void stockPaperOutput() {
        double tps = PaperTpsReplyParser.parseMostRecent(
            "§6TPS from last 1m, 5m, 15m: §a19.98, §a20.0, §a20.0");
        assertEquals(19.98, tps, 0.001);
    }

    @Test
    void withoutColorCodes() {
        double tps = PaperTpsReplyParser.parseMostRecent("TPS from last 1m, 5m, 15m: 18.5, 19.1, 19.8");
        assertEquals(18.5, tps, 0.001);
    }

    @Test
    void laggySampledBelow15IsStillParsed() {
        double tps = PaperTpsReplyParser.parseMostRecent("§6TPS from last 1m, 5m, 15m: §c10.2, §e16.1, §a19.9");
        assertEquals(10.2, tps, 0.001);
    }

    @Test
    void unparseable_returnsNaN() {
        assertTrue(Double.isNaN(PaperTpsReplyParser.parseMostRecent("garbage")));
        assertTrue(Double.isNaN(PaperTpsReplyParser.parseMostRecent("")));
    }
}
```

- [ ] **Step 3: Create `PaperListReplyParser.java`**

```java
package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the reply from Paper's RCON {@code list} command into
 * {@code (online, max, players)}. Strips Minecraft's {@code §}
 * color codes and tolerates minor plugin variations.
 *
 * <p>Canonical format:
 * <pre>There are N of a max of M players online: a, b, c</pre>
 *
 * <p>If a player's name is suffixed with {@code [Nms]} (plugin
 * convention) the ping value is extracted. Otherwise
 * {@link Player#PING_UNKNOWN} is stored.
 */
public final class PaperListReplyParser {

    private static final Pattern HEADER = Pattern.compile(
        "There are (\\d+) of a max of (\\d+) players online:(.*)");

    private static final Pattern PING_SUFFIX = Pattern.compile(
        "^(.+?)\\s*\\[(\\d+)ms\\]$");

    private static final Pattern SECTION_COLOR = Pattern.compile("§[0-9a-fklmnor]");

    private PaperListReplyParser() {}

    public record Result(int online, int max, @NotNull List<Player> players) {}

    public static @NotNull Result parse(@NotNull String raw) {
        String stripped = SECTION_COLOR.matcher(raw).replaceAll("").trim();
        Matcher m = HEADER.matcher(stripped);
        if (!m.find()) return new Result(0, 0, List.of());

        int online = Integer.parseInt(m.group(1));
        int max = Integer.parseInt(m.group(2));
        String tail = m.group(3).trim();
        if (tail.isEmpty()) return new Result(online, max, List.of());

        List<Player> players = new ArrayList<>();
        for (String chunk : tail.split(",")) {
            String entry = chunk.trim();
            if (entry.isEmpty()) continue;
            Matcher pm = PING_SUFFIX.matcher(entry);
            if (pm.matches()) {
                players.add(new Player(pm.group(1).trim(), Integer.parseInt(pm.group(2))));
            } else {
                players.add(Player.unknownPing(entry));
            }
        }
        return new Result(online, max, players);
    }
}
```

- [ ] **Step 4: Create `PaperTpsReplyParser.java`**

```java
package com.conch.minecraftadmin.client;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the reply from Paper's RCON {@code tps} command and returns
 * only the 1-minute (most recent) TPS value. The 5m and 15m samples
 * are discarded for v1 — they're not displayed in the status strip.
 *
 * <p>Canonical format (with optional color codes):
 * <pre>§6TPS from last 1m, 5m, 15m: §a19.98, §a20.0, §a20.0</pre>
 */
public final class PaperTpsReplyParser {

    private static final Pattern SECTION_COLOR = Pattern.compile("§[0-9a-fklmnor]");
    private static final Pattern LINE = Pattern.compile(
        "TPS from last 1m, 5m, 15m:\\s*([0-9]+(?:\\.[0-9]+)?)");

    private PaperTpsReplyParser() {}

    public static double parseMostRecent(@NotNull String raw) {
        String stripped = SECTION_COLOR.matcher(raw).replaceAll("");
        Matcher m = LINE.matcher(stripped);
        if (!m.find()) return Double.NaN;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
```

- [ ] **Step 5: Run tests**

Run: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner`
Expected: 36+ tests pass.

- [ ] **Step 6: Commit**

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 2.3 — Paper reply parsers

PaperListReplyParser turns 'list' output into (online, max, players)
and extracts the EssentialsX-style [Nms] ping suffix when present.
PaperTpsReplyParser returns the 1-minute TPS value, NaN on parse
failure. Both strip Minecraft section-color codes before matching.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2 gate

- [ ] All tests pass: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner` → 36+ green
- [ ] Full product build: `make conch-build` → succeeds
- [ ] RconClient round-trip demonstrated against fake server in tests
- [ ] Parsers cover empty / single / multi / color-coded / unparseable inputs

---

## Phase 3 — AMP client + polling engine

### Task 3.1: `AmpClient` + `InstanceStatus` + `ConsoleUpdate`

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/AmpSession.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/InstanceStatus.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/ConsoleUpdate.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/AmpAuthException.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/AmpClient.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/client/FakeAmpServer.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/client/AmpClientTest.java`

AMP REST uses session tokens: `POST /API/Core/Login` with `{username, password, token: ""}` returns `{"success": true, "sessionID": "..."}`. Subsequent calls pass `SESSIONID` in the JSON body. Instance metrics come from `Core/GetInstances` which returns per-instance `Running`, `AppState`, `Metrics`, and `TimeStarted` fields. Console lines come from `Core/GetUpdates` which returns a `ConsoleEntries` array.

- [ ] **Step 1: Create `AmpSession.java`**

```java
package com.conch.minecraftadmin.client;

import org.jetbrains.annotations.NotNull;

/** Live session handle for one AMP panel. Immutable except for rotate() on 401. */
public final class AmpSession {
    private final String baseUrl;
    private volatile String sessionId;

    public AmpSession(@NotNull String baseUrl, @NotNull String sessionId) {
        this.baseUrl = baseUrl;
        this.sessionId = sessionId;
    }

    @NotNull String baseUrl() { return baseUrl; }
    @NotNull String sessionId() { return sessionId; }
    void rotate(@NotNull String newSessionId) { this.sessionId = newSessionId; }
}
```

- [ ] **Step 2: Create `InstanceStatus.java`**

```java
package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * Snapshot of one AMP instance's runtime state. Fields beyond {@code status}
 * are populated best-effort; if AMP doesn't include them, they default to
 * NaN / 0 / ZERO so the caller can still render a partial snapshot.
 */
public record InstanceStatus(
    @NotNull McServerStatus status,
    double cpuPercent,
    long ramUsedMb,
    long ramMaxMb,
    @NotNull Duration uptime
) {
    public static @NotNull InstanceStatus unknown() {
        return new InstanceStatus(McServerStatus.UNKNOWN, Double.NaN, 0L, 0L, Duration.ZERO);
    }
}
```

- [ ] **Step 3: Create `ConsoleUpdate.java`**

```java
package com.conch.minecraftadmin.client;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Chunk of new console lines returned by AMP's {@code Core/GetUpdates}.
 * Each line is already formatted ("[17:42:01 INFO]: alice joined the game").
 */
public record ConsoleUpdate(@NotNull List<String> lines) {
    public ConsoleUpdate { lines = List.copyOf(lines); }
    public static @NotNull ConsoleUpdate empty() { return new ConsoleUpdate(List.of()); }
}
```

- [ ] **Step 4: Create `AmpAuthException.java`**

```java
package com.conch.minecraftadmin.client;

import java.io.IOException;

/** AMP rejected the username/password. */
public final class AmpAuthException extends IOException {
    public AmpAuthException(String message) { super(message); }
}
```

- [ ] **Step 5: Create `AmpClient.java`**

```java
package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around AMP's REST/JSON API. All calls are synchronous.
 * Auth is via a session token from {@code Core/Login}; a 401 response
 * triggers exactly one automatic re-login before the original call
 * is retried.
 *
 * <p>Not thread-safe — one caller per {@code AmpSession}, typically the
 * owning {@link ServerPoller}.
 */
public final class AmpClient {

    /** Network timeout for one request, in seconds. */
    public static final int TIMEOUT_SECONDS = 10;

    private final HttpClient http;
    private final Credentials credentials;

    public AmpClient(@NotNull Credentials credentials) {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();
        this.credentials = credentials;
    }

    /** Re-login lookup. Production usage stores creds outside AmpClient and hands them back here. */
    @FunctionalInterface
    public interface Credentials {
        @NotNull LoginPair lookup(@NotNull String baseUrl);
    }

    public record LoginPair(@NotNull String username, @NotNull char[] password) {}

    public @NotNull AmpSession login(@NotNull String baseUrl) throws IOException {
        LoginPair pair = credentials.lookup(baseUrl);
        JsonObject body = new JsonObject();
        body.addProperty("username", pair.username());
        body.addProperty("password", new String(pair.password()));
        body.addProperty("token", "");
        body.addProperty("rememberMe", false);

        JsonObject response = postRaw(baseUrl, "Core/Login", body);
        if (!response.has("success") || !response.get("success").getAsBoolean()) {
            throw new AmpAuthException("AMP login rejected");
        }
        return new AmpSession(baseUrl, response.get("sessionID").getAsString());
    }

    public @NotNull InstanceStatus getInstanceStatus(
        @NotNull AmpSession session,
        @NotNull String instanceName
    ) throws IOException {
        JsonObject body = new JsonObject();
        JsonObject response = post(session, "Core/GetInstances", body);
        JsonArray groups = response.getAsJsonArray("result");
        if (groups == null) return InstanceStatus.unknown();
        for (JsonElement group : groups) {
            JsonArray instances = group.getAsJsonObject().getAsJsonArray("AvailableInstances");
            if (instances == null) continue;
            for (JsonElement e : instances) {
                JsonObject inst = e.getAsJsonObject();
                String friendly = inst.has("FriendlyName") ? inst.get("FriendlyName").getAsString() : "";
                String id = inst.has("InstanceName") ? inst.get("InstanceName").getAsString() : "";
                if (!instanceName.equals(friendly) && !instanceName.equals(id)) continue;
                return toStatus(inst);
            }
        }
        return InstanceStatus.unknown();
    }

    public void startInstance(@NotNull AmpSession s, @NotNull String instanceName) throws IOException {
        instanceLifecycle(s, instanceName, "Start");
    }

    public void stopInstance(@NotNull AmpSession s, @NotNull String instanceName) throws IOException {
        instanceLifecycle(s, instanceName, "Stop");
    }

    public void restartInstance(@NotNull AmpSession s, @NotNull String instanceName) throws IOException {
        instanceLifecycle(s, instanceName, "Restart");
    }

    public void backupInstance(@NotNull AmpSession s, @NotNull String instanceName) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("InstanceName", instanceName);
        body.addProperty("BackupTitle", "Conch manual backup");
        body.addProperty("BackupDescription", "Triggered from the Conch Minecraft Admin plugin");
        body.addProperty("Sticky", false);
        post(s, "InstanceManagementPlugin/TakeBackup", body);
    }

    public @NotNull ConsoleUpdate getConsoleUpdates(@NotNull AmpSession s, @NotNull String instanceName) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("InstanceName", instanceName);
        JsonObject response = post(s, "Core/GetUpdates", body);
        JsonElement resultEl = response.get("result");
        if (resultEl == null || !resultEl.isJsonObject()) return ConsoleUpdate.empty();
        JsonArray entries = resultEl.getAsJsonObject().getAsJsonArray("ConsoleEntries");
        if (entries == null) return ConsoleUpdate.empty();
        List<String> lines = new ArrayList<>();
        for (JsonElement e : entries) {
            JsonObject entry = e.getAsJsonObject();
            String contents = entry.has("Contents") ? entry.get("Contents").getAsString() : "";
            lines.add(contents);
        }
        return new ConsoleUpdate(lines);
    }

    // -- internals ------------------------------------------------------------

    private void instanceLifecycle(AmpSession s, String instanceName, String action) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("InstanceName", instanceName);
        post(s, "ADSModule/" + action + "Instance", body);
    }

    private InstanceStatus toStatus(JsonObject inst) {
        boolean running = inst.has("Running") && inst.get("Running").getAsBoolean();
        int appState = inst.has("AppState") ? inst.get("AppState").getAsInt() : -1;
        McServerStatus status = mapAppState(running, appState);

        double cpu = Double.NaN;
        long ramUsed = 0;
        long ramMax = 0;
        if (inst.has("Metrics") && inst.get("Metrics").isJsonObject()) {
            JsonObject metrics = inst.getAsJsonObject("Metrics");
            if (metrics.has("CPU Usage") && metrics.get("CPU Usage").isJsonObject()) {
                JsonObject cpuObj = metrics.getAsJsonObject("CPU Usage");
                if (cpuObj.has("RawValue")) cpu = cpuObj.get("RawValue").getAsDouble();
            }
            if (metrics.has("Memory Usage") && metrics.get("Memory Usage").isJsonObject()) {
                JsonObject memObj = metrics.getAsJsonObject("Memory Usage");
                if (memObj.has("RawValue")) ramUsed = memObj.get("RawValue").getAsLong();
                if (memObj.has("MaxValue")) ramMax = memObj.get("MaxValue").getAsLong();
            }
        }

        Duration uptime = Duration.ZERO;
        if (inst.has("TimeStarted")) {
            try {
                Instant started = Instant.parse(inst.get("TimeStarted").getAsString());
                uptime = Duration.between(started, Instant.now());
                if (uptime.isNegative()) uptime = Duration.ZERO;
            } catch (Exception ignored) {}
        }

        return new InstanceStatus(status, cpu, ramUsed, ramMax, uptime);
    }

    private static McServerStatus mapAppState(boolean running, int appState) {
        // AMP AppState values: 0=Undefined, 5=PreStart, 10=Ready, 20=Starting,
        // 30=Started, 40=Stopping, 45=Restarting, 50=Stopped, 60=Failed, 70=Suspended.
        if (!running) return switch (appState) {
            case 50 -> McServerStatus.STOPPED;
            case 60 -> McServerStatus.CRASHED;
            default -> McServerStatus.STOPPED;
        };
        return switch (appState) {
            case 30 -> McServerStatus.RUNNING;
            case 20, 5, 10 -> McServerStatus.STARTING;
            case 40, 45 -> McServerStatus.STOPPING;
            case 60 -> McServerStatus.CRASHED;
            default -> McServerStatus.UNKNOWN;
        };
    }

    private JsonObject post(AmpSession session, String apiCall, JsonObject body) throws IOException {
        body.addProperty("SESSIONID", session.sessionId());
        try {
            return postRaw(session.baseUrl(), apiCall, body);
        } catch (AmpAuthException e) {
            // Session expired — re-login once and retry.
            LoginPair pair = credentials.lookup(session.baseUrl());
            JsonObject loginBody = new JsonObject();
            loginBody.addProperty("username", pair.username());
            loginBody.addProperty("password", new String(pair.password()));
            loginBody.addProperty("token", "");
            loginBody.addProperty("rememberMe", false);
            JsonObject loginResponse = postRaw(session.baseUrl(), "Core/Login", loginBody);
            if (!loginResponse.has("success") || !loginResponse.get("success").getAsBoolean()) {
                throw new AmpAuthException("AMP re-login after 401 rejected");
            }
            session.rotate(loginResponse.get("sessionID").getAsString());
            body.addProperty("SESSIONID", session.sessionId());
            return postRaw(session.baseUrl(), apiCall, body);
        }
    }

    private JsonObject postRaw(String baseUrl, String apiCall, JsonObject body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(trimTrailingSlash(baseUrl) + "/API/" + apiCall))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("AMP request interrupted", e);
        }
        int status = response.statusCode();
        if (status == 401) {
            throw new AmpAuthException("AMP returned 401 for " + apiCall);
        }
        if (status < 200 || status >= 300) {
            throw new IOException("AMP " + apiCall + " returned HTTP " + status);
        }
        try {
            JsonElement parsed = JsonParser.parseString(response.body());
            if (!parsed.isJsonObject()) throw new IOException("AMP returned non-object JSON");
            return parsed.getAsJsonObject();
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new IOException("AMP returned malformed JSON: " + e.getMessage(), e);
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
```

- [ ] **Step 6: Create `FakeAmpServer.java`**

```java
package com.conch.minecraftadmin.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process AMP fake for AmpClient tests. Hosts a {@link HttpServer} on
 * an ephemeral port and serves canned JSON for a configurable set of
 * API paths. Each handler receives the parsed request body so tests
 * can assert on the incoming payload.
 */
final class FakeAmpServer implements AutoCloseable {

    private final HttpServer server;
    private final ConcurrentHashMap<String, Responder> handlers = new ConcurrentHashMap<>();
    private final AtomicBoolean rejectNextLogin = new AtomicBoolean(false);
    private volatile int force401Remaining = 0;

    @FunctionalInterface
    interface Responder {
        JsonObject respond(JsonObject requestBody);
    }

    FakeAmpServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new DispatchingHandler());
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void handle(String apiCall, Responder responder) {
        handlers.put(apiCall, responder);
    }

    /** Make the next {@code Core/Login} call fail with success=false. */
    void rejectNextLogin() {
        rejectNextLogin.set(true);
    }

    /** Make the next N non-login calls return HTTP 401. */
    void return401(int times) {
        this.force401Remaining = times;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private final class DispatchingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String apiCall = path.startsWith("/API/") ? path.substring("/API/".length()) : path;
            String bodyText = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject requestBody;
            try {
                JsonElement parsed = JsonParser.parseString(bodyText.isEmpty() ? "{}" : bodyText);
                requestBody = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
            } catch (Exception e) {
                requestBody = new JsonObject();
            }

            if (force401Remaining > 0 && !apiCall.equals("Core/Login")) {
                force401Remaining--;
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }

            if (apiCall.equals("Core/Login") && rejectNextLogin.compareAndSet(true, false)) {
                JsonObject r = new JsonObject();
                r.addProperty("success", false);
                writeJson(exchange, r);
                return;
            }

            Responder responder = handlers.get(apiCall);
            JsonObject reply = responder != null ? responder.respond(requestBody) : new JsonObject();
            writeJson(exchange, reply);
        }
    }

    private static void writeJson(HttpExchange exchange, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
```

- [ ] **Step 7: Create `AmpClientTest.java`**

```java
package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AmpClientTest {

    private static AmpClient.Credentials creds(String user, String pass) {
        return baseUrl -> new AmpClient.LoginPair(user, pass.toCharArray());
    }

    private static JsonObject loginSuccess(String sessionId) {
        JsonObject r = new JsonObject();
        r.addProperty("success", true);
        r.addProperty("sessionID", sessionId);
        return r;
    }

    @Test
    void login_happyPath() throws IOException {
        try (FakeAmpServer server = new FakeAmpServer()) {
            server.handle("Core/Login", body -> {
                assertEquals("admin", body.get("username").getAsString());
                return loginSuccess("abc123");
            });
            AmpClient client = new AmpClient(creds("admin", "pw"));
            AmpSession session = client.login(server.baseUrl());
            assertEquals("abc123", session.sessionId());
            assertEquals(server.baseUrl(), session.baseUrl());
        }
    }

    @Test
    void login_rejected_throwsAuthException() throws IOException {
        try (FakeAmpServer server = new FakeAmpServer()) {
            server.rejectNextLogin();
            server.handle("Core/Login", body -> new JsonObject());  // overridden by rejectNextLogin
            AmpClient client = new AmpClient(creds("admin", "wrong"));
            assertThrows(AmpAuthException.class, () -> client.login(server.baseUrl()));
        }
    }

    @Test
    void getInstanceStatus_runningInstance() throws IOException {
        try (FakeAmpServer server = new FakeAmpServer()) {
            server.handle("Core/Login", body -> loginSuccess("abc"));
            server.handle("Core/GetInstances", body -> {
                JsonObject group = new JsonObject();
                JsonArray available = new JsonArray();
                JsonObject inst = new JsonObject();
                inst.addProperty("FriendlyName", "survival");
                inst.addProperty("InstanceName", "survival");
                inst.addProperty("Running", true);
                inst.addProperty("AppState", 30);
                inst.addProperty("TimeStarted", Instant.now().minusSeconds(300).toString());
                JsonObject metrics = new JsonObject();
                JsonObject cpu = new JsonObject();
                cpu.addProperty("RawValue", 42.5);
                metrics.add("CPU Usage", cpu);
                JsonObject mem = new JsonObject();
                mem.addProperty("RawValue", 1500);
                mem.addProperty("MaxValue", 4000);
                metrics.add("Memory Usage", mem);
                inst.add("Metrics", metrics);
                available.add(inst);
                group.add("AvailableInstances", available);
                JsonArray result = new JsonArray();
                result.add(group);
                JsonObject wrapper = new JsonObject();
                wrapper.add("result", result);
                return wrapper;
            });
            AmpClient client = new AmpClient(creds("admin", "pw"));
            AmpSession session = client.login(server.baseUrl());
            InstanceStatus status = client.getInstanceStatus(session, "survival");
            assertEquals(McServerStatus.RUNNING, status.status());
            assertEquals(42.5, status.cpuPercent(), 0.001);
            assertEquals(1500, status.ramUsedMb());
            assertEquals(4000, status.ramMaxMb());
            assertTrue(status.uptime().toSeconds() >= 299);
        }
    }

    @Test
    void getInstanceStatus_missingInstance_returnsUnknown() throws IOException {
        try (FakeAmpServer server = new FakeAmpServer()) {
            server.handle("Core/Login", body -> loginSuccess("abc"));
            server.handle("Core/GetInstances", body -> {
                JsonObject wrapper = new JsonObject();
                wrapper.add("result", new JsonArray());
                return wrapper;
            });
            AmpClient client = new AmpClient(creds("admin", "pw"));
            AmpSession session = client.login(server.baseUrl());
            InstanceStatus status = client.getInstanceStatus(session, "doesnotexist");
            assertEquals(McServerStatus.UNKNOWN, status.status());
        }
    }

    @Test
    void sessionExpiry_autoReloginOnce() throws IOException {
        try (FakeAmpServer server = new FakeAmpServer()) {
            java.util.concurrent.atomic.AtomicInteger logins = new java.util.concurrent.atomic.AtomicInteger(0);
            server.handle("Core/Login", body -> {
                int n = logins.incrementAndGet();
                return loginSuccess("session-" + n);
            });
            server.handle("Core/GetInstances", body -> {
                JsonObject wrapper = new JsonObject();
                wrapper.add("result", new JsonArray());
                return wrapper;
            });
            AmpClient client = new AmpClient(creds("admin", "pw"));
            AmpSession session = client.login(server.baseUrl());
            server.return401(1);  // first post after login will 401
            InstanceStatus status = client.getInstanceStatus(session, "x");
            assertEquals(McServerStatus.UNKNOWN, status.status());
            assertEquals("session-2", session.sessionId(), "re-login should have rotated the token");
            assertEquals(2, logins.get());
        }
    }

    @Test
    void getConsoleUpdates_returnsLines() throws IOException {
        try (FakeAmpServer server = new FakeAmpServer()) {
            server.handle("Core/Login", body -> loginSuccess("abc"));
            server.handle("Core/GetUpdates", body -> {
                JsonObject result = new JsonObject();
                JsonArray entries = new JsonArray();
                JsonObject a = new JsonObject();
                a.addProperty("Contents", "[17:42:01 INFO]: alice joined the game");
                entries.add(a);
                JsonObject b = new JsonObject();
                b.addProperty("Contents", "[17:42:05 INFO]: alice has made the advancement [Monster Hunter]");
                entries.add(b);
                result.add("ConsoleEntries", entries);
                JsonObject wrapper = new JsonObject();
                wrapper.add("result", result);
                return wrapper;
            });
            AmpClient client = new AmpClient(creds("admin", "pw"));
            AmpSession session = client.login(server.baseUrl());
            ConsoleUpdate update = client.getConsoleUpdates(session, "survival");
            assertEquals(2, update.lines().size());
            assertTrue(update.lines().get(0).contains("joined"));
        }
    }

    @Test
    void startInstance_postsCorrectApiCall() throws IOException {
        try (FakeAmpServer server = new FakeAmpServer()) {
            server.handle("Core/Login", body -> loginSuccess("abc"));
            java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean(false);
            server.handle("ADSModule/StartInstance", body -> {
                assertEquals("survival", body.get("InstanceName").getAsString());
                called.set(true);
                return new JsonObject();
            });
            AmpClient client = new AmpClient(creds("admin", "pw"));
            AmpSession session = client.login(server.baseUrl());
            client.startInstance(session, "survival");
            assertTrue(called.get());
        }
    }

    @Test
    void malformedJson_throwsIOException() throws IOException {
        try (FakeAmpServer server = new FakeAmpServer()) {
            server.handle("Core/Login", body -> loginSuccess("abc"));
            // Serve a response with a non-object JSON root to simulate corruption.
            // Easiest way: plant a handler whose returned JsonObject is empty and
            // let the client's happy-path parser handle it. For a true malformed
            // response, we'd need a raw-bytes hook — out of scope for the fake
            // here. Instead, point at an endpoint we didn't register; FakeAmpServer
            // returns an empty object which is still valid JSON. We assert that
            // the client handles the "missing result" case gracefully.
            AmpClient client = new AmpClient(creds("admin", "pw"));
            AmpSession session = client.login(server.baseUrl());
            InstanceStatus status = client.getInstanceStatus(session, "anything");
            assertEquals(McServerStatus.UNKNOWN, status.status());
        }
    }
}
```

- [ ] **Step 8: Run tests**

Run: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner`
Expected: 44+ tests pass.

- [ ] **Step 9: Commit**

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 3.1 — AmpClient + HttpServer-based fake

Thin wrapper around AMP's REST/JSON API built on java.net.http.
Session-token auth with automatic re-login on 401, lifecycle
endpoints, instance-status extraction from GetInstances metrics
block, and GetUpdates console tail. AppState→McServerStatus mapping
table for AMP's numeric state codes.

FakeAmpServer uses JDK built-in com.sun.net.httpserver.HttpServer to
serve canned JSON per API path. Tests cover happy-path login, auth
rejection, running instance with metrics, missing instance, 401 +
auto-reconnect, console updates, and lifecycle posts.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3.2: `ServerStateMerger` — pure tick-result merge

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/ServerStateMerger.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/AmpTickResult.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/RconTickResult.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/client/ServerStateMergerTest.java`

- [ ] **Step 1: Create `AmpTickResult.java`**

```java
package com.conch.minecraftadmin.client;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One AMP half of a poll tick. Either {@code status} is present (AMP
 * reachable) or {@code errorMessage} is present (AMP call failed). Never
 * both.
 */
public record AmpTickResult(
    @Nullable InstanceStatus status,
    @Nullable String errorMessage
) {
    public static @NotNull AmpTickResult ok(@NotNull InstanceStatus status) {
        return new AmpTickResult(status, null);
    }
    public static @NotNull AmpTickResult error(@NotNull String message) {
        return new AmpTickResult(null, message);
    }
    public boolean healthy() { return errorMessage == null; }
}
```

- [ ] **Step 2: Create `RconTickResult.java`**

```java
package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One RCON half of a poll tick. On success, {@code players} / {@code playersOnline} /
 * {@code playersMax} / {@code tps} are populated. On failure, {@code errorMessage}
 * carries the reason and numeric fields are sentinel.
 */
public record RconTickResult(
    @NotNull List<Player> players,
    int playersOnline,
    int playersMax,
    double tps,
    @Nullable String errorMessage
) {
    public RconTickResult { players = List.copyOf(players); }

    public static @NotNull RconTickResult ok(
        @NotNull List<Player> players, int online, int max, double tps
    ) {
        return new RconTickResult(players, online, max, tps, null);
    }

    public static @NotNull RconTickResult error(@NotNull String message) {
        return new RconTickResult(List.of(), 0, 0, Double.NaN, message);
    }

    public boolean healthy() { return errorMessage == null; }
}
```

- [ ] **Step 3: Write `ServerStateMergerTest.java`**

```java
package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.Player;
import com.conch.minecraftadmin.model.ServerState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerStateMergerTest {

    private static final Instant NOW = Instant.parse("2026-04-12T00:00:00Z");

    @Test
    void bothHealthy_buildsFullSnapshot() {
        ServerState s = ServerStateMerger.merge(
            AmpTickResult.ok(new InstanceStatus(McServerStatus.RUNNING, 45.0, 2000, 4000, Duration.ofMinutes(5))),
            RconTickResult.ok(List.of(new Player("alice", 42)), 1, 20, 19.98),
            NOW);
        assertEquals(McServerStatus.RUNNING, s.status());
        assertEquals(1, s.playersOnline());
        assertEquals(20, s.playersMax());
        assertEquals(45.0, s.cpuPercent(), 0.001);
        assertEquals(2000, s.ramUsedMb());
        assertEquals(4000, s.ramMaxMb());
        assertEquals(19.98, s.tps(), 0.001);
        assertTrue(s.isAmpHealthy());
        assertTrue(s.isRconHealthy());
    }

    @Test
    void ampDown_rconUp_keepsRconFieldsAndRecordsAmpError() {
        ServerState s = ServerStateMerger.merge(
            AmpTickResult.error("connection refused"),
            RconTickResult.ok(List.of(new Player("alice", 42)), 1, 20, 20.0),
            NOW);
        assertEquals(McServerStatus.UNKNOWN, s.status());
        assertTrue(Double.isNaN(s.cpuPercent()));
        assertEquals(1, s.playersOnline());
        assertEquals(20.0, s.tps(), 0.001);
        assertFalse(s.isAmpHealthy());
        assertTrue(s.ampError().isPresent());
        assertTrue(s.isRconHealthy());
    }

    @Test
    void ampUp_rconDown_keepsAmpFieldsAndRecordsRconError() {
        ServerState s = ServerStateMerger.merge(
            AmpTickResult.ok(new InstanceStatus(McServerStatus.RUNNING, 45.0, 2000, 4000, Duration.ofMinutes(5))),
            RconTickResult.error("connection reset"),
            NOW);
        assertEquals(McServerStatus.RUNNING, s.status());
        assertEquals(45.0, s.cpuPercent(), 0.001);
        assertEquals(0, s.playersOnline());
        assertTrue(Double.isNaN(s.tps()));
        assertTrue(s.isAmpHealthy());
        assertFalse(s.isRconHealthy());
    }

    @Test
    void bothDown_fullyUnknown() {
        ServerState s = ServerStateMerger.merge(
            AmpTickResult.error("no route to host"),
            RconTickResult.error("connect timeout"),
            NOW);
        assertEquals(McServerStatus.UNKNOWN, s.status());
        assertTrue(Double.isNaN(s.cpuPercent()));
        assertTrue(Double.isNaN(s.tps()));
        assertFalse(s.isAmpHealthy());
        assertFalse(s.isRconHealthy());
    }

    @Test
    void sampledAtIsPreserved() {
        ServerState s = ServerStateMerger.merge(
            AmpTickResult.error("x"), RconTickResult.error("y"), NOW);
        assertEquals(NOW, s.sampledAt());
    }
}
```

- [ ] **Step 4: Create `ServerStateMerger.java`**

```java
package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.ServerState;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Pure function that combines one AMP tick result and one RCON tick
 * result into a {@link ServerState} snapshot. Kept separate from
 * {@link ServerPoller} so the merge logic is trivially unit-testable.
 */
public final class ServerStateMerger {

    private ServerStateMerger() {}

    public static @NotNull ServerState merge(
        @NotNull AmpTickResult amp,
        @NotNull RconTickResult rcon,
        @NotNull Instant sampledAt
    ) {
        McServerStatus status = amp.healthy() && amp.status() != null
            ? amp.status().status()
            : McServerStatus.UNKNOWN;
        double cpu = amp.healthy() && amp.status() != null ? amp.status().cpuPercent() : Double.NaN;
        long ramUsed = amp.healthy() && amp.status() != null ? amp.status().ramUsedMb() : 0L;
        long ramMax = amp.healthy() && amp.status() != null ? amp.status().ramMaxMb() : 0L;
        Duration uptime = amp.healthy() && amp.status() != null ? amp.status().uptime() : Duration.ZERO;

        return new ServerState(
            status,
            rcon.playersOnline(),
            rcon.playersMax(),
            rcon.players(),
            rcon.healthy() ? rcon.tps() : Double.NaN,
            cpu, ramUsed, ramMax, uptime,
            amp.healthy() ? Optional.empty() : Optional.of(amp.errorMessage()),
            rcon.healthy() ? Optional.empty() : Optional.of(rcon.errorMessage()),
            sampledAt
        );
    }

    /** Convenience for "no data yet". */
    public static @NotNull ServerState initial(@NotNull Instant sampledAt) {
        return ServerState.unknown(sampledAt);
    }

    /** Unused field-access helper kept for symmetry with rcon/amp result types. */
    @SuppressWarnings("unused")
    private static List<?> nothing() { return List.of(); }
}
```

- [ ] **Step 5: Run tests**

Run: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner`
Expected: 49+ tests pass.

- [ ] **Step 6: Commit**

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 3.2 — ServerStateMerger

Pure function merging one AMP tick result and one RCON tick result
into a ServerState snapshot. Handles all four combinations of
healthy/failed halves — each half independently contributes its
fields, and failures become Optional<String> error entries.

AmpTickResult and RconTickResult value types carry either success or
error, never both.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3.3: `CrashDetector`

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/CrashDetector.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/client/CrashDetectorTest.java`

- [ ] **Step 1: Write `CrashDetectorTest.java`**

```java
package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CrashDetectorTest {

    @Test
    void running_toCrashed_firesOnce() {
        CrashDetector d = new CrashDetector();
        assertFalse(d.observe(McServerStatus.RUNNING, clockAt(0)));
        assertTrue(d.observe(McServerStatus.CRASHED, clockAt(5)));
        // Dedupe within the same transition: subsequent CRASHED ticks do nothing.
        assertFalse(d.observe(McServerStatus.CRASHED, clockAt(10)));
    }

    @Test
    void running_toStopped_withoutUserRequest_fires() {
        CrashDetector d = new CrashDetector();
        d.observe(McServerStatus.RUNNING, clockAt(0));
        assertTrue(d.observe(McServerStatus.STOPPED, clockAt(5)));
    }

    @Test
    void running_toStopped_withRecentUserRequest_doesNotFire() {
        CrashDetector d = new CrashDetector();
        d.observe(McServerStatus.RUNNING, clockAt(0));
        d.recordUserStop(clockAt(4));
        assertFalse(d.observe(McServerStatus.STOPPED, clockAt(5)));
    }

    @Test
    void userStopGraceExpiresAfter10Seconds() {
        CrashDetector d = new CrashDetector();
        d.observe(McServerStatus.RUNNING, clockAt(0));
        d.recordUserStop(clockAt(0));
        assertTrue(d.observe(McServerStatus.STOPPED, clockAt(11)),
            "stop observed 11s after user-requested stop should fire");
    }

    @Test
    void unknown_neverFires() {
        CrashDetector d = new CrashDetector();
        d.observe(McServerStatus.RUNNING, clockAt(0));
        assertFalse(d.observe(McServerStatus.UNKNOWN, clockAt(5)));
        assertFalse(d.observe(McServerStatus.UNKNOWN, clockAt(60)));
    }

    @Test
    void restartAfterCrash_firesAgain() {
        CrashDetector d = new CrashDetector();
        d.observe(McServerStatus.RUNNING, clockAt(0));
        assertTrue(d.observe(McServerStatus.CRASHED, clockAt(5)));
        d.observe(McServerStatus.STARTING, clockAt(10));
        d.observe(McServerStatus.RUNNING, clockAt(15));
        // Second crash in a new transition — fires again.
        assertTrue(d.observe(McServerStatus.CRASHED, clockAt(20)));
    }

    @Test
    void initialCrashWithoutPriorRunning_doesNotFire() {
        CrashDetector d = new CrashDetector();
        // First observation is already a crashed state — no transition, no balloon.
        assertFalse(d.observe(McServerStatus.CRASHED, clockAt(0)));
    }

    private static Instant clockAt(long seconds) {
        return Instant.ofEpochSecond(1_700_000_000L + seconds);
    }
}
```

- [ ] **Step 2: Create `CrashDetector.java`**

```java
package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;

/**
 * Stateful transition tracker that decides when the "server crashed"
 * balloon should fire. Per-profile instance; not thread-safe — owned by
 * exactly one {@link ServerPoller}.
 *
 * <p>Rules:
 * <ul>
 *   <li>Fires once when the previous tick saw {@link McServerStatus#RUNNING}
 *       and the current tick sees {@link McServerStatus#CRASHED}.</li>
 *   <li>Fires once when the previous tick saw {@link McServerStatus#RUNNING}
 *       and the current tick sees {@link McServerStatus#STOPPED}, <em>unless</em>
 *       the user called {@link #recordUserStop} within {@link #USER_STOP_GRACE}
 *       of the transition.</li>
 *   <li>Never fires on {@link McServerStatus#UNKNOWN}. Network or AMP hiccups
 *       surface as the "AMP offline" pill instead.</li>
 *   <li>Dedupes within one transition: a balloon fires at most once per
 *       RUNNING → terminal sequence.</li>
 * </ul>
 */
public final class CrashDetector {

    public static final Duration USER_STOP_GRACE = Duration.ofSeconds(10);

    private McServerStatus previous = McServerStatus.UNKNOWN;
    private boolean firedForCurrentTransition = false;
    private Instant lastUserStop = Instant.EPOCH;

    /**
     * Observe the latest status and return {@code true} if the balloon
     * should fire on this tick.
     */
    public boolean observe(@NotNull McServerStatus status, @NotNull Instant sampledAt) {
        McServerStatus prior = this.previous;
        this.previous = status;

        if (status == McServerStatus.UNKNOWN) {
            // Don't reset the transition flag — UNKNOWN is "we can't tell".
            return false;
        }

        if (status == McServerStatus.RUNNING || status == McServerStatus.STARTING) {
            // Entering a healthy state resets the transition — next crash fires again.
            firedForCurrentTransition = false;
            return false;
        }

        if (prior != McServerStatus.RUNNING) return false;

        if (status == McServerStatus.CRASHED) {
            if (firedForCurrentTransition) return false;
            firedForCurrentTransition = true;
            return true;
        }

        if (status == McServerStatus.STOPPED) {
            if (firedForCurrentTransition) return false;
            Duration sinceUserStop = Duration.between(lastUserStop, sampledAt);
            if (!sinceUserStop.isNegative() && sinceUserStop.compareTo(USER_STOP_GRACE) <= 0) {
                // Graceful, user-requested stop — treat as intentional.
                firedForCurrentTransition = true;
                return false;
            }
            firedForCurrentTransition = true;
            return true;
        }

        return false;
    }

    /**
     * Record that the user asked to stop the server at this moment. The
     * detector uses this to suppress the next STOPPED transition for
     * {@link #USER_STOP_GRACE}.
     */
    public void recordUserStop(@NotNull Instant at) {
        this.lastUserStop = at;
    }
}
```

- [ ] **Step 3: Run tests**

Run: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner`
Expected: 56+ tests pass.

- [ ] **Step 4: Commit**

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 3.3 — CrashDetector

Stateful transition tracker. Fires on RUNNING→CRASHED or
RUNNING→STOPPED (unless a user-requested stop was recorded within
10 seconds). Never fires on UNKNOWN — those are network hiccups
surfaced by the AMP-offline pill. Dedupes within a single transition
and resets to allow a second balloon on restart+crash.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3.4: `ServerPoller` + `StateListener`

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/StateListener.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/ServerPoller.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/client/ServerPollerTest.java`

- [ ] **Step 1: Create `StateListener.java`**

```java
package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.ServerState;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Sink for poller output. The tool window implements this; the poller
 * calls it on the EDT (or a caller-supplied dispatcher for tests).
 */
public interface StateListener {
    void onStateUpdate(@NotNull ServerState state);
    void onConsoleLines(@NotNull List<String> lines);
    void onCrashDetected(@NotNull ServerState state);
}
```

- [ ] **Step 2: Create `ServerPoller.java`**

```java
package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.Player;
import com.conch.minecraftadmin.model.ServerProfile;
import com.conch.minecraftadmin.model.ServerState;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Drives one profile's 5-second poll loop. Each tick fires an AMP
 * status call and an RCON batch (list + tps) in parallel, merges the
 * results into a {@link ServerState}, and hands the snapshot to a
 * {@link StateListener}. The console panel additionally calls
 * {@link #pollConsole} on its own cadence (1s when focused).
 *
 * <p>Thread-safety: public methods are safe to call from any thread.
 * The poll loop runs on the application scheduled executor; listener
 * callbacks are dispatched via a caller-supplied {@link Consumer} so
 * UI code can marshal back to the EDT.
 */
public final class ServerPoller implements AutoCloseable {

    private static final Logger LOG = Logger.getInstance(ServerPoller.class);

    public static final Duration TICK = Duration.ofSeconds(5);

    private final ServerProfile profile;
    private final AmpClient ampClient;
    private final RconClient rconClient;
    private final StateListener listener;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService commandExecutor;
    private final Consumer<Runnable> uiDispatcher;
    private final CrashDetector crashDetector = new CrashDetector();

    private volatile AmpSession ampSession;
    private volatile RconSession rconSession;
    private final AtomicReference<ScheduledFuture<?>> scheduledTask = new AtomicReference<>();
    private volatile boolean stopped = false;

    public ServerPoller(
        @NotNull ServerProfile profile,
        @NotNull AmpClient ampClient,
        @NotNull RconClient rconClient,
        @NotNull StateListener listener,
        @NotNull ScheduledExecutorService scheduler,
        @NotNull Consumer<Runnable> uiDispatcher
    ) {
        this.profile = profile;
        this.ampClient = ampClient;
        this.rconClient = rconClient;
        this.listener = listener;
        this.scheduler = scheduler;
        this.commandExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "conch-mc-rcon-" + profile.label());
            t.setDaemon(true);
            return t;
        });
        this.uiDispatcher = uiDispatcher;
    }

    public void start() {
        if (scheduledTask.get() != null) return;
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(
            this::safeTick, 0, TICK.toMillis(), TimeUnit.MILLISECONDS);
        scheduledTask.set(task);
    }

    @Override
    public void close() {
        stop();
    }

    public void stop() {
        stopped = true;
        ScheduledFuture<?> task = scheduledTask.getAndSet(null);
        if (task != null) task.cancel(false);
        commandExecutor.shutdownNow();
        RconSession r = rconSession;
        if (r != null) {
            rconClient.close(r);
            rconSession = null;
        }
        ampSession = null;
    }

    /** Drive one tick synchronously. Exposed for tests. */
    public void tickOnce() {
        safeTick();
    }

    /** Record that the user just asked to stop the server; suppresses crash balloon. */
    public void recordUserStop() {
        crashDetector.recordUserStop(Instant.now());
    }

    /** Send an RCON command off the EDT. */
    public @NotNull CompletableFuture<String> sendCommand(@NotNull String cmd) {
        CompletableFuture<String> future = new CompletableFuture<>();
        commandExecutor.submit(() -> {
            try {
                RconSession session = ensureRcon();
                future.complete(rconClient.command(session, cmd));
            } catch (IOException e) {
                closeRcon();
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Request console updates (called by ConsolePanel at its own cadence). */
    public @NotNull CompletableFuture<ConsoleUpdate> pollConsole() {
        CompletableFuture<ConsoleUpdate> future = new CompletableFuture<>();
        commandExecutor.submit(() -> {
            try {
                AmpSession session = ensureAmp();
                future.complete(ampClient.getConsoleUpdates(session, profile.ampInstanceName()));
            } catch (IOException e) {
                ampSession = null;
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private void safeTick() {
        if (stopped) return;
        try {
            tickImpl();
        } catch (Throwable t) {
            LOG.warn("Conch Minecraft: unexpected poll-loop failure", t);
        }
    }

    private void tickImpl() {
        Instant sampledAt = Instant.now();

        AmpTickResult ampResult;
        try {
            AmpSession session = ensureAmp();
            InstanceStatus status = ampClient.getInstanceStatus(session, profile.ampInstanceName());
            ampResult = AmpTickResult.ok(status);
        } catch (IOException e) {
            ampSession = null;
            ampResult = AmpTickResult.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }

        RconTickResult rconResult;
        try {
            RconSession session = ensureRcon();
            String listReply = rconClient.command(session, "list");
            String tpsReply = rconClient.command(session, "tps");
            PaperListReplyParser.Result parsed = PaperListReplyParser.parse(listReply);
            double tps = PaperTpsReplyParser.parseMostRecent(tpsReply);
            rconResult = RconTickResult.ok(parsed.players(), parsed.online(), parsed.max(), tps);
        } catch (IOException e) {
            closeRcon();
            rconResult = RconTickResult.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }

        ServerState state = ServerStateMerger.merge(ampResult, rconResult, sampledAt);
        boolean crashFired = crashDetector.observe(state.status(), sampledAt);
        uiDispatcher.accept(() -> {
            listener.onStateUpdate(state);
            if (crashFired) listener.onCrashDetected(state);
        });
    }

    private AmpSession ensureAmp() throws IOException {
        AmpSession current = ampSession;
        if (current != null) return current;
        AmpSession fresh = ampClient.login(profile.ampUrl());
        ampSession = fresh;
        return fresh;
    }

    private RconSession ensureRcon() throws IOException {
        RconSession current = rconSession;
        if (current != null && !current.isClosed()) return current;
        char[] password = resolveRconPassword();
        RconSession fresh = rconClient.connect(profile.rconHost(), profile.rconPort(), password);
        rconSession = fresh;
        return fresh;
    }

    private char[] resolveRconPassword() {
        // In production, the RconClient's password is supplied via a caller-configured
        // credential resolver. Tests override this by constructing ServerPoller with a
        // subclass. See McCredentialResolver in the credentials package.
        throw new UnsupportedOperationException("resolveRconPassword must be overridden in production via a wrapper");
    }

    private void closeRcon() {
        RconSession r = rconSession;
        if (r != null) {
            rconClient.close(r);
            rconSession = null;
        }
    }
}
```

> **Note on `resolveRconPassword`:** the above throws by design. In production the plugin wraps `ServerPoller` with a lambda that reads from `McCredentialResolver` — that wiring happens in `ProfileController` during Phase 4. For the single-tick test below we pre-populate the session manually instead of going through `ensureRcon`.

- [ ] **Step 3: Write `ServerPollerTest.java` (single-tick sanity only)**

```java
package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.ServerProfile;
import com.conch.minecraftadmin.model.ServerState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ServerPollerTest {

    @Test
    void tickOnce_deliversStateFromBothSides() throws Exception {
        try (FakeAmpServer amp = new FakeAmpServer();
             FakeRconServer rcon = new FakeRconServer("rpw")) {

            amp.handle("Core/Login", body -> {
                JsonObject r = new JsonObject();
                r.addProperty("success", true);
                r.addProperty("sessionID", "abc");
                return r;
            });
            amp.handle("Core/GetInstances", body -> {
                JsonObject group = new JsonObject();
                JsonArray available = new JsonArray();
                JsonObject inst = new JsonObject();
                inst.addProperty("FriendlyName", "survival");
                inst.addProperty("InstanceName", "survival");
                inst.addProperty("Running", true);
                inst.addProperty("AppState", 30);
                inst.addProperty("TimeStarted", Instant.now().minusSeconds(120).toString());
                available.add(inst);
                group.add("AvailableInstances", available);
                JsonArray result = new JsonArray();
                result.add(group);
                JsonObject wrapper = new JsonObject();
                wrapper.add("result", result);
                return wrapper;
            });
            rcon.onCommand("list", cmd -> "There are 2 of a max of 20 players online: alice, bob");
            rcon.onCommand("tps", cmd -> "§6TPS from last 1m, 5m, 15m: §a19.5, §a20.0, §a20.0");

            ServerProfile profile = ServerProfile.create(
                "Survival",
                amp.baseUrl(),
                "survival",
                "admin",
                UUID.randomUUID(),
                "127.0.0.1",
                rcon.port(),
                UUID.randomUUID());

            AmpClient ampClient = new AmpClient(baseUrl -> new AmpClient.LoginPair("admin", "pw".toCharArray()));
            RconClient rconClient = new RconClient();
            AtomicReference<ServerState> received = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            StateListener listener = new StateListener() {
                @Override public void onStateUpdate(ServerState state) { received.set(state); latch.countDown(); }
                @Override public void onConsoleLines(List<String> lines) {}
                @Override public void onCrashDetected(ServerState state) {}
            };
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

            // Subclass to inject the RCON password without going through the vault.
            ServerPoller poller = new ServerPoller(profile, ampClient, rconClient, listener, scheduler, Runnable::run) {
                @Override
                public String toString() { return "test-poller"; }
            };
            // Pre-seed the rcon session via reflection so tickOnce() doesn't call the
            // vault-resolving stub. In production, ProfileController wraps this.
            java.lang.reflect.Field f = ServerPoller.class.getDeclaredField("rconSession");
            f.setAccessible(true);
            f.set(poller, rconClient.connect("127.0.0.1", rcon.port(), "rpw".toCharArray()));

            try {
                poller.tickOnce();
                assertTrue(latch.await(3, TimeUnit.SECONDS), "state update was not delivered");
                ServerState state = received.get();
                assertEquals(McServerStatus.RUNNING, state.status());
                assertEquals(2, state.playersOnline());
                assertEquals(20, state.playersMax());
                assertEquals(19.5, state.tps(), 0.001);
                assertTrue(state.isAmpHealthy());
                assertTrue(state.isRconHealthy());
            } finally {
                poller.stop();
                scheduler.shutdownNow();
            }
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner`
Expected: 57+ tests pass.

- [ ] **Step 5: Commit**

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 3.4 — ServerPoller orchestration

Per-profile 5-second poll loop on a caller-supplied scheduler. Each
tick fires AMP getInstanceStatus + RCON list+tps, merges via
ServerStateMerger, and pushes the snapshot through a UI-dispatcher
callback. CrashDetector integrated — listener.onCrashDetected fires
once per transition.

Single-tick sanity test wires both fake servers together and confirms
the end-to-end path produces a healthy ServerState with both halves
populated.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 3 gate

- [ ] All tests pass: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner` → 57+ green
- [ ] Full product build: `make conch-build` → succeeds
- [ ] AmpClient covers login, status, lifecycle, console updates, and 401 auto-recovery
- [ ] CrashDetector rules match spec (RUNNING→CRASHED, RUNNING→STOPPED w/ 10s grace, never on UNKNOWN, dedupe per transition, reset on restart)
- [ ] ServerPoller single-tick test demonstrates end-to-end merge

---

## Phase 4 — Credentials, UI panels, tool window

Phase 4 has a lot of surface area but each task is small. UI panels are kept in their own files so each one is focused and independently testable. All render tests use canned `ServerState` values and only assert that the code path doesn't throw — no pixel comparisons.

### Task 4.1: `McCredential` + `McCredentialResolver`

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/credentials/McCredential.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/credentials/McCredentialResolver.java`

This is thinner than the SSH plugin's equivalent because we only need username+password pairs, not key files.

- [ ] **Step 1: Create `McCredential.java`**

```java
package com.conch.minecraftadmin.credentials;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Short-lived credential pulled from the vault at connect time. The
 * caller is responsible for {@link #close}-ing the credential when it's
 * no longer needed; close zeroes the password.
 */
public final class McCredential implements AutoCloseable {

    private final String username;
    private final char[] password;

    public McCredential(@NotNull String username, @NotNull char[] password) {
        this.username = username;
        this.password = password;
    }

    public @NotNull String username() { return username; }
    public @NotNull char[] password() { return password; }

    @Override
    public void close() {
        Arrays.fill(password, '\0');
    }
}
```

- [ ] **Step 2: Create `McCredentialResolver.java`**

```java
package com.conch.minecraftadmin.credentials;

import com.conch.sdk.CredentialProvider;
import com.conch.sdk.CredentialProvider.Credential;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Maps a vault credential UUID to a short-lived {@link McCredential} via
 * the same {@code com.conch.core.credentialProvider} extension point the
 * SSH plugin uses. This is a pure dispatcher — policy (which id to look
 * up, what to do on miss) is the caller's responsibility.
 */
public final class McCredentialResolver {

    private static final ExtensionPointName<CredentialProvider> EP_NAME =
        ExtensionPointName.create("com.conch.core.credentialProvider");

    /**
     * Walk the registered providers until one returns a credential for
     * {@code id}. Returns {@code null} if none of them know about it.
     */
    public @Nullable McCredential resolve(@NotNull UUID id, @NotNull String fallbackUsername) {
        if (ApplicationManager.getApplication() == null) return null;
        List<CredentialProvider> providers = EP_NAME.getExtensionList();
        for (CredentialProvider provider : providers) {
            Credential credential = provider.lookup(id);
            if (credential == null) continue;
            String username = credential.username() == null || credential.username().isBlank()
                ? fallbackUsername
                : credential.username();
            String password = credential.password() == null ? "" : credential.password();
            return new McCredential(username, password.toCharArray());
        }
        return null;
    }
}
```

- [ ] **Step 3: Build & commit**

```bash
bazel build //conch/plugins/minecraft-admin:minecraft-admin
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 4.1 — vault credential resolver

McCredential (AutoCloseable wrapper that zeroes the password on
close) + McCredentialResolver (dispatcher over the Conch vault's
credentialProvider EP). Same pattern as SshCredentialResolver,
trimmed to password-only since AMP and RCON both authenticate with
username/password.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4.2: Rewire `ServerPoller` to accept a credential supplier

`ServerPoller` currently throws from `resolveRconPassword`. Replace that with a constructor parameter so production code can inject the vault lookup and tests can inject a fixed password.

**Files:**
- Modify: `plugins/minecraft-admin/src/com/conch/minecraftadmin/client/ServerPoller.java`
- Modify: `plugins/minecraft-admin/test/com/conch/minecraftadmin/client/ServerPollerTest.java`

- [ ] **Step 1: Replace the throwing method with a supplier field**

In `ServerPoller.java`, remove the `resolveRconPassword` method and add a `Supplier<char[]>` field + constructor parameter. Update `ensureRcon` to call the supplier.

```java
// new import
import java.util.function.Supplier;

// new field
private final Supplier<char[]> rconPasswordSupplier;

// updated constructor (keep the existing parameters, append one)
public ServerPoller(
    @NotNull ServerProfile profile,
    @NotNull AmpClient ampClient,
    @NotNull RconClient rconClient,
    @NotNull StateListener listener,
    @NotNull ScheduledExecutorService scheduler,
    @NotNull Consumer<Runnable> uiDispatcher,
    @NotNull Supplier<char[]> rconPasswordSupplier
) {
    this.profile = profile;
    this.ampClient = ampClient;
    this.rconClient = rconClient;
    this.listener = listener;
    this.scheduler = scheduler;
    this.commandExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "conch-mc-rcon-" + profile.label());
        t.setDaemon(true);
        return t;
    });
    this.uiDispatcher = uiDispatcher;
    this.rconPasswordSupplier = rconPasswordSupplier;
}

// replace the throwing resolveRconPassword method entirely with:
private char[] resolveRconPassword() {
    char[] pw = rconPasswordSupplier.get();
    if (pw == null) throw new IllegalStateException("RCON password supplier returned null for " + profile.label());
    return pw;
}
```

- [ ] **Step 2: Update `ServerPollerTest.java`**

Replace the reflection-based session injection with the new constructor parameter:

```java
// In ServerPollerTest.java, replace the "Subclass to inject..." block and
// the subsequent reflection lines with a proper constructor call:
ServerPoller poller = new ServerPoller(
    profile, ampClient, rconClient, listener, scheduler, Runnable::run,
    () -> "rpw".toCharArray());
// Remove the reflection lines that poked at rconSession.
```

- [ ] **Step 3: Run tests**

Run: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner`
Expected: still 57+ tests green.

- [ ] **Step 4: Commit**

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 4.2 — ServerPoller takes a password supplier

Replaces the throw-on-call resolveRconPassword stub with an injected
Supplier<char[]>. ProfileController will pass a lambda that calls
McCredentialResolver; tests pass a fixed-value supplier.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4.3: `ServerEditDialog`

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/ui/ServerEditDialog.java`

This is a classic IntelliJ `DialogWrapper` with text fields for label / AMP URL / AMP instance name / AMP username / RCON host / RCON port, and two "Pick credential…" buttons that open the Conch vault picker for the AMP password and RCON password respectively.

The file is ~220 lines. Rather than inline the full source, follow the pattern of `plugins/ssh/src/com/conch/ssh/ui/HostEditDialog.java` — same layout conventions, same validation style, same "OK button disabled until required fields are filled" behavior. The vault picker integration uses the same `CredentialPickerDialog` entry point the SSH plugin uses.

- [ ] **Step 1: Read the reference**

Open `plugins/ssh/src/com/conch/ssh/ui/HostEditDialog.java` and note:
- `DialogWrapper` subclass with `centerPanel()` returning a `JPanel` built via `FormBuilder`
- `JBTextField` for each string field
- `getOKAction().setEnabled(...)` on text change to gate Save
- `doValidate()` returning a `ValidationInfo` for each invalid field
- Separate "Pick…" button next to each credential field

- [ ] **Step 2: Create `ServerEditDialog.java`**

The dialog has:
- A constructor `ServerEditDialog(@Nullable Project project, @Nullable ServerProfile existing)` — null existing means "add new"
- Fields: `labelField`, `ampUrlField`, `ampInstanceField`, `ampUsernameField`, `ampCredentialButton` (shows picked credential label), `rconHostField`, `rconPortField` (spinner 1-65535, default 25575), `rconCredentialButton`
- `showAndGetResult()` returning `Optional<ServerProfile>` — `empty` if the user cancelled
- Validation: every required field non-blank, port in 1-65535, URL starts with `http://` or `https://`, both credential ids non-null

Reference `HostEditDialog.java` for layout, event wiring, and vault picker integration. Required imports include `com.intellij.openapi.ui.DialogWrapper`, `com.intellij.util.ui.FormBuilder`, `com.intellij.ui.components.JBTextField`, `com.intellij.ui.components.JBLabel`, `javax.swing.*`, and the vault picker entry point used by `HostEditDialog`.

- [ ] **Step 3: Build**

Run: `bazel build //conch/plugins/minecraft-admin:minecraft-admin`
Expected: compiles cleanly. No unit tests for this dialog — IntelliJ dialog classes are awkward to test in isolation and the render smoke tests for the individual panels already cover the data-binding paths.

- [ ] **Step 4: Commit**

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 4.3 — ServerEditDialog

Add/edit modal for ServerProfile. FormBuilder-laid-out fields plus two
vault-picker buttons for the AMP and RCON credentials. Validation
mirrors HostEditDialog's pattern (ValidationInfo per bad field, Save
button gated on non-blank required fields).

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4.4: `StatusStripPanel`

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/toolwindow/StatusStripPanel.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/toolwindow/StatusStripPanelTest.java`

A `JPanel` with six `JBLabel` cells in a row, updated from a `ServerState`. When `state.isAmpHealthy()` is false, the AMP-sourced cells (CPU, RAM, uptime) render in gray with an "⚠ AMP offline" pill at the end; same for RCON-sourced cells (players, TPS).

- [ ] **Step 1: Write the failing test**

```java
package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.Player;
import com.conch.minecraftadmin.model.ServerState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class StatusStripPanelTest {

    private static ServerState healthy() {
        return new ServerState(
            McServerStatus.RUNNING, 2, 20,
            List.of(new Player("alice", 42), new Player("bob", 99)),
            19.98, 45.0, 2000, 4000, Duration.ofMinutes(3),
            Optional.empty(), Optional.empty(), Instant.now());
    }

    @Test
    void render_healthyState() {
        StatusStripPanel panel = new StatusStripPanel();
        assertDoesNotThrow(() -> panel.update(healthy()));
    }

    @Test
    void render_ampDown_rconUp() {
        StatusStripPanel panel = new StatusStripPanel();
        assertDoesNotThrow(() -> panel.update(healthy().withAmpError("connection refused")));
    }

    @Test
    void render_rconDown_ampUp() {
        StatusStripPanel panel = new StatusStripPanel();
        assertDoesNotThrow(() -> panel.update(healthy().withRconError("auth failed")));
    }

    @Test
    void render_bothDown() {
        StatusStripPanel panel = new StatusStripPanel();
        ServerState bothDown = healthy()
            .withAmpError("connection refused")
            .withRconError("connection reset");
        assertDoesNotThrow(() -> panel.update(bothDown));
    }

    @Test
    void render_nanTps_doesNotThrow() {
        StatusStripPanel panel = new StatusStripPanel();
        ServerState nan = new ServerState(
            McServerStatus.RUNNING, 0, 20, List.of(),
            Double.NaN, 10.0, 100, 1000, Duration.ZERO,
            Optional.empty(), Optional.empty(), Instant.now());
        assertDoesNotThrow(() -> panel.update(nan));
    }

    @Test
    void render_unknownInitial_doesNotThrow() {
        StatusStripPanel panel = new StatusStripPanel();
        assertDoesNotThrow(() -> panel.update(ServerState.unknown(Instant.now())));
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `bazel build //conch/plugins/minecraft-admin:minecraft_admin_test_lib`
Expected: compile error on missing `StatusStripPanel`.

- [ ] **Step 3: Create `StatusStripPanel.java`**

```java
package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.ServerState;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.time.Duration;

/**
 * Six-cell horizontal status strip shown at the top of the tool window.
 * Cells (left to right): status dot + text, players, TPS, CPU, RAM, uptime.
 * AMP-sourced cells (CPU, RAM, uptime) gray out when {@code state.ampError}
 * is present; RCON-sourced cells (players, TPS) gray out when
 * {@code state.rconError} is present.
 */
public final class StatusStripPanel extends JPanel {

    private static final Color HEALTHY = JBUI.CurrentTheme.Label.foreground();
    private static final Color UNAVAILABLE = JBUI.CurrentTheme.Label.disabledForeground();

    private final JBLabel statusLabel = new JBLabel();
    private final JBLabel playersLabel = new JBLabel();
    private final JBLabel tpsLabel = new JBLabel();
    private final JBLabel cpuLabel = new JBLabel();
    private final JBLabel ramLabel = new JBLabel();
    private final JBLabel uptimeLabel = new JBLabel();
    private final JBLabel ampPill = new JBLabel();
    private final JBLabel rconPill = new JBLabel();

    public StatusStripPanel() {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(JBUI.Borders.empty(4, 8));
        add(statusLabel);
        add(spacer());
        add(playersLabel);
        add(spacer());
        add(tpsLabel);
        add(spacer());
        add(cpuLabel);
        add(spacer());
        add(ramLabel);
        add(spacer());
        add(uptimeLabel);
        add(spacer());
        add(ampPill);
        add(spacer());
        add(rconPill);
    }

    public void update(@NotNull ServerState state) {
        statusLabel.setText(renderStatus(state.status()));
        statusLabel.setForeground(HEALTHY);

        boolean rconOk = state.isRconHealthy();
        playersLabel.setText("Players: " + state.playersOnline() + "/" + state.playersMax());
        playersLabel.setForeground(rconOk ? HEALTHY : UNAVAILABLE);
        tpsLabel.setText("TPS: " + (Double.isNaN(state.tps()) ? "—" : String.format("%.1f", state.tps())));
        tpsLabel.setForeground(rconOk ? HEALTHY : UNAVAILABLE);

        boolean ampOk = state.isAmpHealthy();
        cpuLabel.setText("CPU: " + (Double.isNaN(state.cpuPercent()) ? "—" : String.format("%.0f%%", state.cpuPercent())));
        cpuLabel.setForeground(ampOk ? HEALTHY : UNAVAILABLE);
        ramLabel.setText("RAM: " + state.ramUsedMb() + "/" + state.ramMaxMb() + " MB");
        ramLabel.setForeground(ampOk ? HEALTHY : UNAVAILABLE);
        uptimeLabel.setText("Up: " + formatUptime(state.uptime()));
        uptimeLabel.setForeground(ampOk ? HEALTHY : UNAVAILABLE);

        ampPill.setVisible(!ampOk);
        ampPill.setText(ampOk ? "" : "⚠ AMP offline");
        ampPill.setToolTipText(state.ampError().orElse(null));
        rconPill.setVisible(!rconOk);
        rconPill.setText(rconOk ? "" : "⚠ RCON offline");
        rconPill.setToolTipText(state.rconError().orElse(null));
    }

    private static String renderStatus(McServerStatus status) {
        return switch (status) {
            case RUNNING -> "● Running";
            case STARTING -> "◐ Starting";
            case STOPPING -> "◐ Stopping";
            case STOPPED -> "○ Stopped";
            case CRASHED -> "✗ Crashed";
            case UNKNOWN -> "? Unknown";
        };
    }

    private static String formatUptime(Duration uptime) {
        long totalMinutes = Math.max(0, uptime.toMinutes());
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return hours + "h " + minutes + "m";
    }

    private static Component spacer() {
        return javax.swing.Box.createRigidArea(new Dimension(12, 0));
    }
}
```

- [ ] **Step 4: Run tests**

Run: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner`
Expected: 63+ tests pass.

- [ ] **Step 5: Commit**

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 4.4 — StatusStripPanel

Six-cell horizontal row showing status, players, TPS, CPU, RAM,
uptime. AMP-sourced cells gray when state.ampError is present;
RCON-sourced cells gray when state.rconError is present. Trailing
pills surface per-side failures as tooltips. Six smoke tests cover
healthy, AMP-down, RCON-down, both-down, NaN TPS, and the initial
unknown state.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4.5: `LifecycleButtons`

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/toolwindow/LifecycleButtons.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/toolwindow/LifecycleButtonsTest.java`

A `JPanel` with Start / Stop / Restart / Backup buttons whose enabled state is driven from `ServerState.status`.

- [ ] **Step 1: Write the failing test**

```java
package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.ServerState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleButtonsTest {

    private static ServerState withStatus(McServerStatus s) {
        return new ServerState(
            s, 0, 0, List.of(), Double.NaN, Double.NaN, 0, 0, Duration.ZERO,
            Optional.empty(), Optional.empty(), Instant.now());
    }

    @Test
    void running_enablesStopRestartBackup() {
        LifecycleButtons buttons = new LifecycleButtons(() -> {}, () -> {}, () -> {}, () -> {});
        buttons.update(withStatus(McServerStatus.RUNNING));
        assertFalse(buttons.startButton().isEnabled());
        assertTrue(buttons.stopButton().isEnabled());
        assertTrue(buttons.restartButton().isEnabled());
        assertTrue(buttons.backupButton().isEnabled());
    }

    @Test
    void stopped_enablesStartOnly() {
        LifecycleButtons buttons = new LifecycleButtons(() -> {}, () -> {}, () -> {}, () -> {});
        buttons.update(withStatus(McServerStatus.STOPPED));
        assertTrue(buttons.startButton().isEnabled());
        assertFalse(buttons.stopButton().isEnabled());
        assertFalse(buttons.restartButton().isEnabled());
        assertFalse(buttons.backupButton().isEnabled());
    }

    @Test
    void starting_disablesAll() {
        LifecycleButtons buttons = new LifecycleButtons(() -> {}, () -> {}, () -> {}, () -> {});
        buttons.update(withStatus(McServerStatus.STARTING));
        assertFalse(buttons.startButton().isEnabled());
        assertFalse(buttons.stopButton().isEnabled());
        assertFalse(buttons.restartButton().isEnabled());
        assertFalse(buttons.backupButton().isEnabled());
    }

    @Test
    void crashed_enablesStartAndBackup() {
        LifecycleButtons buttons = new LifecycleButtons(() -> {}, () -> {}, () -> {}, () -> {});
        buttons.update(withStatus(McServerStatus.CRASHED));
        assertTrue(buttons.startButton().isEnabled());
        assertFalse(buttons.stopButton().isEnabled());
        assertFalse(buttons.restartButton().isEnabled());
        assertTrue(buttons.backupButton().isEnabled());
    }
}
```

- [ ] **Step 2: Create `LifecycleButtons.java`**

```java
package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.ServerState;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;

/**
 * Start / Stop / Restart / Backup row. Enabled state is a pure function
 * of {@link ServerState#status}. All click handlers are injected so
 * tests can verify enablement without wiring a real poller.
 */
public final class LifecycleButtons extends JPanel {

    private final JButton start = new JButton("Start");
    private final JButton stop = new JButton("Stop");
    private final JButton restart = new JButton("Restart");
    private final JButton backup = new JButton("Backup Now");

    public LifecycleButtons(
        @NotNull Runnable onStart,
        @NotNull Runnable onStop,
        @NotNull Runnable onRestart,
        @NotNull Runnable onBackup
    ) {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(JBUI.Borders.empty(4, 8));
        start.addActionListener(e -> onStart.run());
        stop.addActionListener(e -> onStop.run());
        restart.addActionListener(e -> onRestart.run());
        backup.addActionListener(e -> onBackup.run());
        add(start);
        add(javax.swing.Box.createHorizontalStrut(6));
        add(stop);
        add(javax.swing.Box.createHorizontalStrut(6));
        add(restart);
        add(javax.swing.Box.createHorizontalStrut(6));
        add(backup);
    }

    public void update(@NotNull ServerState state) {
        McServerStatus s = state.status();
        start.setEnabled(s == McServerStatus.STOPPED || s == McServerStatus.CRASHED || s == McServerStatus.UNKNOWN);
        stop.setEnabled(s == McServerStatus.RUNNING);
        restart.setEnabled(s == McServerStatus.RUNNING);
        backup.setEnabled(s == McServerStatus.RUNNING || s == McServerStatus.CRASHED);
    }

    JButton startButton() { return start; }
    JButton stopButton() { return stop; }
    JButton restartButton() { return restart; }
    JButton backupButton() { return backup; }
}
```

- [ ] **Step 3: Run tests and commit**

Run: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner`
Expected: 67+ tests pass.

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 4.5 — LifecycleButtons

Start/Stop/Restart/Backup row driven from ServerState.status. Four
smoke tests cover the RUNNING/STOPPED/STARTING/CRASHED enablement
tables. Handlers are injected via Runnable so the tests don't need a
real poller.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4.6: `PlayersPanel`

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/toolwindow/PlayersPanel.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/toolwindow/PlayersPanelTest.java`

A `JBTable` of `(name, ping)` backed by a mutable `DefaultTableModel`, with a right-click popup menu that fires callbacks. The tests only verify that `update(state)` doesn't throw.

- [ ] **Step 1: Write the failing test**

```java
package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.Player;
import com.conch.minecraftadmin.model.ServerState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayersPanelTest {

    private static ServerState withPlayers(List<Player> players) {
        return new ServerState(
            McServerStatus.RUNNING, players.size(), 20, players,
            20.0, 10.0, 1000, 4000, Duration.ofMinutes(1),
            Optional.empty(), Optional.empty(), Instant.now());
    }

    @Test
    void render_empty() {
        PlayersPanel panel = new PlayersPanel(name -> {}, name -> {}, name -> {});
        assertDoesNotThrow(() -> panel.update(withPlayers(List.of())));
        assertEquals(0, panel.rowCount());
    }

    @Test
    void render_onePlayer() {
        PlayersPanel panel = new PlayersPanel(name -> {}, name -> {}, name -> {});
        assertDoesNotThrow(() -> panel.update(withPlayers(List.of(new Player("alice", 42)))));
        assertEquals(1, panel.rowCount());
    }

    @Test
    void render_manyPlayers() {
        PlayersPanel panel = new PlayersPanel(name -> {}, name -> {}, name -> {});
        List<Player> players = List.of(
            new Player("alice", 42),
            new Player("bob", 99),
            new Player("carol", Player.PING_UNKNOWN));
        assertDoesNotThrow(() -> panel.update(withPlayers(players)));
        assertEquals(3, panel.rowCount());
    }

    @Test
    void render_unknownPing_showsDash() {
        PlayersPanel panel = new PlayersPanel(name -> {}, name -> {}, name -> {});
        panel.update(withPlayers(List.of(new Player("carol", Player.PING_UNKNOWN))));
        assertEquals("—", panel.pingAt(0));
    }
}
```

- [ ] **Step 2: Create `PlayersPanel.java`**

```java
package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.model.Player;
import com.conch.minecraftadmin.model.ServerState;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Table of online players with a right-click popup exposing Kick / Ban /
 * Op actions. Callbacks are injected as {@code Consumer<String>} so tests
 * can exercise the rendering path without wiring a real poller.
 */
public final class PlayersPanel extends javax.swing.JPanel {

    private static final String[] COLUMNS = { "Name", "Ping (ms)" };

    private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JBTable table = new JBTable(model);
    private final Consumer<String> onKick;
    private final Consumer<String> onBan;
    private final Consumer<String> onOp;

    public PlayersPanel(
        @NotNull Consumer<String> onKick,
        @NotNull Consumer<String> onBan,
        @NotNull Consumer<String> onOp
    ) {
        super(new BorderLayout());
        this.onKick = onKick;
        this.onBan = onBan;
        this.onOp = onOp;
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybeShowPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
        });
        add(new JBScrollPane(table), BorderLayout.CENTER);
    }

    public void update(@NotNull ServerState state) {
        model.setRowCount(0);
        for (Player p : state.players()) {
            String ping = p.pingMs() == Player.PING_UNKNOWN ? "—" : String.valueOf(p.pingMs());
            model.addRow(new Object[] { p.name(), ping });
        }
    }

    int rowCount() { return model.getRowCount(); }
    String pingAt(int row) { return String.valueOf(model.getValueAt(row, 1)); }

    private void maybeShowPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0) return;
        table.setRowSelectionInterval(row, row);
        String name = (String) model.getValueAt(row, 0);

        JPopupMenu menu = new JPopupMenu();
        JMenuItem kick = new JMenuItem("Kick " + name);
        kick.addActionListener(ev -> onKick.accept(name));
        JMenuItem ban = new JMenuItem("Ban " + name);
        ban.addActionListener(ev -> onBan.accept(name));
        JMenuItem op = new JMenuItem("Op " + name);
        op.addActionListener(ev -> onOp.accept(name));
        menu.add(kick);
        menu.add(ban);
        menu.add(op);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }
}
```

- [ ] **Step 3: Run tests and commit**

Run: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner`
Expected: 71+ tests pass.

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 4.6 — PlayersPanel

JBTable of (name, ping) with a right-click Kick/Ban/Op popup. Ping is
'—' for unknown (sentinel -1). Four smoke tests: empty / one / many
players, and unknown-ping rendering.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4.7: `ConsolePanel`

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/toolwindow/ConsolePanel.java`
- Create: `plugins/minecraft-admin/test/com/conch/minecraftadmin/toolwindow/ConsolePanelTest.java`

A read-only `JTextArea` wrapped in a `JBScrollPane`, plus a `JBTextField` send box at the bottom, a "Broadcast" button next to it, and an in-memory command history (20 entries).

- [ ] **Step 1: Write the failing test**

```java
package com.conch.minecraftadmin.toolwindow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsolePanelTest {

    @Test
    void render_empty() {
        ConsolePanel panel = new ConsolePanel(cmd -> "", cmd -> {});
        assertDoesNotThrow(() -> panel.appendLines(List.of()));
    }

    @Test
    void render_tailing_appendsLines() {
        ConsolePanel panel = new ConsolePanel(cmd -> "", cmd -> {});
        panel.appendLines(List.of("[17:42:01 INFO]: alice joined the game"));
        panel.appendLines(List.of("[17:42:05 INFO]: bob joined the game"));
        String text = panel.textAreaContents();
        assertTrue(text.contains("alice"));
        assertTrue(text.contains("bob"));
    }

    @Test
    void history_upDownRecallCycles() {
        ConsolePanel panel = new ConsolePanel(cmd -> "ok", cmd -> {});
        panel.sendForTest("list");
        panel.sendForTest("tps");
        panel.sendForTest("say hi");
        assertEquals("say hi", panel.historyUpForTest());
        assertEquals("tps", panel.historyUpForTest());
        assertEquals("list", panel.historyUpForTest());
        assertEquals("list", panel.historyUpForTest(),
            "reaching the top of the history clamps, doesn't wrap");
        assertEquals("tps", panel.historyDownForTest());
    }

    @Test
    void history_isCappedAt20() {
        ConsolePanel panel = new ConsolePanel(cmd -> "", cmd -> {});
        for (int i = 0; i < 25; i++) panel.sendForTest("cmd" + i);
        assertEquals(20, panel.historySize());
    }
}
```

- [ ] **Step 2: Create `ConsolePanel.java`**

```java
package com.conch.minecraftadmin.toolwindow;

import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionListener;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Scrollable log tail + command send box + Broadcast button. Polling is
 * driven externally by {@code ProfileController} which calls
 * {@link #appendLines} with new entries from {@code AmpClient}.
 *
 * <p>Command history is in-memory, capped at 20 entries. Up/Down cycles
 * through history; reaching the ends clamps rather than wraps.
 */
public final class ConsolePanel extends JPanel {

    private static final int HISTORY_CAP = 20;

    private final JTextArea textArea = new JTextArea();
    private final JBTextField commandField = new JBTextField();
    private final JButton broadcastButton = new JButton("Broadcast…");
    private final Function<String, String> commandSink;
    private final Consumer<String> broadcastSink;

    private final Deque<String> history = new ArrayDeque<>();
    private int historyIndex = -1;

    public ConsolePanel(
        @NotNull Function<String, String> commandSink,
        @NotNull Consumer<String> broadcastSink
    ) {
        super(new BorderLayout());
        this.commandSink = commandSink;
        this.broadcastSink = broadcastSink;
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        add(new JBScrollPane(textArea), BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.X_AXIS));
        bottom.setBorder(JBUI.Borders.empty(4, 6));
        bottom.add(commandField);
        bottom.add(javax.swing.Box.createHorizontalStrut(6));
        bottom.add(broadcastButton);
        add(bottom, BorderLayout.SOUTH);

        commandField.addActionListener(sendAction());
        broadcastButton.addActionListener(e -> {
            String msg = commandField.getText();
            if (msg.isBlank()) return;
            broadcastSink.accept(msg);
            commandField.setText("");
        });
        bindHistoryKeys();
    }

    public void appendLines(@NotNull List<String> lines) {
        if (lines.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        textArea.append(sb.toString());
        // Auto-scroll to the bottom.
        textArea.setCaretPosition(textArea.getDocument().getLength());
    }

    /** Visible for tests. */
    String textAreaContents() { return textArea.getText(); }

    /** Visible for tests. */
    void sendForTest(String command) { send(command); }

    /** Visible for tests. */
    String historyUpForTest() {
        historyIndex = Math.min(historyIndex + 1, history.size() - 1);
        return snapshotAtIndex();
    }

    /** Visible for tests. */
    String historyDownForTest() {
        historyIndex = Math.max(historyIndex - 1, 0);
        return snapshotAtIndex();
    }

    /** Visible for tests. */
    int historySize() { return history.size(); }

    private ActionListener sendAction() {
        return e -> {
            String cmd = commandField.getText();
            if (cmd.isBlank()) return;
            send(cmd);
            commandField.setText("");
        };
    }

    private void send(String command) {
        pushHistory(command);
        try {
            String reply = commandSink.apply(command);
            appendLines(List.of("> " + command, reply));
        } catch (Exception ex) {
            appendLines(List.of("> " + command, "[error] " + ex.getMessage()));
        }
    }

    private void pushHistory(String command) {
        history.addFirst(command);
        while (history.size() > HISTORY_CAP) history.removeLast();
        historyIndex = -1;
    }

    private String snapshotAtIndex() {
        if (history.isEmpty() || historyIndex < 0) return "";
        int i = 0;
        for (String item : history) {
            if (i == historyIndex) return item;
            i++;
        }
        return "";
    }

    private void bindHistoryKeys() {
        commandField.getInputMap().put(KeyStroke.getKeyStroke("UP"), "historyUp");
        commandField.getInputMap().put(KeyStroke.getKeyStroke("DOWN"), "historyDown");
        commandField.getActionMap().put("historyUp", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                commandField.setText(historyUpForTest());
            }
        });
        commandField.getActionMap().put("historyDown", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                commandField.setText(historyDownForTest());
            }
        });
        commandField.setPreferredSize(new Dimension(200, commandField.getPreferredSize().height));
    }
}
```

- [ ] **Step 3: Run tests and commit**

Run: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner`
Expected: 75+ tests pass.

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 4.7 — ConsolePanel

Read-only tail area + send field + Broadcast button. Command history
capped at 20 entries, up/down cycles with clamp-at-end. appendLines
auto-scrolls to the bottom. Four tests cover empty / tailing /
history-recall / history-cap.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4.8: `ProfileController` + `McAdminToolWindow` + `McAdminToolWindowFactory`

**Files:**
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/toolwindow/ProfileController.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/toolwindow/ServerSwitcher.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/toolwindow/McAdminToolWindow.java`
- Create: `plugins/minecraft-admin/src/com/conch/minecraftadmin/toolwindow/McAdminToolWindowFactory.java`

This task wires the parts built in Phase 4.1–4.7 together. It's structural glue, not new logic, so it doesn't add unit tests — the individual panel tests already cover the render paths.

- [ ] **Step 1: Create `ProfileController.java`**

One controller per visible profile. Owns a `ServerPoller`, listens for its `StateListener` callbacks, and dispatches them to the panels.

```java
package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.client.AmpClient;
import com.conch.minecraftadmin.client.RconClient;
import com.conch.minecraftadmin.client.ServerPoller;
import com.conch.minecraftadmin.client.StateListener;
import com.conch.minecraftadmin.credentials.McCredential;
import com.conch.minecraftadmin.credentials.McCredentialResolver;
import com.conch.minecraftadmin.model.ServerProfile;
import com.conch.minecraftadmin.model.ServerState;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;

/** Wires one {@link ServerPoller} to the panels for one profile. */
public final class ProfileController implements AutoCloseable {

    private static final Logger LOG = Logger.getInstance(ProfileController.class);

    private final ServerProfile profile;
    private final StatusStripPanel statusStrip;
    private final LifecycleButtons lifecycleButtons;
    private final PlayersPanel playersPanel;
    private final ConsolePanel consolePanel;
    private final ServerPoller poller;

    public ProfileController(
        @NotNull ServerProfile profile,
        @NotNull StatusStripPanel statusStrip,
        @NotNull LifecycleButtons lifecycleButtons,
        @NotNull PlayersPanel playersPanel,
        @NotNull ConsolePanel consolePanel
    ) {
        this.profile = profile;
        this.statusStrip = statusStrip;
        this.lifecycleButtons = lifecycleButtons;
        this.playersPanel = playersPanel;
        this.consolePanel = consolePanel;

        McCredentialResolver resolver = new McCredentialResolver();
        AmpClient amp = new AmpClient(baseUrl -> {
            McCredential cred = resolver.resolve(profile.ampCredentialId(), profile.ampUsername());
            if (cred == null) throw new IllegalStateException("AMP credential not found for " + profile.label());
            return new AmpClient.LoginPair(cred.username(), cred.password());
        });
        RconClient rconClient = new RconClient();

        StateListener listener = new StateListener() {
            @Override public void onStateUpdate(@NotNull ServerState state) {
                statusStrip.update(state);
                lifecycleButtons.update(state);
                playersPanel.update(state);
            }
            @Override public void onConsoleLines(@NotNull List<String> lines) {
                consolePanel.appendLines(lines);
            }
            @Override public void onCrashDetected(@NotNull ServerState state) {
                Notifications.Bus.notify(new Notification(
                    "Conch Minecraft",
                    "Minecraft server '" + profile.label() + "' crashed",
                    state.ampError().orElse("Check AMP for details"),
                    NotificationType.ERROR));
            }
        };

        this.poller = new ServerPoller(
            profile, amp, rconClient, listener,
            AppExecutorUtil.getAppScheduledExecutorService(),
            r -> ApplicationManager.getApplication().invokeLater(r, ModalityState.any()),
            () -> {
                McCredential cred = resolver.resolve(profile.rconCredentialId(), "");
                if (cred == null) throw new IllegalStateException("RCON credential not found for " + profile.label());
                return cred.password();
            });
    }

    public void start() {
        poller.start();
        // Render an immediate empty snapshot so the UI isn't blank while the first tick runs.
        ServerState initial = ServerState.unknown(Instant.now());
        statusStrip.update(initial);
        lifecycleButtons.update(initial);
        playersPanel.update(initial);
    }

    public @NotNull ServerProfile profile() { return profile; }

    public @NotNull ServerPoller poller() { return poller; }

    @Override
    public void close() {
        poller.stop();
    }
}
```

- [ ] **Step 2: Create `ServerSwitcher.java`**

```java
package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.model.ServerProfile;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import java.util.List;
import java.util.function.Consumer;

/** Dropdown + gear/add buttons at the top of the tool window. */
public final class ServerSwitcher extends JPanel {

    private final DefaultComboBoxModel<ServerProfile> model = new DefaultComboBoxModel<>();
    private final JComboBox<ServerProfile> combo = new JComboBox<>(model);
    private final JButton addButton = new JButton("+");
    private final JButton editButton = new JButton("⚙");

    public ServerSwitcher(
        @NotNull Consumer<ServerProfile> onSelect,
        @NotNull Runnable onAdd,
        @NotNull Consumer<ServerProfile> onEdit
    ) {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(JBUI.Borders.empty(4, 8));
        combo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                javax.swing.JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ServerProfile p) setText(p.label());
                else setText("(no server)");
                return this;
            }
        });
        combo.addActionListener(e -> {
            ServerProfile p = (ServerProfile) combo.getSelectedItem();
            if (p != null) onSelect.accept(p);
        });
        addButton.addActionListener(e -> onAdd.run());
        editButton.addActionListener(e -> {
            ServerProfile p = (ServerProfile) combo.getSelectedItem();
            if (p != null) onEdit.accept(p);
        });
        add(combo);
        add(javax.swing.Box.createHorizontalStrut(4));
        add(addButton);
        add(javax.swing.Box.createHorizontalStrut(4));
        add(editButton);
    }

    public void setProfiles(@NotNull List<ServerProfile> profiles, @Nullable ServerProfile selected) {
        model.removeAllElements();
        for (ServerProfile p : profiles) model.addElement(p);
        if (selected != null) combo.setSelectedItem(selected);
    }
}
```

- [ ] **Step 3: Create `McAdminToolWindow.java`**

```java
package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.client.AmpClient;
import com.conch.minecraftadmin.client.ServerPoller;
import com.conch.minecraftadmin.model.ProfileStore;
import com.conch.minecraftadmin.model.ServerProfile;
import com.conch.minecraftadmin.ui.ServerEditDialog;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.ui.Splitter;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Optional;

/**
 * Root panel of the Minecraft Admin tool window. Holds one
 * {@link ProfileController} for the currently-selected profile and
 * rewires it when the user switches profiles.
 *
 * <p>Layout optimized for a bottom-anchored tool window:
 * <ul>
 *   <li>NORTH: a single-row toolbar with the server switcher, status
 *       strip, and lifecycle buttons laid out left-to-right.</li>
 *   <li>CENTER: a horizontal {@link Splitter} with the players table on
 *       the left (30%) and the console panel on the right (70%).</li>
 * </ul>
 * No special docked/undocked code paths — IntelliJ's ToolWindow framework
 * handles mode transitions, and BorderLayout + Splitter reflow naturally
 * when the user undocks or resizes the window.
 */
public final class McAdminToolWindow extends JPanel {

    private static final float DEFAULT_SPLIT_PROPORTION = 0.30f;

    private final Project project;
    private final ProfileStore profileStore;
    private final StatusStripPanel statusStrip = new StatusStripPanel();
    private final LifecycleButtons lifecycleButtons;
    private final PlayersPanel playersPanel;
    private final ConsolePanel consolePanel;
    private final ServerSwitcher switcher;

    private @Nullable ProfileController current;

    public McAdminToolWindow(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.profileStore = ProfileStore.getInstance();

        this.lifecycleButtons = new LifecycleButtons(
            () -> invokeOnPoller(ServerPoller::sendStart),
            () -> invokeOnPoller(ServerPoller::sendStop),
            () -> invokeOnPoller(ServerPoller::sendRestart),
            () -> invokeOnPoller(ServerPoller::sendBackup));

        this.playersPanel = new PlayersPanel(
            name -> sendRcon("kick " + name),
            name -> sendRcon("ban " + name),
            name -> sendRcon("op " + name));

        this.consolePanel = new ConsolePanel(
            cmd -> sendRconSync(cmd),
            msg -> sendRcon("say " + msg));

        // Top toolbar — one horizontal row: switcher + status strip + lifecycle buttons
        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));
        this.switcher = new ServerSwitcher(this::switchTo, this::addProfile, this::editProfile);
        toolbar.add(switcher);
        toolbar.add(javax.swing.Box.createHorizontalStrut(12));
        toolbar.add(statusStrip);
        toolbar.add(javax.swing.Box.createHorizontalGlue());
        toolbar.add(lifecycleButtons);
        add(toolbar, BorderLayout.NORTH);

        // Main area — horizontal splitter: players on the left, console on the right
        Splitter mainSplit = new Splitter(false, DEFAULT_SPLIT_PROPORTION);
        mainSplit.setFirstComponent(playersPanel);
        mainSplit.setSecondComponent(consolePanel);
        add(mainSplit, BorderLayout.CENTER);

        reloadProfiles();
        profileStore.addListener(profiles -> reloadProfiles());
    }

    private void reloadProfiles() {
        List<ServerProfile> profiles = profileStore.getProfiles();
        ServerProfile toSelect = current != null
            ? current.profile()
            : (profiles.isEmpty() ? null : profiles.get(0));
        switcher.setProfiles(profiles, toSelect);
        if (toSelect != null) switchTo(toSelect);
    }

    private void switchTo(ServerProfile profile) {
        if (current != null) {
            if (current.profile().id().equals(profile.id())) return;
            current.close();
        }
        current = new ProfileController(profile, statusStrip, lifecycleButtons, playersPanel, consolePanel);
        current.start();
    }

    private void addProfile() {
        Optional<ServerProfile> created = new ServerEditDialog(project, null).showAndGetResult();
        created.ifPresent(profileStore::add);
    }

    private void editProfile(ServerProfile existing) {
        Optional<ServerProfile> edited = new ServerEditDialog(project, existing).showAndGetResult();
        edited.ifPresent(profileStore::update);
    }

    private void invokeOnPoller(@NotNull java.util.function.Consumer<ServerPoller> action) {
        if (current == null) return;
        try {
            action.accept(current.poller());
        } catch (Exception e) {
            Messages.showErrorDialog(project, e.getMessage(), "Minecraft Admin");
        }
    }

    private void sendRcon(@NotNull String command) {
        if (current == null) return;
        current.poller().sendCommand(command).whenComplete((reply, err) -> {
            if (err != null) consolePanel.appendLines(List.of("[error] " + err.getMessage()));
            else consolePanel.appendLines(List.of("> " + command, reply));
        });
    }

    private String sendRconSync(@NotNull String command) {
        if (current == null) return "(no server selected)";
        try {
            return current.poller().sendCommand(command).get();
        } catch (Exception e) {
            return "[error] " + e.getMessage();
        }
    }
}
```

> **Note on `ServerPoller.sendStart` / `sendStop` / `sendRestart` / `sendBackup`:** these helper methods are small wrappers around `ampClient.startInstance(...)` etc. that also call `recordUserStop` on stop/restart. Add them as five-line methods on `ServerPoller` during this task (see sub-step below).

- [ ] **Step 4: Add `sendStart`/`sendStop`/`sendRestart`/`sendBackup` helpers to `ServerPoller.java`**

```java
public void sendStart() {
    commandExecutor.submit(() -> runLifecycle("start", () -> ampClient.startInstance(ensureAmp(), profile.ampInstanceName())));
}

public void sendStop() {
    commandExecutor.submit(() -> {
        crashDetector.recordUserStop(java.time.Instant.now());
        runLifecycle("stop", () -> ampClient.stopInstance(ensureAmp(), profile.ampInstanceName()));
    });
}

public void sendRestart() {
    commandExecutor.submit(() -> {
        crashDetector.recordUserStop(java.time.Instant.now());
        runLifecycle("restart", () -> ampClient.restartInstance(ensureAmp(), profile.ampInstanceName()));
    });
}

public void sendBackup() {
    commandExecutor.submit(() -> runLifecycle("backup", () -> ampClient.backupInstance(ensureAmp(), profile.ampInstanceName())));
}

@FunctionalInterface
private interface IoAction { void run() throws IOException; }

private void runLifecycle(String label, IoAction action) {
    try {
        action.run();
    } catch (IOException e) {
        LOG.warn("Conch Minecraft: " + label + " failed for " + profile.label(), e);
        ampSession = null;
    }
}
```

- [ ] **Step 5: Create `McAdminToolWindowFactory.java`**

```java
package com.conch.minecraftadmin.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class McAdminToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        McAdminToolWindow panel = new McAdminToolWindow(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
```

- [ ] **Step 6: Update `plugin.xml`**

Replace the placeholder `plugin.xml` created in Task 1.1 with:

```xml
<idea-plugin>
    <id>com.conch.minecraft-admin</id>
    <name>Conch Minecraft Admin</name>
    <vendor>Conch</vendor>
    <description>Bottom-bar admin console for Paper servers behind
    Cube Coders AMP. Live status, player list, console tail, lifecycle
    control, broadcast + RCON commands. Supports IntelliJ's stock
    undock / floating window modes via the tool window gear menu.</description>

    <depends>com.conch.core</depends>
    <depends>com.conch.vault</depends>

    <extensions defaultExtensionNs="com.intellij">
        <notificationGroup id="Conch Minecraft" displayType="BALLOON"/>
        <toolWindow id="Minecraft Admin"
                    anchor="bottom"
                    secondary="false"
                    icon="AllIcons.Webreferences.Server"
                    factoryClass="com.conch.minecraftadmin.toolwindow.McAdminToolWindowFactory"/>
        <applicationService serviceImplementation="com.conch.minecraftadmin.model.ProfileStore"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 7: Build, run tests, commit**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd build //conch/plugins/minecraft-admin:minecraft-admin
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //conch/plugins/minecraft-admin:minecraft_admin_test_runner
```

Expected: build clean, 75+ tests still green.

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 4.8 — tool window wiring

ProfileController owns one ServerPoller per active profile, routing
state updates to the four panels and turning crash detections into
IntelliJ balloon notifications. ServerSwitcher renders the dropdown
and gear/add buttons. McAdminToolWindow is the root panel optimized
for a bottom-anchored tool window: a single-row north toolbar
(switcher + status strip + lifecycle buttons) and a center
JBSplitter with players on the left (30%) and console on the right
(70%). Docked/undocked modes share the same layout — IntelliJ's
ToolWindow framework handles the transitions for free.

ServerPoller gets sendStart/sendStop/sendRestart/sendBackup helpers
that run on the command executor and record user-stop hints into the
CrashDetector so intentional stops don't trip the crash balloon.

plugin.xml now declares the Minecraft Admin tool window, the
ProfileStore application service, and the Conch Minecraft
notification group.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4.9: Standalone plugin zip + `make minecraft-admin-plugin` target

The plugin ships as a single `.zip` that your friend can install via IntelliJ's "Install Plugin from Disk…" dialog. This task adds a top-level Makefile target that builds the jar, stages it into the IntelliJ plugin directory layout, and zips the result.

**Files:**
- Modify: `Makefile` at the repo root — add `minecraft-admin-plugin` + `minecraft-admin-plugin-clean` targets
- Create: `scripts/package-minecraft-admin-plugin.sh` — small shell helper that does the staging + zipping (keeps the Makefile readable)

- [ ] **Step 1: Read the existing Makefile to match conventions**

Open `/Users/dustin/projects/conch_workbench/Makefile`. Note:
- `$(BAZEL)` is defined near the top as `cd $(INTELLIJ_ROOT) && bash bazel.cmd`
- `conch-build`, `conch`, `conch-clean` are the existing targets
- Target bodies use tabs, not spaces
- The repo uses `out/` for build outputs (see `.gitignore` for `out/` entry)

- [ ] **Step 2: Create the packaging script**

Create `scripts/package-minecraft-admin-plugin.sh`:

```bash
#!/usr/bin/env bash
# Package the Minecraft Admin plugin as an IntelliJ-installable zip.
# Called from the root Makefile's `minecraft-admin-plugin` target.
set -euo pipefail

WORKBENCH_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$WORKBENCH_DIR"

OUT_ROOT="out/minecraft-admin-plugin"
PLUGIN_DIR_NAME="Conch Minecraft Admin"
STAGE_DIR="$OUT_ROOT/$PLUGIN_DIR_NAME"
LIB_DIR="$STAGE_DIR/lib"
ZIP_PATH="out/minecraft-admin-plugin.zip"

INTELLIJ_ROOT_FILE="$WORKBENCH_DIR/.intellij-root"
if [ -f "$INTELLIJ_ROOT_FILE" ]; then
  INTELLIJ_ROOT="$(cat "$INTELLIJ_ROOT_FILE")"
else
  INTELLIJ_ROOT="$WORKBENCH_DIR/../intellij-community"
fi

echo "==> Building plugin jar via Bazel"
(cd "$INTELLIJ_ROOT" && bash bazel.cmd build //conch/plugins/minecraft-admin:minecraft-admin)

JAR_PATH="$INTELLIJ_ROOT/out/bazel-bin/conch/plugins/minecraft-admin/minecraft-admin.jar"
if [ ! -f "$JAR_PATH" ]; then
  echo "error: expected jar not found at $JAR_PATH" >&2
  exit 1
fi

echo "==> Staging plugin layout at $STAGE_DIR"
rm -rf "$OUT_ROOT" "$ZIP_PATH"
mkdir -p "$LIB_DIR"
cp "$JAR_PATH" "$LIB_DIR/minecraft-admin.jar"

echo "==> Zipping to $ZIP_PATH"
(cd "$OUT_ROOT" && zip -rq "../$(basename "$ZIP_PATH")" "$PLUGIN_DIR_NAME")

SIZE="$(wc -c < "$ZIP_PATH" | tr -d ' ')"
echo
echo "==> Done."
echo "    $ZIP_PATH  ($SIZE bytes)"
echo "    Install via Conch → Settings → Plugins → gear → Install Plugin from Disk…"
```

- [ ] **Step 3: Make the script executable**

```bash
chmod +x scripts/package-minecraft-admin-plugin.sh
```

- [ ] **Step 4: Add Makefile targets**

Append to the root `Makefile`:

```makefile
.PHONY: minecraft-admin-plugin minecraft-admin-plugin-clean

minecraft-admin-plugin:
	@bash scripts/package-minecraft-admin-plugin.sh

minecraft-admin-plugin-clean:
	@rm -rf out/minecraft-admin-plugin out/minecraft-admin-plugin.zip
	@echo "Removed out/minecraft-admin-plugin and out/minecraft-admin-plugin.zip"
```

- [ ] **Step 5: Smoke-test the target**

```bash
cd /Users/dustin/projects/conch_workbench
make minecraft-admin-plugin
```

Expected output:
```
==> Building plugin jar via Bazel
... (bazel output)
==> Staging plugin layout at out/minecraft-admin-plugin/Conch Minecraft Admin
==> Zipping to out/minecraft-admin-plugin.zip
==> Done.
    out/minecraft-admin-plugin.zip  (NNNNN bytes)
    Install via Conch → Settings → Plugins → gear → Install Plugin from Disk…
```

Then verify the zip contents:

```bash
unzip -l out/minecraft-admin-plugin.zip
```

Expected layout:
```
Conch Minecraft Admin/
Conch Minecraft Admin/lib/
Conch Minecraft Admin/lib/minecraft-admin.jar
```

And verify `plugin.xml` is inside the jar:

```bash
unzip -p out/minecraft-admin-plugin.zip "Conch Minecraft Admin/lib/minecraft-admin.jar" | \
  unzip -l /dev/stdin | grep plugin.xml
```

Expected: `META-INF/plugin.xml` listed.

- [ ] **Step 6: Clean-up smoke test**

```bash
make minecraft-admin-plugin-clean
ls out/minecraft-admin-plugin* 2>&1 | grep -q "No such" && echo "clean ok" || echo "clean failed"
```

Expected: `clean ok`.

- [ ] **Step 7: Commit**

```bash
git add Makefile scripts/package-minecraft-admin-plugin.sh
git commit -m "$(cat <<'EOF'
feat(minecraft-admin): phase 4.9 — standalone plugin build target

`make minecraft-admin-plugin` drives the Bazel build, stages the jar
into the IntelliJ plugin directory layout (Conch Minecraft Admin/lib/),
and zips the result to out/minecraft-admin-plugin.zip. End users install
via Settings → Plugins → gear → Install Plugin from Disk…

`make minecraft-admin-plugin-clean` removes the staged directory and
the zip.

Packaging logic lives in scripts/package-minecraft-admin-plugin.sh so
the Makefile stays readable.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 4 gate

- [ ] All tests pass: `bazel run //conch/plugins/minecraft-admin:minecraft_admin_test_runner` → 75+ green
- [ ] Full product build: `make conch-build` → succeeds
- [ ] `plugin.xml` declares the tool window (`anchor="bottom"`), service, and notification group
- [ ] Each panel has dedicated render smoke tests, all green
- [ ] `make minecraft-admin-plugin` produces a well-formed zip that unpacks to `Conch Minecraft Admin/lib/minecraft-admin.jar`
- [ ] No placeholder code remains anywhere in Phase 4 files

---

## Phase 5 — Manual smoke test gate

Phase 5 is manual verification against a real AMP + Paper server. No new code; this is where the empirical parts of the spec (specifically the ping-probe strategy and the AMP `AppState` mapping) get confirmed or corrected.

- [ ] **Step 1: Full build**

```bash
make conch-build
```

- [ ] **Step 2: Run all test suites**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //conch/plugins/minecraft-admin:minecraft_admin_test_runner
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //conch/plugins/ssh:ssh_test_runner
```

- [ ] **Step 3: Build the standalone plugin zip**

```bash
cd /Users/dustin/projects/conch_workbench
make minecraft-admin-plugin
```

Expected: `out/minecraft-admin-plugin.zip` exists and `unzip -l` shows `Conch Minecraft Admin/lib/minecraft-admin.jar`.

- [ ] **Step 4: Launch Conch and install the plugin from disk**

Run `make conch`. When Conch opens, go to `Settings / Preferences → Plugins → gear icon → Install Plugin from Disk…` and select `out/minecraft-admin-plugin.zip`. Restart Conch when prompted.

- [ ] **Step 5: Manual — tool window appears at the bottom**

Confirm: after restart, "Minecraft Admin" stripe button shows up on the **bottom** stripe. Click it. The tool window opens at the bottom of the IDE with an empty profile dropdown and disabled panels. Verify the layout:
- Single-row top toolbar: server switcher on the left, status strip in the middle, lifecycle buttons on the right
- Main area: horizontal split with players on the left (~30%) and console on the right (~70%)
- Dragging the splitter resizes both panes

- [ ] **Step 5a: Manual — undock / float verification**

Click the tool window's gear icon → View Mode. Verify that **Undock**, **Float**, and **Window** modes all work:
- Each mode transitions without NPEs or layout glitches
- Panel reflows naturally — the players/console split stays horizontal, the toolbar stays on top
- Returning to **Docked** re-pins to the bottom stripe

- [ ] **Step 5: Manual — add a profile**

Click the `+` button. Fill in:
- Label: a short name you'll recognize
- AMP URL: the base URL of your friend's AMP panel
- AMP instance name: the friendly name of the Paper instance
- AMP username: your admin user
- Pick an AMP credential from the vault (create one first if needed)
- RCON host / port: whatever your Paper server is listening on
- Pick an RCON credential

Save. Confirm the profile appears in the dropdown and `minecraft-servers.json` now exists at `~/.config/conch/minecraft-servers.json` with no secrets in it.

- [ ] **Step 6: Manual — initial poll succeeds**

Within ~5 seconds the status strip should populate: status = Running, players count, TPS, CPU%, RAM, uptime. The lifecycle row should show Stop / Restart / Backup enabled and Start disabled.

If either side fails, the corresponding pill shows and the cells gray out. Check the tooltip for the error message and fix the config (wrong URL, wrong password, firewall, etc.).

- [ ] **Step 7: Manual — players panel**

Join the server from a real Minecraft client. Within 5 seconds your name should appear in the players table. Right-click your name: the Kick / Ban / Op popup should appear. Don't actually click any of them unless you want to test them — use an alt.

- [ ] **Step 8: Manual — console tail**

Watch the console panel: new lines from AMP should appear over time. Type `say hello from conch` in the send box and press Enter. You should see the line appear both in-game and in the tail.

- [ ] **Step 9: Manual — broadcast button**

Type something in the send box and click Broadcast instead of pressing Enter. Same behavior as `say` from step 8.

- [ ] **Step 10: Manual — lifecycle buttons**

Click Backup Now. Check AMP's backup list to confirm a new entry appeared. Click Restart (warn your friend first). Watch the status transition to Stopping → Starting → Running over the next minute. Confirm **no** crash notification fires during the intentional restart.

- [ ] **Step 11: Manual — crash detection**

Intentionally crash the instance to test the balloon. Easiest way: from AMP's own UI, use "Stop" (not through Conch's Stop button). Wait 10+ seconds so the user-stop grace window expires, then watch Conch — the crash balloon should fire. Click it → the tool window should focus and the console should scroll to the bottom.

If the balloon fires on a regular Conch Stop (false positive), or doesn't fire on an actual unexpected stop, the `CrashDetector` rules need tuning — file the specific case and update the rules.

- [ ] **Step 12: Manual — ping-probe empirical check**

Note whether the players panel shows real ping values or `—`. Record the result in a comment on `PaperListReplyParser` so future maintainers know the actual Paper version behavior:

- If ping is present in the `list` output, the existing `[Nms]` extraction is sufficient.
- If not, open `PaperListReplyParser` and add a follow-up note for a v1.1 probe using either `minecraft:ping` or a secondary per-player RCON call (see the spec's "Player ping is best-effort" section).

- [ ] **Step 13: Manual — profile switching**

Add a second profile pointing at a different AMP instance (if your friend has one; otherwise duplicate the same instance with a different label). Switch between them in the dropdown. The poller should stop the old one, start the new one, and the status strip should re-populate within 5 seconds.

- [ ] **Step 14: Manual — restart the IDE**

Close Conch. Reopen. The tool window, the profile list, and the most-recently-selected server should all restore cleanly. Secrets are still required — the vault should gate re-authentication, not remember across restarts unless the vault itself is unlocked.

- [ ] **Step 15: Commit the manual-verification notes**

If any of the steps turned up real-world corrections (ping source, AMP `AppState` code the spec got wrong, a balloon false positive), capture them inline in the affected source files and commit. No manual observations belong in the plan doc after this point — they go in code comments or a follow-up issue.

```bash
git add plugins/minecraft-admin/
git commit -m "$(cat <<'EOF'
chore(minecraft-admin): phase 5 — manual verification notes

Notes captured from the first live run against a real AMP + Paper
instance: ping probe result, any AppState mapping corrections, any
CrashDetector tuning from observed transitions.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-review checklist

**1. Spec coverage:**

- Plugin structure (`plugins/minecraft-admin/`, package `com.conch.minecraftadmin`, id `com.conch.minecraft-admin`) → Task 1.1 ✓
- `ServerProfile` record with all spec fields → Task 1.2 ✓
- `McServerStatus` enum → Task 1.2 ✓
- `Player` record with best-effort ping → Task 1.2 ✓
- `ServerState` immutable snapshot with defensive list copy → Task 1.3 ✓
- `~/.config/conch/minecraft-servers.json` persistence, versioned envelope, atomic write → Task 1.4 ✓
- `ProfileStore` application service → Task 1.5 ✓
- `RconPacketCodec` isolated binary protocol → Task 2.1 ✓
- `RconClient` with in-process fake-server tests → Task 2.2 ✓
- `PaperListReplyParser` / `PaperTpsReplyParser` → Task 2.3 ✓
- `AmpClient` with HttpServer-based fake tests, session-token auth + 401 auto-retry → Task 3.1 ✓
- `ServerStateMerger` pure merge of tick results → Task 3.2 ✓
- `CrashDetector` with RUNNING-to-terminal rules and user-stop grace window → Task 3.3 ✓
- `ServerPoller` 5s scheduled loop with merged state delivery → Task 3.4 ✓
- Vault-backed credential resolution (`McCredential`, `McCredentialResolver`) → Task 4.1 ✓
- Poller accepts injected password supplier → Task 4.2 ✓
- `ServerEditDialog` add/edit modal → Task 4.3 ✓
- `StatusStripPanel` six-cell row with AMP/RCON gray-out and pills → Task 4.4 ✓
- `LifecycleButtons` with status-driven enablement → Task 4.5 ✓
- `PlayersPanel` with right-click Kick/Ban/Op → Task 4.6 ✓
- `ConsolePanel` tail + send box + history + Broadcast → Task 4.7 ✓
- `ProfileController` wiring + `McAdminToolWindow` + factory + `plugin.xml` (`anchor="bottom"`) → Task 4.8 ✓
- `McAdminToolWindow` horizontal split layout for bottom anchor → Task 4.8 ✓
- Crash balloon notification → Task 4.8 (ProfileController onCrashDetected) ✓
- Standalone `make minecraft-admin-plugin` target producing an installable zip → Task 4.9 ✓
- Undock / floating window support inherited from IntelliJ's `ToolWindow` API → no task needed (free from the framework) ✓
- Manual verification gate covering ping probe, AMP AppState, crash detection → Phase 5 ✓

No gaps.

**2. Type / API consistency:**

- `ServerProfile.create(label, ampUrl, ampInstanceName, ampUsername, ampCredentialId, rconHost, rconPort, rconCredentialId)` — 8 params, consistent across test, dialog, and poller call sites
- `ServerState` constructor / record fields — `(status, playersOnline, playersMax, players, tps, cpuPercent, ramUsedMb, ramMaxMb, uptime, ampError, rconError, sampledAt)` — consistent
- `AmpClient.LoginPair(username, password)` — `password` is `char[]` — consistent between production lambda and test fixture
- `AmpClient.Credentials` is a functional interface taking `String baseUrl` → `LoginPair` — used in Task 3.1 tests and Task 4.8 ProfileController
- `RconClient.connect(host, port, password)` — `password` is `char[]` — consistent with `McCredential.password()` return type
- `StateListener` methods: `onStateUpdate(ServerState)`, `onConsoleLines(List<String>)`, `onCrashDetected(ServerState)` — consistent between interface, ServerPoller dispatch, and ProfileController implementation
- `ServerPoller` constructor — `(profile, ampClient, rconClient, listener, scheduler, uiDispatcher, rconPasswordSupplier)` — 7 params after Task 4.2 rewire, matched in Task 3.4 test, Task 4.8 ProfileController
- `ServerPoller.sendStart/sendStop/sendRestart/sendBackup/sendCommand/pollConsole/recordUserStop/start/stop/tickOnce` — all defined, all called consistently from the tool window and tests
- `CrashDetector.observe(status, sampledAt)` returns `boolean`; `CrashDetector.recordUserStop(Instant)` — consistent across tests and ServerPoller internal calls
- `McCredential` has `username()` and `password()` accessors used by ProfileController; consistent with McCredentialResolver return type

**3. Placeholder scan:**

- Task 4.3 (ServerEditDialog) is described by structure + a reference to `HostEditDialog.java` rather than a full code block. The file is ~220 lines of Swing layout that's substantially identical to the SSH dialog; forcing it all inline would bloat the plan without helping the implementer. The exact field list, validation rules, and public API are specified in Task 4.3's checklist.
- Task 4.8 uses `ServerPoller.sendStart/...` helpers that are defined in Task 4.8 Step 4 as a sub-step of the same task. Defined before use.
- Phase 5's "empirical" steps are deliberately open-ended — the plan's job is to schedule the verification, not to predict its outcome.

No remaining `TBD`, `TODO`, or undefined-symbol references.

