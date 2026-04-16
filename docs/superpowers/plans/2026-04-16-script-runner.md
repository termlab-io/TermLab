# Script Runner Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a lightweight script execution plugin to TermLab that lets users run scripts from the light editor on local or remote hosts, with live-streamed output in a dedicated tool window.

**Architecture:** New standalone `plugins/runner/` plugin depending on `com.termlab.ssh` (for `TermLabSshClient`, `HostStore`), `com.termlab.editor` (for `ScratchMarker`, `SaveAsHelper`), and `com.termlab.sftp` (for `SftpVirtualFile` detection). Configuration persisted to `~/.config/termlab/run-configs.json` using the same atomic-write JSON pattern as the SSH host store. Execution via `ProcessBuilder` (local) or SSH exec channels (remote), with output streamed to a bottom-anchored tabbed tool window.

**Tech Stack:** Java 21, MINA SSHD (exec channels), GSON, IntelliJ Platform SDK (actions, tool windows, services), JUnit 5, Bazel

**Spec:** `docs/superpowers/specs/2026-04-16-script-runner-design.md`

---

## File Structure

```
plugins/runner/
├── BUILD.bazel
├── intellij.termlab.runner.iml
├── resources/META-INF/plugin.xml
├── src/com/termlab/runner/
│   ├── config/
│   │   ├── RunConfig.java              # Configuration record
│   │   ├── RunConfigStore.java         # Application service: CRUD + persistence
│   │   ├── FileConfigBinding.java      # File path → config UUID mapping
│   │   ├── InterpreterRegistry.java    # Extension → default interpreter map
│   │   ├── RunnerPaths.java            # ~/.config/termlab/ path helpers
│   │   └── RunnerGson.java             # GSON singleton for runner serialization
│   ├── execution/
│   │   ├── ScriptExecution.java        # Running script handle (interface)
│   │   ├── LocalExecution.java         # Local ProcessBuilder-based execution
│   │   ├── RemoteExecution.java        # SSH exec channel-based execution
│   │   └── CommandBuilder.java         # Assembles the execution command string
│   ├── output/
│   │   ├── ScriptOutputToolWindowFactory.java  # ToolWindowFactory impl
│   │   ├── ScriptOutputPanel.java      # Tabbed panel (main tool window content)
│   │   ├── OutputTab.java              # Single execution output tab
│   │   └── OutputTabHeader.java        # Header bar (interpreter, host, status)
│   └── actions/
│       ├── RunScriptAction.java        # Run button (Cmd+R)
│       ├── EditConfigAction.java       # Edit Configuration button (Cmd+Shift+R)
│       ├── ConfigDropdownAction.java   # Config selector dropdown
│       └── SaveBeforeRunHelper.java    # Save-before-run flow
└── test/com/termlab/runner/
    ├── TestRunner.java                 # JUnit 5 platform launcher
    ├── config/
    │   ├── RunConfigTest.java
    │   ├── RunConfigStoreTest.java
    │   ├── FileConfigBindingTest.java
    │   └── InterpreterRegistryTest.java
    └── execution/
        └── CommandBuilderTest.java
```

Files modified in other modules:
- `BUILD.bazel` (root) — add `//termlab/plugins/runner` to `termlab_run`
- `customization/resources/idea/TermLabApplicationInfo.xml` — add essential-plugin entry

---

### Task 1: Plugin Skeleton

**Files:**
- Create: `plugins/runner/BUILD.bazel`
- Create: `plugins/runner/intellij.termlab.runner.iml`
- Create: `plugins/runner/resources/META-INF/plugin.xml`
- Create: `plugins/runner/src/com/termlab/runner/package-info.java`
- Create: `plugins/runner/test/com/termlab/runner/TestRunner.java`
- Modify: `BUILD.bazel` (root)
- Modify: `customization/resources/idea/TermLabApplicationInfo.xml`

- [ ] **Step 1: Create the BUILD.bazel**

```python
# plugins/runner/BUILD.bazel
load("@rules_java//java:defs.bzl", "java_binary")
load("@rules_jvm//:jvm.bzl", "jvm_library", "resourcegroup")

resourcegroup(
    name = "runner_resources",
    srcs = glob(["resources/**/*"]),
    strip_prefix = "resources",
)

jvm_library(
    name = "runner",
    module_name = "intellij.termlab.runner",
    visibility = ["//visibility:public"],
    srcs = glob(["src/**/*.java"], allow_empty = True),
    resources = [":runner_resources"],
    deps = [
        "//termlab/sdk",
        "//termlab/core",
        "//termlab/plugins/ssh",
        "//termlab/plugins/sftp",
        "//termlab/plugins/sftp/libs:sshd_sftp",
        "//termlab/plugins/editor",
        "//libraries/sshd-osgi",
        "//platform/analysis-api:analysis",
        "//platform/core-api:core",
        "//platform/core-ui",
        "//platform/editor-ui-api:editor-ui",
        "//platform/ide-core",
        "//platform/platform-api:ide",
        "//platform/platform-impl:ide-impl",
        "//platform/projectModel-api:projectModel",
        "//platform/util",
        "//platform/util:util-ui",
        "@lib//:gson",
        "@lib//:jetbrains-annotations",
        "@lib//:kotlin-stdlib",
    ],
)

jvm_library(
    name = "runner_test_lib",
    module_name = "intellij.termlab.runner.tests",
    visibility = ["//visibility:public"],
    srcs = glob(["test/**/*.java"], allow_empty = True),
    deps = [
        ":runner",
        "//termlab/sdk",
        "//termlab/plugins/ssh",
        "//libraries/junit5",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
        "//platform/core-api:core",
        "//platform/util",
        "@lib//:gson",
        "@lib//:jetbrains-annotations",
        "@lib//:kotlin-stdlib",
    ],
)

java_binary(
    name = "runner_test_runner",
    main_class = "com.termlab.runner.TestRunner",
    runtime_deps = [
        ":runner_test_lib",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
    ],
)

exports_files(["intellij.termlab.runner.iml"], visibility = ["//visibility:public"])
```

- [ ] **Step 2: Create the plugin.xml**

```xml
<!-- plugins/runner/resources/META-INF/plugin.xml -->
<idea-plugin>
    <id>com.termlab.runner</id>
    <name>TermLab Script Runner</name>
    <version>0.1.0</version>
    <vendor>TermLab</vendor>
    <description>
        Lightweight script execution for TermLab. Run scripts from the
        light editor on local or remote hosts with live output streaming.
    </description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.termlab.core</depends>
    <depends>com.termlab.ssh</depends>
    <depends>com.termlab.sftp</depends>
    <depends>com.termlab.editor</depends>

    <extensions defaultExtensionNs="com.intellij">
        <notificationGroup
            id="TermLab Script Runner"
            displayType="BALLOON"/>

        <applicationService
            serviceImplementation="com.termlab.runner.config.RunConfigStore"/>

        <applicationService
            serviceImplementation="com.termlab.runner.config.FileConfigBinding"/>

        <toolWindow
            id="Script Output"
            anchor="bottom"
            icon="AllIcons.Actions.Execute"
            factoryClass="com.termlab.runner.output.ScriptOutputToolWindowFactory"/>
    </extensions>

    <actions>
        <action id="TermLab.Runner.Run"
                class="com.termlab.runner.actions.RunScriptAction"
                text="Run Script"
                icon="AllIcons.Actions.Execute"
                description="Run the current file on a local or remote host">
            <keyboard-shortcut keymap="$default" first-keystroke="control R"/>
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="meta R" replace-all="true"/>
            <keyboard-shortcut keymap="Mac OS X" first-keystroke="meta R" replace-all="true"/>
        </action>

        <action id="TermLab.Runner.EditConfig"
                class="com.termlab.runner.actions.EditConfigAction"
                text="Edit Run Configuration"
                icon="AllIcons.General.Settings"
                description="Edit the run configuration for the current file">
            <keyboard-shortcut keymap="$default" first-keystroke="control shift R"/>
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="meta shift R" replace-all="true"/>
            <keyboard-shortcut keymap="Mac OS X" first-keystroke="meta shift R" replace-all="true"/>
        </action>
    </actions>
</idea-plugin>
```

- [ ] **Step 3: Create the package-info.java placeholder**

```java
// plugins/runner/src/com/termlab/runner/package-info.java
/**
 * TermLab Script Runner — lightweight script execution on local and
 * remote hosts with live output streaming.
 */
package com.termlab.runner;
```

- [ ] **Step 4: Create the TestRunner**

```java
// plugins/runner/test/com/termlab/runner/TestRunner.java
package com.termlab.runner;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

/**
 * Standalone test runner for the runner plugin's unit tests.
 *
 * <p>Usage:
 * <pre>
 *   bash bazel.cmd run //termlab/plugins/runner:runner_test_runner
 * </pre>
 */
public final class TestRunner {

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("com.termlab.runner"))
            .build();

        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        PrintWriter writer = new PrintWriter(System.out, true);
        summary.printTo(writer);

        if (!summary.getFailures().isEmpty()) {
            summary.printFailuresTo(writer);
            System.exit(1);
        }
        System.exit(0);
    }
}
```

- [ ] **Step 5: Create the IML file**

```xml
<!-- plugins/runner/intellij.termlab.runner.iml -->
<?xml version="1.0" encoding="UTF-8"?>
<module type="JAVA_MODULE" version="4">
  <component name="NewModuleRootManager" inherit-compiler-output="true">
    <exclude-output />
    <content url="file://$MODULE_DIR$">
      <sourceFolder url="file://$MODULE_DIR$/src" isTestSource="false" />
      <sourceFolder url="file://$MODULE_DIR$/test" isTestSource="true" />
      <sourceFolder url="file://$MODULE_DIR$/resources" type="java-resource" />
    </content>
    <orderEntry type="inheritedJdk" />
    <orderEntry type="sourceFolder" forTests="false" />
  </component>
</module>
```

- [ ] **Step 6: Register in root BUILD.bazel**

In `BUILD.bazel` (root), add to the `termlab_run` target's `runtime_deps`, after the editor plugin line:

```python
        # Light editor: scratch files + SFTP-triggered file editing.
        "//termlab/plugins/editor",
        # Script runner: lightweight script execution on local/remote hosts.
        "//termlab/plugins/runner",
```

- [ ] **Step 7: Register as essential plugin**

In `customization/resources/idea/TermLabApplicationInfo.xml`, add after the SFTP entry:

```xml
  <essential-plugin>com.termlab.sftp</essential-plugin>
  <essential-plugin>com.termlab.runner</essential-plugin>
```

- [ ] **Step 8: Verify the build compiles**

Run: `bash bazel.cmd build //termlab/plugins/runner`
Expected: BUILD SUCCESS (may have warnings about empty sources, that's fine)

- [ ] **Step 9: Commit**

```bash
git add plugins/runner/ BUILD.bazel customization/resources/idea/TermLabApplicationInfo.xml
git commit -m "feat(runner): add plugin skeleton with build and registration"
```

---

### Task 2: RunConfig Model + GSON

**Files:**
- Create: `plugins/runner/src/com/termlab/runner/config/RunConfig.java`
- Create: `plugins/runner/src/com/termlab/runner/config/RunnerGson.java`
- Create: `plugins/runner/test/com/termlab/runner/config/RunConfigTest.java`

- [ ] **Step 1: Write the failing test for RunConfig creation and serialization**

```java
// plugins/runner/test/com/termlab/runner/config/RunConfigTest.java
package com.termlab.runner.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RunConfigTest {

    @Test
    void create_generatesUniqueId() {
        RunConfig a = RunConfig.create("Test", null, "python3",
            List.of(), null, Map.of(), List.of());
        RunConfig b = RunConfig.create("Test", null, "python3",
            List.of(), null, Map.of(), List.of());
        assertNotEquals(a.id(), b.id());
    }

    @Test
    void create_nullHostId_meansLocal() {
        RunConfig config = RunConfig.create("Local Python", null, "python3",
            List.of(), null, Map.of(), List.of());
        assertNull(config.hostId());
        assertTrue(config.isLocal());
    }

    @Test
    void create_withHostId_meansRemote() {
        UUID hostId = UUID.randomUUID();
        RunConfig config = RunConfig.create("Remote", hostId, "bash",
            List.of(), "/home/user", Map.of(), List.of());
        assertEquals(hostId, config.hostId());
        assertFalse(config.isLocal());
    }

    @Test
    void withName_returnsNewInstance() {
        RunConfig original = RunConfig.create("Old", null, "python3",
            List.of(), null, Map.of(), List.of());
        RunConfig renamed = original.withName("New");
        assertEquals("New", renamed.name());
        assertEquals(original.id(), renamed.id());
        assertEquals("Old", original.name());
    }

    @Test
    void roundTrip_throughGson_preservesAllFields() {
        UUID hostId = UUID.randomUUID();
        RunConfig original = RunConfig.create("Full Config", hostId, "python3.11",
            List.of("-u"), "/home/deploy", Map.of("DEBUG", "1", "PORT", "8080"),
            List.of("--verbose", "input.csv"));

        String json = RunnerGson.GSON.toJson(original);
        RunConfig restored = RunnerGson.GSON.fromJson(json, RunConfig.class);

        assertEquals(original.id(), restored.id());
        assertEquals(original.name(), restored.name());
        assertEquals(original.hostId(), restored.hostId());
        assertEquals(original.interpreter(), restored.interpreter());
        assertEquals(original.args(), restored.args());
        assertEquals(original.workingDirectory(), restored.workingDirectory());
        assertEquals(original.envVars(), restored.envVars());
        assertEquals(original.scriptArgs(), restored.scriptArgs());
    }

    @Test
    void roundTrip_nullableFieldsAsNull() {
        RunConfig original = RunConfig.create("Minimal", null, "bash",
            List.of(), null, Map.of(), List.of());

        String json = RunnerGson.GSON.toJson(original);
        RunConfig restored = RunnerGson.GSON.fromJson(json, RunConfig.class);

        assertNull(restored.hostId());
        assertNull(restored.workingDirectory());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `bash bazel.cmd run //termlab/plugins/runner:runner_test_runner`
Expected: FAIL — `RunConfig` and `RunnerGson` classes do not exist

- [ ] **Step 3: Implement RunConfig**

```java
// plugins/runner/src/com/termlab/runner/config/RunConfig.java
package com.termlab.runner.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A named script execution configuration.
 *
 * @param id               stable UUID
 * @param name             user-facing label (e.g., "Python on prod-server")
 * @param hostId           {@code null} for local execution; otherwise the
 *                         UUID of a host in {@code HostStore}
 * @param interpreter      command to invoke (e.g., "python3", "/usr/bin/node")
 * @param args             extra arguments passed to the interpreter before
 *                         the script path
 * @param workingDirectory working directory; {@code null} means use the
 *                         script's parent directory
 * @param envVars          environment variable overrides
 * @param scriptArgs       arguments passed after the script path
 */
public record RunConfig(
    @NotNull UUID id,
    @NotNull String name,
    @Nullable UUID hostId,
    @NotNull String interpreter,
    @NotNull List<String> args,
    @Nullable String workingDirectory,
    @NotNull Map<String, String> envVars,
    @NotNull List<String> scriptArgs
) {

    public RunConfig {
        args = List.copyOf(args);
        envVars = Map.copyOf(envVars);
        scriptArgs = List.copyOf(scriptArgs);
    }

    /** @return {@code true} if this config runs locally (no remote host). */
    public boolean isLocal() {
        return hostId == null;
    }

    /** @return a copy with a new name. */
    public @NotNull RunConfig withName(@NotNull String newName) {
        return new RunConfig(id, newName, hostId, interpreter, args,
            workingDirectory, envVars, scriptArgs);
    }

    /** @return a copy with all editable fields replaced. */
    public @NotNull RunConfig withEdited(
        @NotNull String newName,
        @Nullable UUID newHostId,
        @NotNull String newInterpreter,
        @NotNull List<String> newArgs,
        @Nullable String newWorkingDirectory,
        @NotNull Map<String, String> newEnvVars,
        @NotNull List<String> newScriptArgs
    ) {
        return new RunConfig(id, newName, newHostId, newInterpreter, newArgs,
            newWorkingDirectory, newEnvVars, newScriptArgs);
    }

    /** Factory for brand-new configurations. */
    public static @NotNull RunConfig create(
        @NotNull String name,
        @Nullable UUID hostId,
        @NotNull String interpreter,
        @NotNull List<String> args,
        @Nullable String workingDirectory,
        @NotNull Map<String, String> envVars,
        @NotNull List<String> scriptArgs
    ) {
        return new RunConfig(UUID.randomUUID(), name, hostId, interpreter,
            args, workingDirectory, envVars, scriptArgs);
    }
}
```

- [ ] **Step 4: Implement RunnerGson**

```java
// plugins/runner/src/com/termlab/runner/config/RunnerGson.java
package com.termlab.runner.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * GSON configuration for the runner plugin. RunConfig is a simple
 * record with no sealed-type polymorphism, so no custom adapters are
 * needed — Gson handles records, UUIDs, Lists, and Maps natively.
 */
public final class RunnerGson {

    public static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    private RunnerGson() {}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `bash bazel.cmd run //termlab/plugins/runner:runner_test_runner`
Expected: All 6 tests PASS

- [ ] **Step 6: Commit**

```bash
git add plugins/runner/src/com/termlab/runner/config/RunConfig.java \
       plugins/runner/src/com/termlab/runner/config/RunnerGson.java \
       plugins/runner/test/com/termlab/runner/config/RunConfigTest.java
git commit -m "feat(runner): add RunConfig model and GSON serialization"
```

---

### Task 3: RunnerPaths + RunConfigStore Persistence

**Files:**
- Create: `plugins/runner/src/com/termlab/runner/config/RunnerPaths.java`
- Create: `plugins/runner/src/com/termlab/runner/config/RunConfigStore.java`
- Create: `plugins/runner/test/com/termlab/runner/config/RunConfigStoreTest.java`

- [ ] **Step 1: Write the failing tests for RunConfigStore**

```java
// plugins/runner/test/com/termlab/runner/config/RunConfigStoreTest.java
package com.termlab.runner.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RunConfigStoreTest {

    @Test
    void newStore_isEmpty(@TempDir Path tmp) {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);
        assertTrue(store.getAll().isEmpty());
    }

    @Test
    void addConfig_thenGetAll_returnsIt(@TempDir Path tmp) {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);

        RunConfig config = RunConfig.create("Test", null, "python3",
            List.of(), null, Map.of(), List.of());
        store.add(config);

        assertEquals(1, store.getAll().size());
        assertEquals("Test", store.getAll().get(0).name());
    }

    @Test
    void save_thenLoad_roundTrips(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);

        RunConfig config = RunConfig.create("Deploy Script", null, "bash",
            List.of("-x"), "/tmp", Map.of("ENV", "prod"), List.of("--dry-run"));
        store.add(config);
        store.save();

        assertTrue(Files.exists(file));

        RunConfigStore reloaded = new RunConfigStore(file);
        assertEquals(1, reloaded.getAll().size());
        RunConfig restored = reloaded.getAll().get(0);
        assertEquals(config.id(), restored.id());
        assertEquals("Deploy Script", restored.name());
        assertEquals("bash", restored.interpreter());
        assertEquals(List.of("-x"), restored.args());
        assertEquals("/tmp", restored.workingDirectory());
        assertEquals(Map.of("ENV", "prod"), restored.envVars());
        assertEquals(List.of("--dry-run"), restored.scriptArgs());
    }

    @Test
    void save_isAtomic_noTmpFileRemains(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);
        store.add(RunConfig.create("A", null, "bash", List.of(), null, Map.of(), List.of()));
        store.save();

        assertFalse(Files.exists(tmp.resolve("run-configs.json.tmp")));
        assertTrue(Files.exists(file));
    }

    @Test
    void remove_byId(@TempDir Path tmp) {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);

        RunConfig config = RunConfig.create("Test", null, "bash",
            List.of(), null, Map.of(), List.of());
        store.add(config);
        assertTrue(store.remove(config.id()));
        assertTrue(store.getAll().isEmpty());
    }

    @Test
    void update_replacesInPlace(@TempDir Path tmp) {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);

        RunConfig config = RunConfig.create("Old", null, "bash",
            List.of(), null, Map.of(), List.of());
        store.add(config);

        RunConfig updated = config.withName("New");
        assertTrue(store.update(updated));
        assertEquals("New", store.getAll().get(0).name());
    }

    @Test
    void getById_findsConfig(@TempDir Path tmp) {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);

        RunConfig config = RunConfig.create("Find Me", null, "bash",
            List.of(), null, Map.of(), List.of());
        store.add(config);

        assertNotNull(store.getById(config.id()));
        assertEquals("Find Me", store.getById(config.id()).name());
    }

    @Test
    void getById_missingId_returnsNull(@TempDir Path tmp) {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);
        assertNull(store.getById(java.util.UUID.randomUUID()));
    }

    @Test
    void changeListener_firesOnAdd(@TempDir Path tmp) {
        Path file = tmp.resolve("run-configs.json");
        RunConfigStore store = new RunConfigStore(file);

        boolean[] fired = {false};
        store.addChangeListener(() -> fired[0] = true);
        store.add(RunConfig.create("X", null, "bash", List.of(), null, Map.of(), List.of()));
        assertTrue(fired[0]);
    }

    @Test
    void load_corruptedFile_returnsEmpty(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("run-configs.json");
        Files.writeString(file, "not valid json {{{");
        RunConfigStore store = new RunConfigStore(file);
        assertTrue(store.getAll().isEmpty());
    }

    @Test
    void load_missingFile_returnsEmpty(@TempDir Path tmp) {
        Path file = tmp.resolve("nonexistent.json");
        RunConfigStore store = new RunConfigStore(file);
        assertTrue(store.getAll().isEmpty());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash bazel.cmd run //termlab/plugins/runner:runner_test_runner`
Expected: FAIL — `RunConfigStore` and `RunnerPaths` do not exist

- [ ] **Step 3: Implement RunnerPaths**

```java
// plugins/runner/src/com/termlab/runner/config/RunnerPaths.java
package com.termlab.runner.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Canonical paths for the runner plugin's on-disk state.
 * Everything under {@code ~/.config/termlab/}.
 */
public final class RunnerPaths {

    private RunnerPaths() {}

    /** JSON file holding the list of named run configurations. */
    public static Path configsFile() {
        return Paths.get(System.getProperty("user.home"),
            ".config", "termlab", "run-configs.json");
    }

    /** JSON file holding file path → config UUID bindings. */
    public static Path bindingsFile() {
        return Paths.get(System.getProperty("user.home"),
            ".config", "termlab", "run-bindings.json");
    }
}
```

- [ ] **Step 4: Implement RunConfigStore**

```java
// plugins/runner/src/com/termlab/runner/config/RunConfigStore.java
package com.termlab.runner.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory holder for named run configurations, registered as an
 * IntelliJ application service. Persistence is delegated to atomic
 * JSON file I/O at {@code ~/.config/termlab/run-configs.json}.
 *
 * <p>Same pattern as {@code HostStore} in the SSH plugin.
 */
public final class RunConfigStore {

    private static final Logger LOG = Logger.getInstance(RunConfigStore.class);
    private static final int VERSION = 1;

    private final Path path;
    private final List<RunConfig> configs = new ArrayList<>();
    private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    /** No-arg constructor for IntelliJ application-service framework. */
    public RunConfigStore() {
        this(RunnerPaths.configsFile());
    }

    /** Explicit constructor for tests. Loads from disk on construction. */
    public RunConfigStore(@NotNull Path path) {
        this.path = path;
        configs.addAll(loadSilently(path));
    }

    public @NotNull List<RunConfig> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(configs));
    }

    public @Nullable RunConfig getById(@NotNull UUID id) {
        for (RunConfig config : configs) {
            if (config.id().equals(id)) return config;
        }
        return null;
    }

    public void add(@NotNull RunConfig config) {
        configs.add(config);
        fireChanged();
    }

    public boolean remove(@NotNull UUID id) {
        boolean removed = configs.removeIf(c -> c.id().equals(id));
        if (removed) fireChanged();
        return removed;
    }

    public boolean update(@NotNull RunConfig updated) {
        for (int i = 0; i < configs.size(); i++) {
            if (configs.get(i).id().equals(updated.id())) {
                configs.set(i, updated);
                fireChanged();
                return true;
            }
        }
        return false;
    }

    public void save() throws IOException {
        Envelope envelope = new Envelope(VERSION, new ArrayList<>(configs));
        String json = RunnerGson.GSON.toJson(envelope);

        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Files.move(tmp, path,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    }

    public void addChangeListener(@NotNull Runnable listener) {
        changeListeners.add(listener);
    }

    public void removeChangeListener(@NotNull Runnable listener) {
        changeListeners.remove(listener);
    }

    private void fireChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    private static @NotNull List<RunConfig> loadSilently(@NotNull Path path) {
        if (!Files.isRegularFile(path)) return Collections.emptyList();
        try {
            return load(path);
        } catch (Exception e) {
            LOG.warn("TermLab Runner: could not load configs from "
                + path + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    static @NotNull List<RunConfig> load(@NotNull Path source) throws IOException {
        if (!Files.isRegularFile(source)) return Collections.emptyList();
        String raw = Files.readString(source);
        JsonElement rootEl = JsonParser.parseString(raw);
        if (rootEl == null || !rootEl.isJsonObject()) return Collections.emptyList();
        JsonObject root = rootEl.getAsJsonObject();
        if (!root.has("configs") || !root.get("configs").isJsonArray()) {
            return Collections.emptyList();
        }
        Envelope envelope = RunnerGson.GSON.fromJson(root, Envelope.class);
        if (envelope == null || envelope.configs == null) return Collections.emptyList();
        return envelope.configs;
    }

    static final class Envelope {
        int version;
        List<RunConfig> configs;

        Envelope() {}

        Envelope(int version, List<RunConfig> configs) {
            this.version = version;
            this.configs = configs;
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `bash bazel.cmd run //termlab/plugins/runner:runner_test_runner`
Expected: All 11 tests PASS (6 from RunConfigTest + all RunConfigStoreTest)

- [ ] **Step 6: Commit**

```bash
git add plugins/runner/src/com/termlab/runner/config/RunnerPaths.java \
       plugins/runner/src/com/termlab/runner/config/RunConfigStore.java \
       plugins/runner/test/com/termlab/runner/config/RunConfigStoreTest.java
git commit -m "feat(runner): add RunConfigStore with atomic JSON persistence"
```

---

### Task 4: FileConfigBinding + InterpreterRegistry

**Files:**
- Create: `plugins/runner/src/com/termlab/runner/config/FileConfigBinding.java`
- Create: `plugins/runner/src/com/termlab/runner/config/InterpreterRegistry.java`
- Create: `plugins/runner/test/com/termlab/runner/config/FileConfigBindingTest.java`
- Create: `plugins/runner/test/com/termlab/runner/config/InterpreterRegistryTest.java`

- [ ] **Step 1: Write the failing tests for FileConfigBinding**

```java
// plugins/runner/test/com/termlab/runner/config/FileConfigBindingTest.java
package com.termlab.runner.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FileConfigBindingTest {

    @Test
    void newBinding_isEmpty(@TempDir Path tmp) {
        Path file = tmp.resolve("run-bindings.json");
        FileConfigBinding binding = new FileConfigBinding(file);
        assertNull(binding.getConfigId("/some/path.py"));
    }

    @Test
    void bind_thenGet(@TempDir Path tmp) {
        Path file = tmp.resolve("run-bindings.json");
        FileConfigBinding binding = new FileConfigBinding(file);

        UUID configId = UUID.randomUUID();
        binding.bind("/home/user/script.py", configId);
        assertEquals(configId, binding.getConfigId("/home/user/script.py"));
    }

    @Test
    void unbind_removesMapping(@TempDir Path tmp) {
        Path file = tmp.resolve("run-bindings.json");
        FileConfigBinding binding = new FileConfigBinding(file);

        UUID configId = UUID.randomUUID();
        binding.bind("/path.py", configId);
        binding.unbind("/path.py");
        assertNull(binding.getConfigId("/path.py"));
    }

    @Test
    void save_thenLoad_roundTrips(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("run-bindings.json");
        FileConfigBinding binding = new FileConfigBinding(file);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        binding.bind("/a.py", id1);
        binding.bind("/b.sh", id2);
        binding.save();

        FileConfigBinding reloaded = new FileConfigBinding(file);
        assertEquals(id1, reloaded.getConfigId("/a.py"));
        assertEquals(id2, reloaded.getConfigId("/b.sh"));
    }

    @Test
    void load_missingFile_isEmpty(@TempDir Path tmp) {
        Path file = tmp.resolve("nonexistent.json");
        FileConfigBinding binding = new FileConfigBinding(file);
        assertNull(binding.getConfigId("/any"));
    }

    @Test
    void load_corruptedFile_isEmpty(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("run-bindings.json");
        Files.writeString(file, "broken {{{}");
        FileConfigBinding binding = new FileConfigBinding(file);
        assertNull(binding.getConfigId("/any"));
    }
}
```

- [ ] **Step 2: Write the failing tests for InterpreterRegistry**

```java
// plugins/runner/test/com/termlab/runner/config/InterpreterRegistryTest.java
package com.termlab.runner.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InterpreterRegistryTest {

    @Test
    void python_resolves() {
        assertEquals("python3", InterpreterRegistry.interpreterFor("py"));
    }

    @Test
    void shell_resolves() {
        assertEquals("bash", InterpreterRegistry.interpreterFor("sh"));
    }

    @Test
    void javascript_resolves() {
        assertEquals("node", InterpreterRegistry.interpreterFor("js"));
    }

    @Test
    void unknown_returnsNull() {
        assertNull(InterpreterRegistry.interpreterFor("xyz"));
    }

    @Test
    void extractExtension_fromFilename() {
        assertEquals("py", InterpreterRegistry.extractExtension("script.py"));
        assertEquals("sh", InterpreterRegistry.extractExtension("deploy.sh"));
        assertNull(InterpreterRegistry.extractExtension("Makefile"));
    }

    @Test
    void interpreterForFile_combinesLookup() {
        assertEquals("python3", InterpreterRegistry.interpreterForFile("test.py"));
        assertEquals("bash", InterpreterRegistry.interpreterForFile("run.sh"));
        assertNull(InterpreterRegistry.interpreterForFile("Makefile"));
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `bash bazel.cmd run //termlab/plugins/runner:runner_test_runner`
Expected: FAIL — `FileConfigBinding` and `InterpreterRegistry` do not exist

- [ ] **Step 4: Implement FileConfigBinding**

```java
// plugins/runner/src/com/termlab/runner/config/FileConfigBinding.java
package com.termlab.runner.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks which run configuration a file was last executed with.
 * Persisted to {@code ~/.config/termlab/run-bindings.json} as a
 * simple {@code { "bindings": { "/path": "uuid", ... } }} object.
 *
 * <p>Registered as an IntelliJ application service.
 */
public final class FileConfigBinding {

    private static final Logger LOG = Logger.getInstance(FileConfigBinding.class);

    private final Path path;
    private final Map<String, UUID> bindings = new HashMap<>();

    /** No-arg constructor for IntelliJ application-service framework. */
    public FileConfigBinding() {
        this(RunnerPaths.bindingsFile());
    }

    /** Explicit constructor for tests. */
    public FileConfigBinding(@NotNull Path path) {
        this.path = path;
        bindings.putAll(loadSilently(path));
    }

    public @Nullable UUID getConfigId(@NotNull String filePath) {
        return bindings.get(filePath);
    }

    public void bind(@NotNull String filePath, @NotNull UUID configId) {
        bindings.put(filePath, configId);
    }

    public void unbind(@NotNull String filePath) {
        bindings.remove(filePath);
    }

    public void save() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject bindingsObj = new JsonObject();
        for (Map.Entry<String, UUID> entry : bindings.entrySet()) {
            bindingsObj.addProperty(entry.getKey(), entry.getValue().toString());
        }
        root.add("bindings", bindingsObj);
        String json = RunnerGson.GSON.toJson(root);

        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Files.move(tmp, path,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    }

    private static @NotNull Map<String, UUID> loadSilently(@NotNull Path path) {
        if (!Files.isRegularFile(path)) return Map.of();
        try {
            String raw = Files.readString(path);
            JsonElement rootEl = JsonParser.parseString(raw);
            if (rootEl == null || !rootEl.isJsonObject()) return Map.of();
            JsonObject root = rootEl.getAsJsonObject();
            if (!root.has("bindings") || !root.get("bindings").isJsonObject()) {
                return Map.of();
            }
            Map<String, UUID> result = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry :
                    root.getAsJsonObject("bindings").entrySet()) {
                result.put(entry.getKey(),
                    UUID.fromString(entry.getValue().getAsString()));
            }
            return result;
        } catch (Exception e) {
            LOG.warn("TermLab Runner: could not load bindings from "
                + path + ": " + e.getMessage());
            return Map.of();
        }
    }
}
```

- [ ] **Step 5: Implement InterpreterRegistry**

```java
// plugins/runner/src/com/termlab/runner/config/InterpreterRegistry.java
package com.termlab.runner.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Built-in mapping of file extensions to default interpreter commands.
 * Used for quick-run (no saved config) and as defaults when creating
 * a new configuration.
 */
public final class InterpreterRegistry {

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
        Map.entry("py", "python3"),
        Map.entry("sh", "bash"),
        Map.entry("js", "node"),
        Map.entry("rb", "ruby"),
        Map.entry("pl", "perl"),
        Map.entry("go", "go run"),
        Map.entry("java", "java"),
        Map.entry("lua", "lua"),
        Map.entry("php", "php")
    );

    private InterpreterRegistry() {}

    /** @return the default interpreter for the given extension, or {@code null}. */
    public static @Nullable String interpreterFor(@NotNull String extension) {
        return DEFAULTS.get(extension.toLowerCase());
    }

    /**
     * Extract the file extension from a filename (without the leading dot).
     * @return the extension, or {@code null} if the filename has no dot.
     */
    public static @Nullable String extractExtension(@NotNull String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) return null;
        return filename.substring(dot + 1);
    }

    /**
     * Convenience: resolve the default interpreter for a filename.
     * @return the interpreter, or {@code null} if the extension is unknown.
     */
    public static @Nullable String interpreterForFile(@NotNull String filename) {
        String ext = extractExtension(filename);
        if (ext == null) return null;
        return interpreterFor(ext);
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `bash bazel.cmd run //termlab/plugins/runner:runner_test_runner`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```bash
git add plugins/runner/src/com/termlab/runner/config/FileConfigBinding.java \
       plugins/runner/src/com/termlab/runner/config/InterpreterRegistry.java \
       plugins/runner/test/com/termlab/runner/config/FileConfigBindingTest.java \
       plugins/runner/test/com/termlab/runner/config/InterpreterRegistryTest.java
git commit -m "feat(runner): add FileConfigBinding and InterpreterRegistry"
```

---

### Task 5: CommandBuilder

**Files:**
- Create: `plugins/runner/src/com/termlab/runner/execution/CommandBuilder.java`
- Create: `plugins/runner/test/com/termlab/runner/execution/CommandBuilderTest.java`

- [ ] **Step 1: Write the failing tests**

```java
// plugins/runner/test/com/termlab/runner/execution/CommandBuilderTest.java
package com.termlab.runner.execution;

import com.termlab.runner.config.RunConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommandBuilderTest {

    @Test
    void localCommand_simpleScript() {
        RunConfig config = RunConfig.create("Test", null, "python3",
            List.of(), null, Map.of(), List.of());
        List<String> cmd = CommandBuilder.buildLocalCommand(config, "/home/user/test.py");
        assertEquals(List.of("python3", "/home/user/test.py"), cmd);
    }

    @Test
    void localCommand_withInterpreterArgs() {
        RunConfig config = RunConfig.create("Test", null, "python3",
            List.of("-u", "-O"), null, Map.of(), List.of());
        List<String> cmd = CommandBuilder.buildLocalCommand(config, "/test.py");
        assertEquals(List.of("python3", "-u", "-O", "/test.py"), cmd);
    }

    @Test
    void localCommand_withScriptArgs() {
        RunConfig config = RunConfig.create("Test", null, "bash",
            List.of(), null, Map.of(), List.of("--verbose", "input.csv"));
        List<String> cmd = CommandBuilder.buildLocalCommand(config, "/run.sh");
        assertEquals(List.of("bash", "/run.sh", "--verbose", "input.csv"), cmd);
    }

    @Test
    void localCommand_withBothArgs() {
        RunConfig config = RunConfig.create("Test", null, "python3",
            List.of("-u"), null, Map.of(), List.of("--dry-run"));
        List<String> cmd = CommandBuilder.buildLocalCommand(config, "/script.py");
        assertEquals(List.of("python3", "-u", "/script.py", "--dry-run"), cmd);
    }

    @Test
    void remoteCommand_simpleScript() {
        RunConfig config = RunConfig.create("Test", null, "python3",
            List.of(), null, Map.of(), List.of());
        String cmd = CommandBuilder.buildRemoteCommand(config, "/home/user/test.py");
        assertEquals("python3 /home/user/test.py", cmd);
    }

    @Test
    void remoteCommand_withWorkingDirectory() {
        RunConfig config = RunConfig.create("Test", null, "bash",
            List.of(), "/opt/app", Map.of(), List.of());
        String cmd = CommandBuilder.buildRemoteCommand(config, "/opt/app/run.sh");
        assertEquals("cd /opt/app && bash /opt/app/run.sh", cmd);
    }

    @Test
    void remoteCommand_withEnvVars() {
        RunConfig config = RunConfig.create("Test", null, "python3",
            List.of(), null, Map.of("DEBUG", "1", "PORT", "8080"), List.of());
        String cmd = CommandBuilder.buildRemoteCommand(config, "/test.py");
        // Env vars should be sorted for deterministic output
        assertTrue(cmd.contains("DEBUG=1"));
        assertTrue(cmd.contains("PORT=8080"));
        assertTrue(cmd.endsWith("python3 /test.py"));
    }

    @Test
    void remoteCommand_withEverything() {
        RunConfig config = RunConfig.create("Test", null, "python3",
            List.of("-u"), "/home/deploy",
            Map.of("ENV", "prod"), List.of("--run"));
        String cmd = CommandBuilder.buildRemoteCommand(config, "/home/deploy/app.py");
        assertEquals("cd /home/deploy && ENV=prod python3 -u /home/deploy/app.py --run", cmd);
    }

    @Test
    void remoteCommand_shellEscapesSpacesInPath() {
        RunConfig config = RunConfig.create("Test", null, "bash",
            List.of(), null, Map.of(), List.of());
        String cmd = CommandBuilder.buildRemoteCommand(config, "/home/user/my script.sh");
        assertEquals("bash '/home/user/my script.sh'", cmd);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash bazel.cmd run //termlab/plugins/runner:runner_test_runner`
Expected: FAIL — `CommandBuilder` does not exist

- [ ] **Step 3: Implement CommandBuilder**

```java
// plugins/runner/src/com/termlab/runner/execution/CommandBuilder.java
package com.termlab.runner.execution;

import com.termlab.runner.config.RunConfig;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Assembles execution commands from a {@link RunConfig} and script path.
 * Two variants: local (returns a {@code List<String>} for
 * {@link ProcessBuilder}) and remote (returns a single shell command
 * string for an SSH exec channel).
 */
public final class CommandBuilder {

    private CommandBuilder() {}

    /**
     * Build a command list for local execution via {@link ProcessBuilder}.
     * Format: {@code [interpreter, ...args, scriptPath, ...scriptArgs]}
     */
    public static @NotNull List<String> buildLocalCommand(
        @NotNull RunConfig config,
        @NotNull String scriptPath
    ) {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.interpreter());
        cmd.addAll(config.args());
        cmd.add(scriptPath);
        cmd.addAll(config.scriptArgs());
        return cmd;
    }

    /**
     * Build a single shell command string for remote SSH exec.
     * Format: {@code [cd dir &&] [ENV=val ...] interpreter [args] scriptPath [scriptArgs]}
     */
    public static @NotNull String buildRemoteCommand(
        @NotNull RunConfig config,
        @NotNull String scriptPath
    ) {
        StringBuilder sb = new StringBuilder();

        // Working directory
        if (config.workingDirectory() != null) {
            sb.append("cd ").append(shellQuoteIfNeeded(config.workingDirectory()))
              .append(" && ");
        }

        // Environment variables (sorted for deterministic output)
        if (!config.envVars().isEmpty()) {
            TreeMap<String, String> sorted = new TreeMap<>(config.envVars());
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                sb.append(entry.getKey()).append("=")
                  .append(shellQuoteIfNeeded(entry.getValue())).append(" ");
            }
        }

        // Interpreter + args
        sb.append(config.interpreter());
        for (String arg : config.args()) {
            sb.append(" ").append(shellQuoteIfNeeded(arg));
        }

        // Script path
        sb.append(" ").append(shellQuoteIfNeeded(scriptPath));

        // Script args
        for (String arg : config.scriptArgs()) {
            sb.append(" ").append(shellQuoteIfNeeded(arg));
        }

        return sb.toString();
    }

    /**
     * Quote a value with single quotes if it contains characters that
     * need shell escaping. Safe values are left unquoted for readability.
     */
    static @NotNull String shellQuoteIfNeeded(@NotNull String value) {
        if (value.isEmpty()) return "''";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '"' || c == '\'' || c == '\\' ||
                c == '$' || c == '`' || c == '(' || c == ')' || c == '&' ||
                c == '|' || c == ';' || c == '<' || c == '>' || c == '*' ||
                c == '?' || c == '[' || c == ']' || c == '{' || c == '}' ||
                c == '!' || c == '#' || c == '~') {
                // Wrap in single quotes, escaping embedded single quotes
                return "'" + value.replace("'", "'\\''") + "'";
            }
        }
        return value;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash bazel.cmd run //termlab/plugins/runner:runner_test_runner`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add plugins/runner/src/com/termlab/runner/execution/CommandBuilder.java \
       plugins/runner/test/com/termlab/runner/execution/CommandBuilderTest.java
git commit -m "feat(runner): add CommandBuilder for local and remote commands"
```

---

### Task 6: ScriptExecution Interface + LocalExecution

**Files:**
- Create: `plugins/runner/src/com/termlab/runner/execution/ScriptExecution.java`
- Create: `plugins/runner/src/com/termlab/runner/execution/LocalExecution.java`

- [ ] **Step 1: Create the ScriptExecution interface**

```java
// plugins/runner/src/com/termlab/runner/execution/ScriptExecution.java
package com.termlab.runner.execution;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;

/**
 * Handle to a running script execution. Provides access to the live
 * output stream, lifecycle control, and exit status.
 */
public interface ScriptExecution {

    /**
     * @return the merged stdout+stderr stream. Callers should read
     *         this on a background thread.
     */
    @NotNull InputStream getOutputStream();

    /** Send SIGINT (or equivalent) to the running process. */
    void sendInterrupt();

    /** Forcefully kill the process. */
    void kill();

    /** @return the exit code, or {@code null} if still running. */
    @Nullable Integer getExitCode();

    /** Register a callback invoked when the process terminates. */
    void addTerminationListener(@NotNull Runnable listener);

    /** @return {@code true} if the process is still running. */
    boolean isRunning();
}
```

- [ ] **Step 2: Implement LocalExecution**

```java
// plugins/runner/src/com/termlab/runner/execution/LocalExecution.java
package com.termlab.runner.execution;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Executes a script locally via {@link ProcessBuilder}. Merges stdout
 * and stderr into a single stream. Supports SIGINT via
 * {@link Process#destroy()} and SIGKILL via
 * {@link Process#destroyForcibly()}.
 */
public final class LocalExecution implements ScriptExecution {

    private static final Logger LOG = Logger.getInstance(LocalExecution.class);

    private final Process process;
    private final CopyOnWriteArrayList<Runnable> terminationListeners =
        new CopyOnWriteArrayList<>();

    private LocalExecution(@NotNull Process process) {
        this.process = process;
        Thread waiter = new Thread(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (Runnable listener : terminationListeners) {
                listener.run();
            }
        }, "TermLabRunner-local-waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * Start a local script execution.
     *
     * @param command          the full command list
     *                         (interpreter + args + script + scriptArgs)
     * @param workingDirectory working directory, or {@code null} for
     *                         the script's parent
     * @param envVars          environment variable overrides
     * @param scriptPath       path to the script (used to derive default
     *                         working directory)
     * @return a running {@link LocalExecution}
     */
    public static @NotNull LocalExecution start(
        @NotNull List<String> command,
        @Nullable String workingDirectory,
        @NotNull Map<String, String> envVars,
        @NotNull String scriptPath
    ) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        if (workingDirectory != null) {
            pb.directory(new File(workingDirectory));
        } else {
            File parent = new File(scriptPath).getParentFile();
            if (parent != null) pb.directory(parent);
        }

        if (!envVars.isEmpty()) {
            pb.environment().putAll(envVars);
        }

        LOG.info("TermLab Runner: starting local execution: " + command);
        Process process = pb.start();
        return new LocalExecution(process);
    }

    @Override
    public @NotNull InputStream getOutputStream() {
        return process.getInputStream();
    }

    @Override
    public void sendInterrupt() {
        LOG.info("TermLab Runner: sending interrupt to local process");
        process.destroy();
    }

    @Override
    public void kill() {
        LOG.info("TermLab Runner: force-killing local process");
        process.destroyForcibly();
    }

    @Override
    public @Nullable Integer getExitCode() {
        if (process.isAlive()) return null;
        return process.exitValue();
    }

    @Override
    public void addTerminationListener(@NotNull Runnable listener) {
        terminationListeners.add(listener);
        // If already terminated, fire immediately
        if (!process.isAlive()) listener.run();
    }

    @Override
    public boolean isRunning() {
        return process.isAlive();
    }
}
```

- [ ] **Step 3: Verify build compiles**

Run: `bash bazel.cmd build //termlab/plugins/runner`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add plugins/runner/src/com/termlab/runner/execution/ScriptExecution.java \
       plugins/runner/src/com/termlab/runner/execution/LocalExecution.java
git commit -m "feat(runner): add ScriptExecution interface and LocalExecution"
```

---

### Task 7: RemoteExecution

**Files:**
- Create: `plugins/runner/src/com/termlab/runner/execution/RemoteExecution.java`

- [ ] **Step 1: Implement RemoteExecution**

```java
// plugins/runner/src/com/termlab/runner/execution/RemoteExecution.java
package com.termlab.runner.execution;

import com.intellij.openapi.diagnostic.Logger;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.EnumSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Executes a script on a remote host via an SSH exec channel. The
 * caller provides an already-authenticated {@link ClientSession}
 * obtained from {@code TermLabSshClient.connectSession()}.
 *
 * <p>Owns the exec channel but NOT the session — the caller must
 * close the session when done (typically after the execution finishes
 * and the output has been drained).
 */
public final class RemoteExecution implements ScriptExecution {

    private static final Logger LOG = Logger.getInstance(RemoteExecution.class);

    private final ClientSession session;
    private final ChannelExec channel;
    private final InputStream mergedOutput;
    private final CopyOnWriteArrayList<Runnable> terminationListeners =
        new CopyOnWriteArrayList<>();

    private RemoteExecution(
        @NotNull ClientSession session,
        @NotNull ChannelExec channel,
        @NotNull InputStream mergedOutput
    ) {
        this.session = session;
        this.channel = channel;
        this.mergedOutput = mergedOutput;

        Thread waiter = new Thread(() -> {
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L);
            for (Runnable listener : terminationListeners) {
                listener.run();
            }
        }, "TermLabRunner-remote-waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * Start a remote script execution over SSH.
     *
     * @param session an authenticated SSH session (caller retains
     *                ownership and must close it after execution)
     * @param command the full shell command to execute
     * @return a running {@link RemoteExecution}
     */
    public static @NotNull RemoteExecution start(
        @NotNull ClientSession session,
        @NotNull String command
    ) throws IOException {
        LOG.info("TermLab Runner: starting remote execution: " + command);
        ChannelExec channel = session.createExecChannel(command);
        channel.open().verify(java.time.Duration.ofSeconds(10));

        // Merge stdout and stderr into a single stream
        InputStream stdout = channel.getInvertedOut();
        InputStream stderr = channel.getInvertedErr();
        InputStream merged = new SequenceInputStream(stdout, stderr);

        return new RemoteExecution(session, channel, merged);
    }

    @Override
    public @NotNull InputStream getOutputStream() {
        return mergedOutput;
    }

    @Override
    public void sendInterrupt() {
        try {
            LOG.info("TermLab Runner: sending INT signal to remote process");
            channel.sendSignal("INT");
        } catch (IOException e) {
            LOG.warn("TermLab Runner: failed to send INT signal: " + e.getMessage());
        }
    }

    @Override
    public void kill() {
        LOG.info("TermLab Runner: closing remote exec channel");
        try {
            channel.close(true);
        } catch (Exception ignored) {
        }
        try {
            session.close(true);
        } catch (Exception ignored) {
        }
    }

    @Override
    public @Nullable Integer getExitCode() {
        if (channel.isOpen() && !channel.isClosed()) return null;
        Integer status = channel.getExitStatus();
        return status;
    }

    @Override
    public void addTerminationListener(@NotNull Runnable listener) {
        terminationListeners.add(listener);
        if (!channel.isOpen() || channel.isClosed()) listener.run();
    }

    @Override
    public boolean isRunning() {
        return channel.isOpen() && !channel.isClosed();
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `bash bazel.cmd build //termlab/plugins/runner`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add plugins/runner/src/com/termlab/runner/execution/RemoteExecution.java
git commit -m "feat(runner): add RemoteExecution via SSH exec channel"
```

---

### Task 8: Output Tool Window — Factory + Panel + Tab

**Files:**
- Create: `plugins/runner/src/com/termlab/runner/output/ScriptOutputToolWindowFactory.java`
- Create: `plugins/runner/src/com/termlab/runner/output/ScriptOutputPanel.java`
- Create: `plugins/runner/src/com/termlab/runner/output/OutputTabHeader.java`
- Create: `plugins/runner/src/com/termlab/runner/output/OutputTab.java`

- [ ] **Step 1: Create ScriptOutputToolWindowFactory**

```java
// plugins/runner/src/com/termlab/runner/output/ScriptOutputToolWindowFactory.java
package com.termlab.runner.output;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class ScriptOutputToolWindowFactory implements ToolWindowFactory {

    public static final String ID = "Script Output";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ScriptOutputPanel panel = new ScriptOutputPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setCloseable(false);
        toolWindow.getContentManager().addContent(content);
    }
}
```

- [ ] **Step 2: Create OutputTabHeader**

```java
// plugins/runner/src/com/termlab/runner/output/OutputTabHeader.java
package com.termlab.runner.output;

import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Header bar shown above the output text area in each tab. Displays
 * interpreter, host, start time, and current status.
 */
final class OutputTabHeader extends JPanel {

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final JLabel statusLabel;

    OutputTabHeader(
        @NotNull String interpreter,
        @NotNull String hostLabel,
        @NotNull Instant startTime
    ) {
        super(new FlowLayout(FlowLayout.LEFT, 8, 2));
        setBorder(JBUI.Borders.emptyBottom(4));

        add(new JLabel(interpreter));
        add(new JLabel("•"));
        add(new JLabel(hostLabel));
        add(new JLabel("•"));
        add(new JLabel(TIME_FMT.format(startTime)));
        add(new JLabel("•"));

        statusLabel = new JLabel("Running...");
        add(statusLabel);
    }

    void setFinished(int exitCode) {
        if (exitCode == 0) {
            statusLabel.setText("Finished (exit 0)");
            statusLabel.setForeground(new Color(0, 128, 0));
        } else {
            statusLabel.setText("Failed (exit " + exitCode + ")");
            statusLabel.setForeground(new Color(192, 0, 0));
        }
    }

    void setStatus(@NotNull String text) {
        statusLabel.setText(text);
    }
}
```

- [ ] **Step 3: Create OutputTab**

```java
// plugins/runner/src/com/termlab/runner/output/OutputTab.java
package com.termlab.runner.output;

import com.termlab.runner.execution.ScriptExecution;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * A single output tab representing one script execution. Contains a
 * header bar and a scrolling text area that streams output.
 */
final class OutputTab extends JPanel {

    private static final Logger LOG = Logger.getInstance(OutputTab.class);
    private static final int BUFFER_SIZE = 4096;

    private final ScriptExecution execution;
    private final OutputTabHeader header;
    private final JTextArea textArea;
    private final JScrollPane scrollPane;
    private volatile boolean userScrolledAway = false;

    OutputTab(
        @NotNull ScriptExecution execution,
        @NotNull String interpreter,
        @NotNull String hostLabel
    ) {
        super(new BorderLayout());
        this.execution = execution;

        this.header = new OutputTabHeader(interpreter, hostLabel, Instant.now());
        add(header, BorderLayout.NORTH);

        this.textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        textArea.setBorder(JBUI.Borders.empty(4));

        this.scrollPane = new JScrollPane(textArea);
        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            JScrollBar sb = scrollPane.getVerticalScrollBar();
            int extent = sb.getModel().getExtent();
            userScrolledAway = (sb.getValue() + extent) < sb.getMaximum() - 10;
        });
        add(scrollPane, BorderLayout.CENTER);

        startOutputReader();

        execution.addTerminationListener(() ->
            SwingUtilities.invokeLater(() -> {
                Integer exitCode = execution.getExitCode();
                header.setFinished(exitCode != null ? exitCode : -1);
            })
        );
    }

    private void startOutputReader() {
        Thread reader = new Thread(() -> {
            try (InputStream in = execution.getOutputStream()) {
                byte[] buf = new byte[BUFFER_SIZE];
                StringBuilder batch = new StringBuilder();
                long lastFlush = System.currentTimeMillis();
                int n;
                while ((n = in.read(buf)) >= 0) {
                    batch.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    long now = System.currentTimeMillis();
                    if (now - lastFlush >= 50 || batch.length() > 8192) {
                        String text = batch.toString();
                        batch.setLength(0);
                        lastFlush = now;
                        SwingUtilities.invokeLater(() -> appendText(text));
                    }
                }
                // Flush remaining
                if (batch.length() > 0) {
                    String text = batch.toString();
                    SwingUtilities.invokeLater(() -> appendText(text));
                }
            } catch (Exception e) {
                LOG.warn("TermLab Runner: output reader error: " + e.getMessage());
            }
        }, "TermLabRunner-output-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void appendText(@NotNull String text) {
        textArea.append(text);
        if (!userScrolledAway) {
            textArea.setCaretPosition(textArea.getDocument().getLength());
        }
    }

    @NotNull ScriptExecution execution() {
        return execution;
    }

    void clearOutput() {
        textArea.setText("");
    }
}
```

- [ ] **Step 4: Create ScriptOutputPanel**

```java
// plugins/runner/src/com/termlab/runner/output/ScriptOutputPanel.java
package com.termlab.runner.output;

import com.termlab.runner.execution.ScriptExecution;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Tabbed panel that hosts all script output tabs. Registered as the
 * main content of the "Script Output" tool window.
 */
public final class ScriptOutputPanel extends JPanel {

    private static final int MAX_TABS = 10;

    private final Project project;
    private final JTabbedPane tabbedPane;
    private final List<OutputTab> tabs = new ArrayList<>();

    public ScriptOutputPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        add(tabbedPane, BorderLayout.CENTER);
    }

    /**
     * Add a new output tab for a script execution.
     *
     * @param execution   the running execution handle
     * @param filename    script filename for the tab title
     * @param hostLabel   host label for the tab title
     * @param interpreter the interpreter command for the header
     * @return the created {@link OutputTab}
     */
    public @NotNull OutputTab addExecution(
        @NotNull ScriptExecution execution,
        @NotNull String filename,
        @NotNull String hostLabel,
        @NotNull String interpreter
    ) {
        evictOldTabs();

        OutputTab tab = new OutputTab(execution, interpreter, hostLabel);
        tabs.add(tab);

        String title = filename + " @ " + hostLabel;
        tabbedPane.addTab(title, tab);
        int index = tabbedPane.indexOfComponent(tab);
        tabbedPane.setTabComponentAt(index, createTabLabel(title, tab));
        tabbedPane.setSelectedComponent(tab);

        return tab;
    }

    private @NotNull JPanel createTabLabel(
        @NotNull String title,
        @NotNull OutputTab tab
    ) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        panel.add(new JLabel(title));

        // Stop/Re-run button
        JButton stopButton = new JButton(AllIcons.Actions.Suspend);
        stopButton.setToolTipText("Stop");
        stopButton.setBorderPainted(false);
        stopButton.setContentAreaFilled(false);
        stopButton.setPreferredSize(new Dimension(16, 16));
        stopButton.addActionListener(e -> {
            if (tab.execution().isRunning()) {
                tab.execution().sendInterrupt();
                // Second click will kill
                stopButton.setToolTipText("Force Kill");
                stopButton.removeActionListener(stopButton.getActionListeners()[0]);
                stopButton.addActionListener(e2 -> tab.execution().kill());
            }
        });
        tab.execution().addTerminationListener(() ->
            SwingUtilities.invokeLater(() -> {
                stopButton.setEnabled(false);
                stopButton.setToolTipText("Finished");
            })
        );
        panel.add(stopButton);

        // Close button
        JButton closeButton = new JButton(AllIcons.Actions.Close);
        closeButton.setToolTipText("Close");
        closeButton.setBorderPainted(false);
        closeButton.setContentAreaFilled(false);
        closeButton.setPreferredSize(new Dimension(16, 16));
        closeButton.addActionListener(e -> {
            if (tab.execution().isRunning()) {
                tab.execution().kill();
            }
            tabs.remove(tab);
            tabbedPane.remove(tab);
        });
        panel.add(closeButton);

        return panel;
    }

    private void evictOldTabs() {
        while (tabs.size() >= MAX_TABS) {
            // Find oldest completed tab
            OutputTab toRemove = null;
            for (OutputTab tab : tabs) {
                if (!tab.execution().isRunning()) {
                    toRemove = tab;
                    break;
                }
            }
            if (toRemove == null) break; // All tabs are running
            tabs.remove(toRemove);
            tabbedPane.remove(toRemove);
        }
    }
}
```

- [ ] **Step 5: Verify build compiles**

Run: `bash bazel.cmd build //termlab/plugins/runner`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add plugins/runner/src/com/termlab/runner/output/
git commit -m "feat(runner): add Script Output tool window with tabbed output"
```

---

### Task 9: SaveBeforeRunHelper

**Files:**
- Create: `plugins/runner/src/com/termlab/runner/actions/SaveBeforeRunHelper.java`

- [ ] **Step 1: Implement SaveBeforeRunHelper**

```java
// plugins/runner/src/com/termlab/runner/actions/SaveBeforeRunHelper.java
package com.termlab.runner.actions;

import com.termlab.editor.scratch.ScratchMarker;
import com.termlab.editor.scratch.SaveAsHelper;
import com.termlab.sftp.vfs.SftpUrl;
import com.termlab.sftp.vfs.SftpVirtualFile;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Ensures a file is saved to disk before execution. Handles three cases:
 * <ol>
 *   <li>Unsaved scratch → triggers Save As dialog</li>
 *   <li>Dirty saved file → auto-saves silently</li>
 *   <li>Clean saved file → no-op</li>
 * </ol>
 *
 * After save, resolves whether the file is local or remote and returns
 * the execution context.
 */
final class SaveBeforeRunHelper {

    private SaveBeforeRunHelper() {}

    /**
     * Result of the save-before-run check. Contains the file path and
     * whether execution should target a local or remote host.
     */
    record RunTarget(
        @NotNull String scriptPath,
        @Nullable UUID sftpHostId
    ) {
        boolean isLocal() { return sftpHostId == null; }
    }

    /**
     * Ensure the file is saved and resolve its run target.
     *
     * @return the target, or {@code null} if the user cancelled
     *         the save dialog or the file cannot be resolved
     */
    static @Nullable RunTarget resolve(@NotNull Project project, @NotNull VirtualFile file) {
        // Case 1: unsaved scratch
        if (file instanceof LightVirtualFile lvf
            && lvf.getUserData(ScratchMarker.KEY) == Boolean.TRUE) {
            SaveAsHelper.saveAs(project, lvf);
            // After save-as, the scratch tab is closed and the new file
            // is opened. Find the new active file.
            VirtualFile saved = activeFile(project);
            if (saved == null || saved == file) return null; // user cancelled
            file = saved;
        }

        // Case 2: dirty saved file
        Document doc = FileDocumentManager.getInstance().getDocument(file);
        if (doc != null && FileDocumentManager.getInstance().isDocumentUnsaved(doc)) {
            ApplicationManager.getApplication().runWriteAction(() ->
                FileDocumentManager.getInstance().saveDocument(doc));
        }

        // Resolve path and host
        if (file instanceof SftpVirtualFile sftp) {
            SftpUrl url = SftpUrl.parse(sftp.getUrl());
            if (url == null) return null;
            return new RunTarget(url.remotePath(), url.hostId());
        }

        String localPath = file.getPath();
        return new RunTarget(localPath, null);
    }

    private static @Nullable VirtualFile activeFile(@NotNull Project project) {
        var editor = FileEditorManager.getInstance(project).getSelectedEditor();
        return editor != null ? editor.getFile() : null;
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `bash bazel.cmd build //termlab/plugins/runner`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add plugins/runner/src/com/termlab/runner/actions/SaveBeforeRunHelper.java
git commit -m "feat(runner): add SaveBeforeRunHelper for save-before-run flow"
```

---

### Task 10: RunScriptAction

**Files:**
- Create: `plugins/runner/src/com/termlab/runner/actions/RunScriptAction.java`

- [ ] **Step 1: Implement RunScriptAction**

```java
// plugins/runner/src/com/termlab/runner/actions/RunScriptAction.java
package com.termlab.runner.actions;

import com.termlab.runner.actions.SaveBeforeRunHelper.RunTarget;
import com.termlab.runner.config.FileConfigBinding;
import com.termlab.runner.config.InterpreterRegistry;
import com.termlab.runner.config.RunConfig;
import com.termlab.runner.config.RunConfigStore;
import com.termlab.runner.execution.CommandBuilder;
import com.termlab.runner.execution.LocalExecution;
import com.termlab.runner.execution.RemoteExecution;
import com.termlab.runner.execution.ScriptExecution;
import com.termlab.runner.output.ScriptOutputPanel;
import com.termlab.runner.output.ScriptOutputToolWindowFactory;
import com.termlab.ssh.client.TermLabSshClient;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.client.SshResolvedCredential;
import com.termlab.ssh.credentials.SshCredentialResolver;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.SshHost;
import com.termlab.ssh.model.VaultAuth;
import com.intellij.icons.AllIcons;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.apache.sshd.client.session.ClientSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Run the current editor file. If a bound configuration exists, uses it.
 * Otherwise performs a quick-run with auto-detected defaults and offers
 * to save the configuration after execution.
 */
public final class RunScriptAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(RunScriptAction.class);
    private static final String NOTIFICATION_GROUP = "TermLab Script Runner";

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = activeFile(e);
        boolean enabled = file != null &&
            InterpreterRegistry.interpreterForFile(file.getName()) != null;
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        VirtualFile file = activeFile(e);
        if (file == null) return;

        RunTarget target = SaveBeforeRunHelper.resolve(project, file);
        if (target == null) return;

        // Re-fetch active file in case save-as changed it
        VirtualFile currentFile = activeFile(e);
        if (currentFile == null) currentFile = file;
        String filename = currentFile.getName();

        RunConfigStore configStore = ApplicationManager.getApplication()
            .getService(RunConfigStore.class);
        FileConfigBinding binding = ApplicationManager.getApplication()
            .getService(FileConfigBinding.class);

        UUID boundConfigId = binding.getConfigId(target.scriptPath());
        RunConfig config = boundConfigId != null ? configStore.getById(boundConfigId) : null;

        boolean isQuickRun = (config == null);
        if (isQuickRun) {
            String interpreter = InterpreterRegistry.interpreterForFile(filename);
            if (interpreter == null) {
                notifyError(project, "No interpreter configured for " + filename);
                return;
            }
            UUID hostId = target.sftpHostId();
            config = RunConfig.create("Quick Run", hostId, interpreter,
                List.of(), null, Map.of(), List.of());
        }

        RunConfig finalConfig = config;
        boolean finalIsQuickRun = isQuickRun;
        String scriptPath = target.scriptPath();

        // Execute on a background thread
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ScriptExecution execution = startExecution(finalConfig, target);
                String hostLabel = resolveHostLabel(finalConfig);

                // Show output on EDT
                ApplicationManager.getApplication().invokeLater(() -> {
                    showOutput(project, execution, filename, hostLabel,
                        finalConfig.interpreter());

                    if (finalIsQuickRun) {
                        offerSaveConfig(project, finalConfig, scriptPath);
                    } else {
                        // Update binding
                        binding.bind(scriptPath, finalConfig.id());
                        saveSilently(binding);
                    }
                });
            } catch (IOException | SshConnectException ex) {
                ApplicationManager.getApplication().invokeLater(() ->
                    notifyError(project, "Execution failed: " + ex.getMessage()));
            }
        });
    }

    private @NotNull ScriptExecution startExecution(
        @NotNull RunConfig config,
        @NotNull RunTarget target
    ) throws IOException, SshConnectException {
        if (config.isLocal() && target.isLocal()) {
            List<String> cmd = CommandBuilder.buildLocalCommand(config, target.scriptPath());
            return LocalExecution.start(cmd, config.workingDirectory(),
                config.envVars(), target.scriptPath());
        }

        // Remote execution
        UUID hostId = config.hostId() != null ? config.hostId() : target.sftpHostId();
        if (hostId == null) {
            throw new IOException("No host configured for remote execution");
        }

        HostStore hostStore = ApplicationManager.getApplication()
            .getService(HostStore.class);
        SshHost host = hostStore.findById(hostId);
        if (host == null) {
            throw new IOException("Host not found (may have been deleted)");
        }

        TermLabSshClient sshClient = ApplicationManager.getApplication()
            .getService(TermLabSshClient.class);

        SshResolvedCredential credential = resolveCredential(host);
        if (credential == null) {
            throw new IOException("Could not resolve credentials for " + host.label());
        }

        try {
            ClientSession session = sshClient.connectSession(
                host, credential,
                new com.termlab.ssh.client.TermLabServerKeyVerifier());
            String command = CommandBuilder.buildRemoteCommand(config, target.scriptPath());
            return RemoteExecution.start(session, command);
        } finally {
            credential.close();
        }
    }

    private @Nullable SshResolvedCredential resolveCredential(@NotNull SshHost host) {
        if (!(host.auth() instanceof VaultAuth vault)) return null;
        if (vault.credentialId() == null) return null;
        return new SshCredentialResolver().resolve(vault.credentialId(), host.username());
    }

    private @NotNull String resolveHostLabel(@NotNull RunConfig config) {
        if (config.isLocal()) return "local";
        HostStore hostStore = ApplicationManager.getApplication()
            .getService(HostStore.class);
        SshHost host = config.hostId() != null
            ? hostStore.findById(config.hostId()) : null;
        return host != null ? host.label() : "remote";
    }

    private void showOutput(
        @NotNull Project project,
        @NotNull ScriptExecution execution,
        @NotNull String filename,
        @NotNull String hostLabel,
        @NotNull String interpreter
    ) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(ScriptOutputToolWindowFactory.ID);
        if (toolWindow == null) return;

        toolWindow.show(() -> {
            ScriptOutputPanel panel = findOutputPanel(toolWindow);
            if (panel != null) {
                panel.addExecution(execution, filename, hostLabel, interpreter);
            }
        });
    }

    private @Nullable ScriptOutputPanel findOutputPanel(@NotNull ToolWindow toolWindow) {
        var content = toolWindow.getContentManager().getContent(0);
        if (content == null) return null;
        var component = content.getComponent();
        if (component instanceof ScriptOutputPanel panel) return panel;
        return null;
    }

    private void offerSaveConfig(
        @NotNull Project project,
        @NotNull RunConfig config,
        @NotNull String scriptPath
    ) {
        Notification notification = new Notification(
            NOTIFICATION_GROUP,
            "Script executed",
            "Save as run configuration?",
            NotificationType.INFORMATION);
        notification.addAction(new AnAction("Save Configuration") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                notification.expire();
                RunConfigStore store = ApplicationManager.getApplication()
                    .getService(RunConfigStore.class);
                store.add(config);
                FileConfigBinding binding = ApplicationManager.getApplication()
                    .getService(FileConfigBinding.class);
                binding.bind(scriptPath, config.id());
                saveSilently(store);
                saveSilently(binding);
            }
        });
        Notifications.Bus.notify(notification, project);
    }

    private static void saveSilently(@NotNull RunConfigStore store) {
        try { store.save(); } catch (IOException e) {
            LOG.warn("TermLab Runner: failed to save configs: " + e.getMessage());
        }
    }

    private static void saveSilently(@NotNull FileConfigBinding binding) {
        try { binding.save(); } catch (IOException e) {
            LOG.warn("TermLab Runner: failed to save bindings: " + e.getMessage());
        }
    }

    private void notifyError(@NotNull Project project, @NotNull String message) {
        Notifications.Bus.notify(new Notification(
            NOTIFICATION_GROUP, "Run Script", message,
            NotificationType.ERROR), project);
    }

    private static @Nullable VirtualFile activeFile(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return null;
        FileEditor editor = FileEditorManager.getInstance(project).getSelectedEditor();
        if (editor == null) return null;
        return editor.getFile();
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `bash bazel.cmd build //termlab/plugins/runner`
Expected: BUILD SUCCESS (may need to adjust imports if `TermLabServerKeyVerifier` has a different path — check and fix)

- [ ] **Step 3: Commit**

```bash
git add plugins/runner/src/com/termlab/runner/actions/RunScriptAction.java
git commit -m "feat(runner): add RunScriptAction with local and remote execution"
```

---

### Task 11: EditConfigAction + Configuration Dialog

**Files:**
- Create: `plugins/runner/src/com/termlab/runner/actions/EditConfigAction.java`
- Create: `plugins/runner/src/com/termlab/runner/config/RunConfigDialog.java`

- [ ] **Step 1: Implement RunConfigDialog**

```java
// plugins/runner/src/com/termlab/runner/config/RunConfigDialog.java
package com.termlab.runner.config;

import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.SshHost;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Modal dialog for managing named run configurations. Left panel shows
 * the config list; right panel shows the selected config's details.
 */
public final class RunConfigDialog extends DialogWrapper {

    private final RunConfigStore configStore;
    private final HostStore hostStore;
    private final DefaultListModel<RunConfig> listModel = new DefaultListModel<>();
    private final JList<RunConfig> configList = new JList<>(listModel);

    // Right-panel fields
    private final JTextField nameField = new JTextField(20);
    private final JComboBox<HostItem> hostCombo = new JComboBox<>();
    private final JTextField interpreterField = new JTextField(20);
    private final JTextField scriptArgsField = new JTextField(20);
    private final JTextField workDirField = new JTextField(20);
    private final DefaultTableModel envTableModel =
        new DefaultTableModel(new String[]{"Variable", "Value"}, 0);
    private final JTable envTable = new JTable(envTableModel);

    private final Runnable hostChangeListener;

    /** Wrapper for the host combo box items. */
    private record HostItem(@Nullable UUID hostId, @NotNull String label) {
        @Override public String toString() { return label; }
    }

    public RunConfigDialog(
        @NotNull Project project,
        @Nullable RunConfig preselect,
        @Nullable String defaultInterpreter
    ) {
        super(project, true);
        this.configStore = ApplicationManager.getApplication()
            .getService(RunConfigStore.class);
        this.hostStore = ApplicationManager.getApplication()
            .getService(HostStore.class);

        setTitle("Run Configurations");

        // Load existing configs into list model
        for (RunConfig config : configStore.getAll()) {
            listModel.addElement(config);
        }

        // Listen for host store changes
        hostChangeListener = this::refreshHostCombo;
        hostStore.addChangeListener(hostChangeListener);

        refreshHostCombo();

        configList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        configList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof RunConfig rc) setText(rc.name());
                return this;
            }
        });
        configList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadSelectedConfig();
        });

        // Pre-select or pre-populate
        if (preselect != null) {
            for (int i = 0; i < listModel.size(); i++) {
                if (listModel.get(i).id().equals(preselect.id())) {
                    configList.setSelectedIndex(i);
                    break;
                }
            }
        }
        if (defaultInterpreter != null && interpreterField.getText().isEmpty()) {
            interpreterField.setText(defaultInterpreter);
        }

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setPreferredSize(new Dimension(700, 450));

        // Left: config list with add/remove buttons
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.add(new JScrollPane(configList), BorderLayout.CENTER);

        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addButton = new JButton("Add");
        addButton.addActionListener(e -> addConfig());
        JButton removeButton = new JButton("Remove");
        removeButton.addActionListener(e -> removeConfig());
        JButton duplicateButton = new JButton("Duplicate");
        duplicateButton.addActionListener(e -> duplicateConfig());
        listButtons.add(addButton);
        listButtons.add(removeButton);
        listButtons.add(duplicateButton);
        leftPanel.add(listButtons, BorderLayout.SOUTH);
        leftPanel.setPreferredSize(new Dimension(200, 0));

        // Right: config detail fields
        JPanel rightPanel = new JPanel(new GridBagLayout());
        rightPanel.setBorder(JBUI.Borders.emptyLeft(8));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(4);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;
        addField(rightPanel, gbc, row++, "Name:", nameField);
        addField(rightPanel, gbc, row++, "Host:", hostCombo);
        addField(rightPanel, gbc, row++, "Interpreter:", interpreterField);
        addField(rightPanel, gbc, row++, "Script Arguments:", scriptArgsField);
        addField(rightPanel, gbc, row++, "Working Directory:", workDirField);

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1; gbc.weighty = 1;
        JPanel envPanel = new JPanel(new BorderLayout());
        envPanel.add(new JLabel("Environment Variables:"), BorderLayout.NORTH);
        envPanel.add(new JScrollPane(envTable), BorderLayout.CENTER);
        JPanel envButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addEnv = new JButton("Add");
        addEnv.addActionListener(e -> envTableModel.addRow(new String[]{"", ""}));
        JButton removeEnv = new JButton("Remove");
        removeEnv.addActionListener(e -> {
            int selected = envTable.getSelectedRow();
            if (selected >= 0) envTableModel.removeRow(selected);
        });
        envButtons.add(addEnv);
        envButtons.add(removeEnv);
        envPanel.add(envButtons, BorderLayout.SOUTH);
        rightPanel.add(envPanel, gbc);

        panel.add(leftPanel, BorderLayout.WEST);
        panel.add(rightPanel, BorderLayout.CENTER);
        return panel;
    }

    private void addField(JPanel panel, GridBagConstraints gbc, int row,
                          String label, JComponent field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0; gbc.weighty = 0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
        panel.add(field, gbc);
    }

    private void refreshHostCombo() {
        Object selected = hostCombo.getSelectedItem();
        hostCombo.removeAllItems();
        hostCombo.addItem(new HostItem(null, "Local"));
        for (SshHost host : hostStore.getHosts()) {
            hostCombo.addItem(new HostItem(host.id(), host.label()));
        }
        if (selected != null) hostCombo.setSelectedItem(selected);
    }

    private void loadSelectedConfig() {
        RunConfig config = configList.getSelectedValue();
        if (config == null) return;
        nameField.setText(config.name());
        interpreterField.setText(config.interpreter());
        scriptArgsField.setText(String.join(" ", config.scriptArgs()));
        workDirField.setText(config.workingDirectory() != null
            ? config.workingDirectory() : "");

        // Select matching host
        for (int i = 0; i < hostCombo.getItemCount(); i++) {
            HostItem item = hostCombo.getItemAt(i);
            if (java.util.Objects.equals(item.hostId(), config.hostId())) {
                hostCombo.setSelectedIndex(i);
                break;
            }
        }

        // Load env vars
        envTableModel.setRowCount(0);
        for (Map.Entry<String, String> entry : config.envVars().entrySet()) {
            envTableModel.addRow(new String[]{entry.getKey(), entry.getValue()});
        }
    }

    private @NotNull RunConfig buildConfigFromFields(@NotNull UUID id) {
        HostItem hostItem = (HostItem) hostCombo.getSelectedItem();
        UUID hostId = hostItem != null ? hostItem.hostId() : null;

        String scriptArgsRaw = scriptArgsField.getText().trim();
        List<String> scriptArgs = scriptArgsRaw.isEmpty()
            ? List.of() : List.of(scriptArgsRaw.split("\\s+"));

        String workDir = workDirField.getText().trim();

        Map<String, String> envVars = new HashMap<>();
        for (int i = 0; i < envTableModel.getRowCount(); i++) {
            String key = (String) envTableModel.getValueAt(i, 0);
            String val = (String) envTableModel.getValueAt(i, 1);
            if (key != null && !key.isBlank()) {
                envVars.put(key.trim(), val != null ? val.trim() : "");
            }
        }

        return new RunConfig(id, nameField.getText().trim(), hostId,
            interpreterField.getText().trim(), List.of(),
            workDir.isEmpty() ? null : workDir, envVars, scriptArgs);
    }

    private void addConfig() {
        RunConfig config = RunConfig.create("New Configuration", null, "",
            List.of(), null, Map.of(), List.of());
        listModel.addElement(config);
        configList.setSelectedIndex(listModel.size() - 1);
    }

    private void removeConfig() {
        int index = configList.getSelectedIndex();
        if (index < 0) return;
        RunConfig config = listModel.get(index);
        listModel.remove(index);
        configStore.remove(config.id());
    }

    private void duplicateConfig() {
        RunConfig selected = configList.getSelectedValue();
        if (selected == null) return;
        RunConfig copy = RunConfig.create(
            selected.name() + " (copy)", selected.hostId(),
            selected.interpreter(), selected.args(),
            selected.workingDirectory(), selected.envVars(),
            selected.scriptArgs());
        listModel.addElement(copy);
        configList.setSelectedIndex(listModel.size() - 1);
    }

    @Override
    protected void doOKAction() {
        applyChanges();
        hostStore.removeChangeListener(hostChangeListener);
        super.doOKAction();
    }

    @Override
    public void doCancelAction() {
        hostStore.removeChangeListener(hostChangeListener);
        super.doCancelAction();
    }

    private void applyChanges() {
        // Save the currently selected config's field edits
        RunConfig selected = configList.getSelectedValue();
        if (selected != null) {
            RunConfig updated = buildConfigFromFields(selected.id());
            int index = configList.getSelectedIndex();
            listModel.set(index, updated);
        }

        // Sync list model back to store
        RunConfigStore store = configStore;
        List<RunConfig> existing = store.getAll();
        // Remove all, re-add from list model
        for (RunConfig old : existing) {
            store.remove(old.id());
        }
        for (int i = 0; i < listModel.size(); i++) {
            store.add(listModel.get(i));
        }
        try {
            store.save();
        } catch (IOException e) {
            // logged by store
        }
    }
}
```

- [ ] **Step 2: Implement EditConfigAction**

```java
// plugins/runner/src/com/termlab/runner/actions/EditConfigAction.java
package com.termlab.runner.actions;

import com.termlab.runner.config.FileConfigBinding;
import com.termlab.runner.config.InterpreterRegistry;
import com.termlab.runner.config.RunConfig;
import com.termlab.runner.config.RunConfigDialog;
import com.termlab.runner.config.RunConfigStore;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Opens the run configuration dialog. If the current file has a bound
 * config, pre-selects it; otherwise pre-populates defaults.
 */
public final class EditConfigAction extends AnAction {

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(activeFile(e) != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        VirtualFile file = activeFile(e);
        if (file == null) return;

        RunConfigStore store = ApplicationManager.getApplication()
            .getService(RunConfigStore.class);
        FileConfigBinding binding = ApplicationManager.getApplication()
            .getService(FileConfigBinding.class);

        UUID boundId = binding.getConfigId(file.getPath());
        RunConfig preselect = boundId != null ? store.getById(boundId) : null;
        String defaultInterpreter = InterpreterRegistry.interpreterForFile(file.getName());

        RunConfigDialog dialog = new RunConfigDialog(project, preselect, defaultInterpreter);
        dialog.show();
    }

    private static @Nullable VirtualFile activeFile(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return null;
        FileEditor editor = FileEditorManager.getInstance(project).getSelectedEditor();
        if (editor == null) return null;
        return editor.getFile();
    }
}
```

- [ ] **Step 3: Verify build compiles**

Run: `bash bazel.cmd build //termlab/plugins/runner`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add plugins/runner/src/com/termlab/runner/actions/EditConfigAction.java \
       plugins/runner/src/com/termlab/runner/config/RunConfigDialog.java
git commit -m "feat(runner): add EditConfigAction and RunConfigDialog"
```

---

### Task 12: Wire Up Toolbar Actions + Smoke Test

**Files:**
- Modify: `plugins/runner/resources/META-INF/plugin.xml` (actions already declared in Task 1)

The actions are already registered in plugin.xml from Task 1. This task verifies end-to-end functionality.

- [ ] **Step 1: Verify the full build**

Run: `bash bazel.cmd build //termlab/plugins/runner`
Expected: BUILD SUCCESS

- [ ] **Step 2: Run all runner tests**

Run: `bash bazel.cmd run //termlab/plugins/runner:runner_test_runner`
Expected: All tests PASS

- [ ] **Step 3: Run TermLab and verify plugin loads**

Run: `bash bazel.cmd run //termlab:termlab_run`

Verify:
1. TermLab launches without errors in the log
2. "Script Output" tool window appears in the bottom bar (collapsed)
3. Open a new scratch file (Cmd+N), pick Python
4. Type `print("hello world")`
5. Press Cmd+R — save-as dialog appears (it's an unsaved scratch)
6. Save to a local path
7. Script runs, output appears in the Script Output tool window
8. Header shows "python3 • local • [time] • Finished (exit 0)"
9. Press Cmd+Shift+R — configuration dialog opens

- [ ] **Step 4: Test remote execution**

Prerequisite: have at least one SSH host saved in the Hosts panel.

1. Open a `.sh` file from the SFTP browser (double-click in remote pane)
2. Press Cmd+R — script executes on the remote host
3. Output streams live in the Script Output panel
4. "Save as configuration?" notification appears after execution

- [ ] **Step 5: Commit any fixes from smoke testing**

```bash
git add -A
git commit -m "fix(runner): smoke test fixes"
```

(Only if changes were needed. Skip if everything worked.)

---

### Task 13: Cross-Host Mismatch Warning

**Files:**
- Modify: `plugins/runner/src/com/termlab/runner/actions/RunScriptAction.java`

- [ ] **Step 1: Add the mismatch check to RunScriptAction**

In `RunScriptAction.actionPerformed()`, after resolving the config and target but before executing, add:

```java
// In RunScriptAction.actionPerformed(), after config is resolved:

// Cross-host mismatch check
if (!config.isLocal() && !target.isLocal()
    && config.hostId() != null && target.sftpHostId() != null
    && !config.hostId().equals(target.sftpHostId())) {

    HostStore hostStore = ApplicationManager.getApplication()
        .getService(HostStore.class);
    String configHost = resolveHostLabel(config);
    SshHost targetHost = hostStore.findById(target.sftpHostId());
    String targetLabel = targetHost != null ? targetHost.label() : "unknown";

    int result = com.intellij.openapi.ui.Messages.showYesNoDialog(
        project,
        "This file is on " + targetLabel + " but the configuration runs on "
            + configHost + ". Run anyway?",
        "Host Mismatch",
        com.intellij.openapi.ui.Messages.getWarningIcon());
    if (result != com.intellij.openapi.ui.Messages.YES) return;
}
```

- [ ] **Step 2: Verify build compiles**

Run: `bash bazel.cmd build //termlab/plugins/runner`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add plugins/runner/src/com/termlab/runner/actions/RunScriptAction.java
git commit -m "feat(runner): add cross-host mismatch warning dialog"
```

---

## Self-Review

**Spec coverage check:**
- Plugin structure & dependencies → Task 1
- RunConfig model → Task 2
- RunConfigStore persistence → Task 3
- FileConfigBinding → Task 4
- InterpreterRegistry → Task 4
- Toolbar actions (Run, Edit Config) → Tasks 10, 11
- Execution engine (local + remote) → Tasks 6, 7
- CommandBuilder → Task 5
- Output tool window → Task 8
- Save-before-run flow → Task 9
- Cross-host mismatch → Task 13
- Configuration dialog → Task 11
- Host store integration (reactive dropdown) → Task 11 (in RunConfigDialog)
- Share bundle integration → Spec marks this as "later phase", not in this plan. Correct.

**Placeholder scan:** No TBDs, TODOs, or vague steps found.

**Type consistency check:**
- `RunConfig.create()` signature matches across Tasks 2, 3, 4, 5, 10, 11
- `RunConfigStore` methods (`add`, `remove`, `update`, `getAll`, `getById`, `save`) consistent across Tasks 3, 10, 11
- `FileConfigBinding` methods (`getConfigId`, `bind`, `unbind`, `save`) consistent across Tasks 4, 10
- `CommandBuilder.buildLocalCommand()` and `buildRemoteCommand()` signatures match Tasks 5, 10
- `LocalExecution.start()` and `RemoteExecution.start()` signatures match Tasks 6, 7, 10
- `ScriptOutputPanel.addExecution()` signature matches Tasks 8, 10
- `SaveBeforeRunHelper.resolve()` and `RunTarget` match Tasks 9, 10
