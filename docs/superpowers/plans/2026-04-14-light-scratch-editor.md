# Light Scratch Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in `plugins/editor` bundle that provides scratch file creation and SFTP-triggered local/remote file editing. The feature is disabled by default, enabled via a first-launch notification or a Settings checkbox, and toggling requires a restart.

**Architecture:** A new bundled `com.termlab.editor` plugin (parallel to `sftp`/`ssh`/etc.) is built into the product distribution but disabled by default via a seeded `disabled_plugins.txt`. SFTP grows two new extension points (`LocalFileOpener`, `RemoteFileOpener`) that the editor plugin implements; SFTP's double-click handlers delegate through the EP, so they remain a no-op when the editor is disabled. The `core` plugin — always loaded — owns the first-launch notification and the Workbench settings page that flip `PluginManagerCore.enablePlugins` for the editor + TextMate pair.

**Tech Stack:** Java 21, Bazel, IntelliJ Platform (monolith layout pinned via `/Users/dustin/projects/intellij-community`), Apache SSHD SFTP, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-04-14-light-scratch-editor-design.md`

---

## Orientation for the Implementing Engineer

Before touching any code, read these files to understand the conventions you'll be following:

- `plugins/sftp/BUILD.bazel` — template for a new plugin's Bazel layout.
- `plugins/sftp/resources/META-INF/plugin.xml` — template for a new plugin manifest.
- `plugins/vault/BUILD.bazel` — template for adding JUnit 5 unit tests (`<plugin>_test_lib` + `<plugin>_test_runner` with the standalone `TestRunner` pattern).
- `plugins/vault/test/com/termlab/vault/TestRunner.java` — copy-pasteable JUnit 5 launcher.
- `plugins/vault/test/com/termlab/vault/crypto/SecureBytesTest.java` — example of a plain JUnit 5 test with no platform dependencies.
- `BUILD.bazel` (repo root) — the `termlab_run` target, where new plugins and TextMate must be added as `runtime_deps`.
- `core/resources/META-INF/plugin.xml` — pattern for `<applicationConfigurable>`, `<applicationListeners>`, `<postStartupActivity>`, `<notificationGroup>`.
- `plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java:215` — the file-not-directory branch currently falls through.
- `plugins/sftp/src/com/termlab/sftp/toolwindow/LocalFilePane.java:354` — same pattern on the local side.
- `plugins/sftp/src/com/termlab/sftp/model/RemoteFileEntry.java` — record fields used in signatures: `name`, `size`, `isDirectory`.
- `plugins/sftp/src/com/termlab/sftp/model/LocalFileEntry.java` — fields: `path`, `name`, `size`, `isDirectory`.

**Build commands** (from repo root):

- Build everything TermLab: `bash bazel.cmd build //termlab/...`
- Build just a plugin: `bash bazel.cmd build //termlab/plugins/editor:editor`
- Run TermLab from source: `bash bazel.cmd run //termlab:termlab_run`
- Run a plugin's unit tests: `bash bazel.cmd run //termlab/plugins/editor:editor_test_runner`

**Commit convention:** short prose summaries per completed task, lowercase conventional-commit prefix (`feat(editor):`, `fix(sftp):`, `docs:`). Each task in this plan ends with a commit step.

**Two pragmatic deviations from the spec:**

1. **`SftpSingleFileTransfer` instead of `TransferCoordinator.downloadToLocal` / `uploadFromLocal`.** `TransferCoordinator` is a stateful per-pane coordinator not designed to be called from outside the SFTP tool window. We add a stateless `SftpSingleFileTransfer` utility in `plugins/sftp/src/com/termlab/sftp/transfer/` that takes a `SftpClient` directly. The editor plugin gets the `SftpClient` via the `SshSftpSession` passed through the `RemoteFileOpener` extension point. Intent (reuse sftp's SFTP IO, don't open new sessions) is preserved.

2. **Unit tests only for pure utilities; platform-dependent code is manual-verified.** The spec promises tests for `NewScratchActionTest`, `RemoteFileOpenerExtensionPointTest`, `FirstLaunchNotificationTest`, `EditorSettingsConfigurableTest`, etc. Those require a full IntelliJ test fixture (`LightPlatformTestCase`, `HeavyPlatformTestCase`, or similar), and the existing TermLab test infrastructure (see `plugins/vault/BUILD.bazel`) only runs plain JUnit 5 through a standalone `TestRunner` — its comment explicitly notes "Executable test target can be added later once the TermLab tree has a pattern for running pure-JUnit5 tests via Bazel." Rather than adding a new test-fixture infrastructure as part of this plan, we restrict unit tests to the pure utilities (`ExtensionBlocklist`, `BinarySniffer`, `TempPathResolver`) and validate everything else via the end-to-end manual checklist in Task 20. If platform fixtures become available later, add the missing test classes as a follow-up.

---

## Task 1: Scaffold `plugins/editor` module skeleton

**Files:**
- Create: `plugins/editor/BUILD.bazel`
- Create: `plugins/editor/resources/META-INF/plugin.xml`
- Create: `plugins/editor/src/com/termlab/editor/package-info.java`
- Create: `plugins/editor/intellij.termlab.editor.iml` (empty IML to satisfy `exports_files`)

- [ ] **Step 1: Create `plugins/editor/BUILD.bazel`**

```bazel
load("@rules_java//java:defs.bzl", "java_binary")
load("@rules_jvm//:jvm.bzl", "jvm_library", "resourcegroup")

resourcegroup(
    name = "editor_resources",
    srcs = glob(["resources/**/*"]),
    strip_prefix = "resources",
)

jvm_library(
    name = "editor",
    module_name = "intellij.termlab.editor",
    visibility = ["//visibility:public"],
    srcs = glob(["src/**/*.java"], allow_empty = True),
    resources = [":editor_resources"],
    deps = [
        "//termlab/sdk",
        "//termlab/core",
        "//termlab/plugins/sftp",
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
        "@lib//:kotlin-stdlib",
    ],
)

jvm_library(
    name = "editor_test_lib",
    module_name = "intellij.termlab.editor.tests",
    visibility = ["//visibility:public"],
    srcs = glob(["test/**/*.java"], allow_empty = True),
    deps = [
        ":editor",
        "//termlab/sdk",
        "//libraries/junit5",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
        "@lib//:jetbrains-annotations",
        "@lib//:kotlin-stdlib",
    ],
)

java_binary(
    name = "editor_test_runner",
    main_class = "com.termlab.editor.TestRunner",
    runtime_deps = [
        ":editor_test_lib",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
    ],
)

exports_files(["intellij.termlab.editor.iml"], visibility = ["//visibility:public"])
```

- [ ] **Step 2: Create `plugins/editor/resources/META-INF/plugin.xml`** (minimal placeholder — real registrations added in later tasks)

```xml
<idea-plugin>
    <id>com.termlab.editor</id>
    <name>TermLab Light Editor</name>
    <version>0.1.0</version>
    <vendor>TermLab</vendor>
    <description>
        Opt-in light editor for scratches and SFTP-triggered file editing.
        Disabled by default; enabled via the first-launch notification or
        Settings → TermLab → Light Editor. Requires a restart to
        toggle.
    </description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.termlab.core</depends>
    <depends>com.termlab.sftp</depends>
    <depends>org.jetbrains.plugins.textmate</depends>
</idea-plugin>
```

- [ ] **Step 3: Create `plugins/editor/src/com/termlab/editor/package-info.java`** (keeps the `glob(["src/**/*.java"])` non-empty so Bazel doesn't bail)

```java
/**
 * Opt-in light editor plugin for TermLab. Provides scratch
 * file creation and SFTP-triggered local/remote file editing. See
 * docs/superpowers/specs/2026-04-14-light-scratch-editor-design.md.
 */
package com.termlab.editor;
```

- [ ] **Step 4: Create an empty `plugins/editor/intellij.termlab.editor.iml`**

```
```

(Literally zero bytes; `exports_files` just needs the path to exist.)

- [ ] **Step 5: Build the new target to verify it resolves**

Run: `bash bazel.cmd build //termlab/plugins/editor:editor`
Expected: `Build completed successfully`. If it fails with "target not found", check that no typos were introduced in `BUILD.bazel` and that `plugins/editor/` is a sibling of `plugins/sftp/`.

- [ ] **Step 6: Commit**

```bash
git add plugins/editor/
git commit -m "feat(editor): scaffold empty com.termlab.editor plugin module"
```

---

## Task 2: Wire `editor` plugin and TextMate into the `termlab_run` product target

**Files:**
- Modify: `BUILD.bazel:38-66` (the `termlab_run` `runtime_deps` list)

- [ ] **Step 1: Add the two new runtime deps**

Edit `BUILD.bazel`. In the `termlab_run` target's `runtime_deps` list, after the line `"//termlab/plugins/sftp",`, add the editor plugin. Also add TextMate to the "IntelliJ-platform bundled plugins" block after `"//json",`. The resulting `runtime_deps` list should read:

```bazel
    runtime_deps = [
        # Platform core runtime (no product plugins)
        "//platform/main/intellij.platform.monolith.main:monolith-main",
        "//platform/boot",
        "//platform/bootstrap",
        "//platform/starter",

        # TermLab modules
        "//termlab/customization",
        "//termlab/core",
        "//termlab/sdk",

        # TermLab bundled plugins (first-party, always enabled)
        "//termlab/plugins/vault",
        "//termlab/plugins/ssh",
        "//termlab/plugins/tunnels",
        "//termlab/plugins/sftp",
        # Opt-in light editor. Bundled in the distribution but disabled
        # by default via config/disabled_plugins.txt seeding below; users
        # enable it via the first-launch notification or Settings.
        "//termlab/plugins/editor",
        # Runtime implementation of PasswordSafe — required by the vault
        # plugin so it can store the device secret in the cross-platform
        # credential store (Keychain / Credential Manager / KWallet /
        # libsecret / encrypted file fallback).
        "//platform/credential-store-impl:credentialStore-impl",

        # IntelliJ-platform bundled plugins — ONLY these exist in TermLab
        "//plugins/classic-ui",
        "//json",
        # Required by the opt-in editor plugin for grammar-based
        # syntax highlighting. Also disabled by default alongside
        # com.termlab.editor.
        "//plugins/textmate/plugin",
    ],
```

- [ ] **Step 2: Build the product target to verify the deps resolve**

Run: `bash bazel.cmd build //termlab:termlab_run`
Expected: `Build completed successfully`. If `//plugins/textmate/plugin` does not resolve, check `/Users/dustin/projects/intellij-community/plugins/textmate/plugin/BUILD.bazel` — the target name should be `plugin`. If the intellij-community symlink changed, adjust the label accordingly.

- [ ] **Step 3: Commit**

```bash
git add BUILD.bazel
git commit -m "feat(editor): wire editor plugin and textmate into termlab_run"
```

---

## Task 3: Seed `disabled_plugins.txt` at packaging time

**Context:** The feature's opt-in promise hinges on this step. On a fresh install, `com.termlab.editor` and `org.jetbrains.plugins.textmate` must be listed in the bundled config's `disabled_plugins.txt`, so IntelliJ's platform skips loading them at startup.

**Investigation first:** The `termlab_run` target in `BUILD.bazel:121-159` uses `-Didea.config.path=$${BUILD_WORKSPACE_DIRECTORY}/config/termlab`. This points dev runs at a workspace-relative config dir. For packaged artifacts, config lives inside the installed app bundle and is copied on first launch.

This task creates the dev-mode seed file. The packaged-artifact pipeline (installer) is a separate concern that the roadmap Track C will address; adding a check to that pipeline is out of scope for this plan.

**Files:**
- Create: `config/termlab/disabled_plugins.txt`

- [ ] **Step 1: Check whether the file already exists**

Run: `ls config/termlab/disabled_plugins.txt 2>/dev/null || echo "not present"`
If it prints "not present", proceed. If it already has contents (e.g., from prior experiments), back up first: `cp config/termlab/disabled_plugins.txt config/termlab/disabled_plugins.txt.bak`.

- [ ] **Step 2: Create the file**

Write `config/termlab/disabled_plugins.txt`:

```
com.termlab.editor
org.jetbrains.plugins.textmate
```

(Two lines, no blank line at the end.)

- [ ] **Step 3: Run TermLab and verify neither plugin loads**

Run: `bash bazel.cmd run //termlab:termlab_run` in one terminal. In the running IDE, open `Help → Find Action → Plugin Manager` (or just watch the IDE logs). Check that `com.termlab.editor` and `TextMate` appear *disabled* in the plugin list. Close the IDE.

Expected: both plugins listed as disabled. If they're enabled, `disabled_plugins.txt` isn't being read — verify the file is in `config/termlab/` relative to the workspace root and that `idea.config.path` resolves to that directory at runtime (check IDE logs for "Config path: …").

- [ ] **Step 4: Commit**

```bash
git add config/termlab/disabled_plugins.txt
git commit -m "feat(editor): disable editor + textmate by default in dev config"
```

---

## Task 4: Add `LocalFileOpener` and `RemoteFileOpener` extension points to SFTP

**Files:**
- Create: `plugins/sftp/src/com/termlab/sftp/spi/LocalFileOpener.java`
- Create: `plugins/sftp/src/com/termlab/sftp/spi/RemoteFileOpener.java`
- Modify: `plugins/sftp/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `LocalFileOpener.java`**

```java
package com.termlab.sftp.spi;

import com.termlab.sftp.model.LocalFileEntry;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Extension point consumed by the SFTP local pane to delegate
 * double-click-to-open behavior. Zero extensions registered (the
 * default) means "do nothing on double-click," which preserves the
 * pane's behavior when the opt-in editor plugin is disabled.
 */
public interface LocalFileOpener {

    ExtensionPointName<LocalFileOpener> EP_NAME =
        ExtensionPointName.create("com.termlab.sftp.localFileOpener");

    /**
     * Open the given local file in whatever editor the caller
     * provides. Errors should be surfaced as UI notifications; this
     * method must not throw.
     */
    void open(@NotNull Project project, @NotNull LocalFileEntry entry);
}
```

- [ ] **Step 2: Create `RemoteFileOpener.java`**

```java
package com.termlab.sftp.spi;

import com.termlab.sftp.client.SshSftpSession;
import com.termlab.sftp.model.RemoteFileEntry;
import com.termlab.ssh.model.SshHost;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Extension point consumed by the SFTP remote pane to delegate
 * double-click-to-open behavior for files (directories still
 * navigate). Zero extensions registered means "do nothing," which
 * preserves existing pane behavior when the opt-in editor plugin
 * is disabled.
 *
 * <p>The SFTP session is passed through directly rather than
 * reopened — the opener is expected to use
 * {@link SshSftpSession#client()} for any SFTP IO. The opener must
 * not close the session.
 */
public interface RemoteFileOpener {

    ExtensionPointName<RemoteFileOpener> EP_NAME =
        ExtensionPointName.create("com.termlab.sftp.remoteFileOpener");

    /**
     * Open the given remote file for editing.
     *
     * @param project        current project
     * @param host           host descriptor (for display + caching)
     * @param session        live SFTP session; caller retains ownership
     * @param absoluteRemotePath absolute path to the file on the remote
     * @param entry          directory-listing entry for the file (used
     *                       for size + name + attribute info without a
     *                       second stat call)
     */
    void open(@NotNull Project project,
              @NotNull SshHost host,
              @NotNull SshSftpSession session,
              @NotNull String absoluteRemotePath,
              @NotNull RemoteFileEntry entry);
}
```

- [ ] **Step 3: Register the extension points in `plugins/sftp/resources/META-INF/plugin.xml`**

Edit the file. Immediately after the `<depends>com.termlab.ssh</depends>` line and before the first `<extensions>` block, add:

```xml
    <extensionPoints>
        <extensionPoint name="localFileOpener"
                        interface="com.termlab.sftp.spi.LocalFileOpener"
                        dynamic="true"/>
        <extensionPoint name="remoteFileOpener"
                        interface="com.termlab.sftp.spi.RemoteFileOpener"
                        dynamic="true"/>
    </extensionPoints>
```

- [ ] **Step 4: Build the SFTP plugin**

Run: `bash bazel.cmd build //termlab/plugins/sftp:sftp`
Expected: `Build completed successfully`. If the build fails with "package com.termlab.sftp.spi does not exist", confirm the two files are under `plugins/sftp/src/com/termlab/sftp/spi/`.

- [ ] **Step 5: Commit**

```bash
git add plugins/sftp/src/com/termlab/sftp/spi/ plugins/sftp/resources/META-INF/plugin.xml
git commit -m "feat(sftp): add LocalFileOpener and RemoteFileOpener extension points"
```

---

## Task 5: Wire `RemoteFilePane.onRowActivated` to call `RemoteFileOpener`

**Files:**
- Modify: `plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java:215-222`

- [ ] **Step 1: Replace the `onRowActivated` method**

Find this block (around line 215):

```java
    private void onRowActivated(int viewRow) {
        if (viewRow < 0 || activeSession == null || currentRemotePath == null) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        var entry = model.getEntryAt(modelRow);
        if (entry instanceof RemoteFileEntry remote && remote.isDirectory()) {
            navigateRemote(joinPath(currentRemotePath, remote.name()));
        }
    }
```

Replace it with:

```java
    private void onRowActivated(int viewRow) {
        if (viewRow < 0 || activeSession == null || currentRemotePath == null) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        var entry = model.getEntryAt(modelRow);
        if (!(entry instanceof RemoteFileEntry remote)) return;
        if (remote.isDirectory()) {
            navigateRemote(joinPath(currentRemotePath, remote.name()));
            return;
        }
        SshHost host = currentHost;
        SshSftpSession session = activeSession;
        if (host == null) return;
        String absolute = joinPath(currentRemotePath, remote.name());
        var openers = RemoteFileOpener.EP_NAME.getExtensionList();
        if (openers.isEmpty()) return;
        openers.get(0).open(project, host, session, absolute, remote);
    }
```

- [ ] **Step 2: Add the new import**

Add this import alongside the existing imports (alphabetically after `com.termlab.sftp.persistence.TermLabSftpConfig;`):

```java
import com.termlab.sftp.spi.RemoteFileOpener;
```

- [ ] **Step 3: Build**

Run: `bash bazel.cmd build //termlab/plugins/sftp:sftp`
Expected: `Build completed successfully`.

- [ ] **Step 4: Launch TermLab and manually verify current (no-op) behavior is preserved**

Run: `bash bazel.cmd run //termlab:termlab_run`

Connect to any remote host, navigate to any directory, double-click a file. Expected: nothing happens (same as before). Double-click a directory: still navigates into it. Close TermLab.

If double-clicking a file throws an exception in the IDE log, undo step 1/2 and re-check that `openers.isEmpty()` is the branch being taken (editor plugin should be disabled by default at this point).

- [ ] **Step 5: Commit**

```bash
git add plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java
git commit -m "feat(sftp): route remote file double-click through RemoteFileOpener EP"
```

---

## Task 6: Wire `LocalFilePane.onRowActivated` to call `LocalFileOpener`

**Files:**
- Modify: `plugins/sftp/src/com/termlab/sftp/toolwindow/LocalFilePane.java:354-361`

- [ ] **Step 1: Replace the `onRowActivated` method**

Find this block (around line 354):

```java
    private void onRowActivated(int viewRow) {
        if (viewRow < 0 || currentDir == null) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        var entry = model.getEntryAt(modelRow);
        if (entry instanceof LocalFileEntry local && local.isDirectory()) {
            reload(local.path());
        }
    }
```

Replace it with:

```java
    private void onRowActivated(int viewRow) {
        if (viewRow < 0 || currentDir == null) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        var entry = model.getEntryAt(modelRow);
        if (!(entry instanceof LocalFileEntry local)) return;
        if (local.isDirectory()) {
            reload(local.path());
            return;
        }
        var openers = LocalFileOpener.EP_NAME.getExtensionList();
        if (openers.isEmpty()) return;
        openers.get(0).open(project, local);
    }
```

- [ ] **Step 2: Add the new import**

Add alongside existing imports (alphabetically after `com.termlab.sftp.ops.LocalFileOps;`):

```java
import com.termlab.sftp.spi.LocalFileOpener;
```

- [ ] **Step 3: Build and verify**

Run: `bash bazel.cmd build //termlab/plugins/sftp:sftp`
Expected: `Build completed successfully`.

Run: `bash bazel.cmd run //termlab:termlab_run`. In the SFTP local pane, double-click any file — nothing should happen. Double-click a directory — should still navigate. Close TermLab.

- [ ] **Step 4: Commit**

```bash
git add plugins/sftp/src/com/termlab/sftp/toolwindow/LocalFilePane.java
git commit -m "feat(sftp): route local file double-click through LocalFileOpener EP"
```

---

## Task 7: Add `SftpSingleFileTransfer` utility for one-shot downloads / uploads

**Context:** The editor plugin needs to read a single remote file to a local temp path and write it back on save. `TransferCoordinator` is pane-bound; adding another entry point on it would expose an awkward surface. Instead we add a stateless utility next to it.

**Files:**
- Create: `plugins/sftp/src/com/termlab/sftp/transfer/SftpSingleFileTransfer.java`

- [ ] **Step 1: Create the utility**

```java
package com.termlab.sftp.transfer;

import org.apache.sshd.sftp.client.SftpClient;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stateless helpers for single-file SFTP transfers used by the
 * opt-in editor plugin. Intentionally lighter than
 * {@link TransferCoordinator}, which owns UI state and collision
 * handling for pane-to-pane transfers.
 */
public final class SftpSingleFileTransfer {

    private SftpSingleFileTransfer() {}

    /**
     * Download {@code remotePath} to {@code localDest}, replacing
     * anything at the destination. Creates parent directories.
     * Streams bytes; does not load the whole file into memory.
     */
    public static void download(
        @NotNull SftpClient client,
        @NotNull String remotePath,
        @NotNull Path localDest
    ) throws IOException {
        Path parent = localDest.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = localDest.resolveSibling(localDest.getFileName().toString() + ".part");
        try (InputStream in = client.read(remotePath);
             OutputStream out = Files.newOutputStream(tmp)) {
            in.transferTo(out);
        }
        Files.move(tmp, localDest,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Upload {@code localSource} to {@code remotePath}, replacing
     * anything at the destination. Does not manage permissions or
     * ownership.
     */
    public static void upload(
        @NotNull SftpClient client,
        @NotNull Path localSource,
        @NotNull String remotePath
    ) throws IOException {
        try (InputStream in = Files.newInputStream(localSource);
             OutputStream out = client.write(remotePath)) {
            in.transferTo(out);
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `bash bazel.cmd build //termlab/plugins/sftp:sftp`
Expected: `Build completed successfully`.

- [ ] **Step 3: Commit**

```bash
git add plugins/sftp/src/com/termlab/sftp/transfer/SftpSingleFileTransfer.java
git commit -m "feat(sftp): add SftpSingleFileTransfer utility for editor plugin use"
```

---

## Task 8: Implement `ExtensionBlocklist` utility (TDD)

**Files:**
- Create: `plugins/editor/src/com/termlab/editor/guard/ExtensionBlocklist.java`
- Create: `plugins/editor/test/com/termlab/editor/guard/ExtensionBlocklistTest.java`
- Create: `plugins/editor/test/com/termlab/editor/TestRunner.java` (first test file — shared launcher)

- [ ] **Step 1: Create the test runner** (shared across all editor plugin unit tests; model on `plugins/vault/test/com/termlab/vault/TestRunner.java`)

```java
package com.termlab.editor;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

/**
 * Standalone test runner for the editor plugin's unit tests. Runs
 * the full {@code com.termlab.editor} test tree via the JUnit 5
 * platform launcher.
 *
 * <p>Usage:
 * <pre>
 *   bash bazel.cmd run //termlab/plugins/editor:editor_test_runner
 * </pre>
 */
public final class TestRunner {

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("com.termlab.editor"))
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

- [ ] **Step 2: Write the failing test**

`plugins/editor/test/com/termlab/editor/guard/ExtensionBlocklistTest.java`:

```java
package com.termlab.editor.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionBlocklistTest {

    @Test
    void blocksCommonImageExtensions() {
        assertTrue(ExtensionBlocklist.isBlocked("screenshot.png"));
        assertTrue(ExtensionBlocklist.isBlocked("photo.jpg"));
        assertTrue(ExtensionBlocklist.isBlocked("photo.JPEG"));
        assertTrue(ExtensionBlocklist.isBlocked("icon.GIF"));
    }

    @Test
    void blocksArchiveExtensions() {
        assertTrue(ExtensionBlocklist.isBlocked("release.zip"));
        assertTrue(ExtensionBlocklist.isBlocked("backup.tar"));
        assertTrue(ExtensionBlocklist.isBlocked("backup.tar.gz"));
        assertTrue(ExtensionBlocklist.isBlocked("pack.7z"));
    }

    @Test
    void blocksExecutableExtensions() {
        assertTrue(ExtensionBlocklist.isBlocked("tool.exe"));
        assertTrue(ExtensionBlocklist.isBlocked("lib.dll"));
        assertTrue(ExtensionBlocklist.isBlocked("lib.so"));
        assertTrue(ExtensionBlocklist.isBlocked("lib.dylib"));
        assertTrue(ExtensionBlocklist.isBlocked("Main.class"));
    }

    @Test
    void allowsTextExtensions() {
        assertFalse(ExtensionBlocklist.isBlocked("config.yaml"));
        assertFalse(ExtensionBlocklist.isBlocked("script.sh"));
        assertFalse(ExtensionBlocklist.isBlocked("notes.md"));
        assertFalse(ExtensionBlocklist.isBlocked("code.py"));
        assertFalse(ExtensionBlocklist.isBlocked("data.json"));
    }

    @Test
    void allowsNoExtension() {
        assertFalse(ExtensionBlocklist.isBlocked("Makefile"));
        assertFalse(ExtensionBlocklist.isBlocked("Dockerfile"));
        assertFalse(ExtensionBlocklist.isBlocked(".bashrc"));
    }

    @Test
    void allowsDotfilesWithNonBlockedExtension() {
        assertFalse(ExtensionBlocklist.isBlocked(".config.yaml"));
    }
}
```

- [ ] **Step 3: Run test, confirm it fails**

Run: `bash bazel.cmd run //termlab/plugins/editor:editor_test_runner`
Expected: compile failure ("cannot find symbol: class ExtensionBlocklist"). That is a "failing" red state for TDD purposes — proceed.

- [ ] **Step 4: Implement `ExtensionBlocklist.java`**

```java
package com.termlab.editor.guard;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Hard-coded extension blocklist for the opt-in editor plugin.
 * Files with any of these extensions are refused before download
 * or local open, without touching bytes on disk or the wire.
 */
public final class ExtensionBlocklist {

    private static final Set<String> BLOCKED = Set.of(
        // images
        "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "svg",
        // archives
        "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar",
        // JVM + native bundles
        "jar", "war", "ear", "class",
        "exe", "dll", "so", "dylib",
        // documents
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        // media
        "mp3", "mp4", "mov", "avi", "mkv", "wav", "flac",
        // compiled python
        "pyc", "pyo"
    );

    private ExtensionBlocklist() {}

    /**
     * True if the filename's trailing extension is on the blocklist.
     * The extension is taken as everything after the final {@code '.'}
     * in the filename. Case-insensitive. Files with no dot (or a
     * leading dot only, like {@code .bashrc}) are never blocked.
     *
     * <p>For multi-dot archive extensions like {@code backup.tar.gz},
     * only the final segment ({@code gz}) is checked — which is
     * enough because the compressed outer extension is itself on
     * the blocklist.
     */
    public static boolean isBlocked(@NotNull String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == filename.length() - 1) return false;
        String ext = filename.substring(lastDot + 1).toLowerCase();
        return BLOCKED.contains(ext);
    }
}
```

- [ ] **Step 5: Run tests, confirm green**

Run: `bash bazel.cmd run //termlab/plugins/editor:editor_test_runner`
Expected: `6 tests successful, 0 failed`.

- [ ] **Step 6: Commit**

```bash
git add plugins/editor/src/com/termlab/editor/guard/ExtensionBlocklist.java plugins/editor/test/
git commit -m "feat(editor): extension blocklist for non-editable file types"
```

---

## Task 9: Implement `BinarySniffer` utility (TDD)

**Files:**
- Create: `plugins/editor/src/com/termlab/editor/guard/BinarySniffer.java`
- Create: `plugins/editor/test/com/termlab/editor/guard/BinarySnifferTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.termlab.editor.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinarySnifferTest {

    @Test
    void emptyFileIsNotBinary(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("empty");
        Files.createFile(f);
        assertFalse(BinarySniffer.isBinary(f));
    }

    @Test
    void plainTextIsNotBinary(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("text.txt");
        Files.writeString(f, "hello world\nlorem ipsum\n");
        assertFalse(BinarySniffer.isBinary(f));
    }

    @Test
    void fileWithNullByteAtStartIsBinary(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("bin");
        Files.write(f, new byte[]{0x00, 0x01, 0x02});
        assertTrue(BinarySniffer.isBinary(f));
    }

    @Test
    void fileWithNullByteInMiddleIsBinary(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("bin");
        byte[] bytes = new byte[]{'a', 'b', 'c', 0x00, 'd', 'e'};
        Files.write(f, bytes);
        assertTrue(BinarySniffer.isBinary(f));
    }

    @Test
    void nullByteAfterFirst8KBIsNotChecked(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("big");
        byte[] bytes = new byte[16 * 1024];
        // pure text in the first 8KB
        java.util.Arrays.fill(bytes, 0, 8 * 1024, (byte) 'a');
        // a null byte at position 10000 (outside the window)
        bytes[10_000] = 0x00;
        Files.write(f, bytes);
        assertFalse(BinarySniffer.isBinary(f));
    }

    @Test
    void nullByteAt8KBOneIsIgnored(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("edge");
        byte[] bytes = new byte[9 * 1024];
        java.util.Arrays.fill(bytes, (byte) 'a');
        bytes[8 * 1024] = 0x00; // first byte past the window
        Files.write(f, bytes);
        assertFalse(BinarySniffer.isBinary(f));
    }

    @Test
    void fileSmallerThan8KBWithNullIsBinary(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("small");
        byte[] bytes = new byte[100];
        java.util.Arrays.fill(bytes, (byte) 'a');
        bytes[50] = 0x00;
        Files.write(f, bytes);
        assertTrue(BinarySniffer.isBinary(f));
    }
}
```

- [ ] **Step 2: Run test, confirm it fails**

Run: `bash bazel.cmd run //termlab/plugins/editor:editor_test_runner`
Expected: compile failure for `BinarySniffer`.

- [ ] **Step 3: Implement `BinarySniffer.java`**

```java
package com.termlab.editor.guard;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cheap binary-content heuristic matching git's strategy: a file
 * is considered binary if any of its first 8 KB contains a null
 * byte. Files shorter than 8 KB are fully scanned.
 */
public final class BinarySniffer {

    private static final int SNIFF_BYTES = 8 * 1024;

    private BinarySniffer() {}

    public static boolean isBinary(@NotNull Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = in.readNBytes(SNIFF_BYTES);
            for (byte b : buf) {
                if (b == 0) return true;
            }
            return false;
        }
    }
}
```

- [ ] **Step 4: Run tests, confirm green**

Run: `bash bazel.cmd run //termlab/plugins/editor:editor_test_runner`
Expected: all tests pass (now 13 total across both files).

- [ ] **Step 5: Commit**

```bash
git add plugins/editor/src/com/termlab/editor/guard/BinarySniffer.java plugins/editor/test/com/termlab/editor/guard/BinarySnifferTest.java
git commit -m "feat(editor): 8KB null-byte binary sniffer"
```

---

## Task 10: Implement `TempPathResolver` (TDD)

**Files:**
- Create: `plugins/editor/src/com/termlab/editor/remote/TempPathResolver.java`
- Create: `plugins/editor/test/com/termlab/editor/remote/TempPathResolverTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.termlab.editor.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempPathResolverTest {

    @Test
    void preservesBasenameWithExtension(@TempDir Path root) {
        Path result = TempPathResolver.resolve(root, "user@host:22", "/etc/nginx/nginx.conf");
        assertEquals("nginx.conf", result.getFileName().toString());
    }

    @Test
    void preservesDotfileBasename(@TempDir Path root) {
        Path result = TempPathResolver.resolve(root, "user@host:22", "/home/me/.bashrc");
        assertEquals(".bashrc", result.getFileName().toString());
    }

    @Test
    void preservesMultiDotBasename(@TempDir Path root) {
        Path result = TempPathResolver.resolve(root, "user@host:22", "/a/b/c/backup.tar.gz");
        assertEquals("backup.tar.gz", result.getFileName().toString());
    }

    @Test
    void differentHostsResolveToDifferentDirectories(@TempDir Path root) {
        Path a = TempPathResolver.resolve(root, "alice@host-a:22", "/tmp/file");
        Path b = TempPathResolver.resolve(root, "bob@host-b:22", "/tmp/file");
        assertNotEquals(a.getParent().getParent(), b.getParent().getParent());
    }

    @Test
    void samePathSameHostResolvesStably(@TempDir Path root) {
        Path a = TempPathResolver.resolve(root, "user@host:22", "/var/log/app.log");
        Path b = TempPathResolver.resolve(root, "user@host:22", "/var/log/app.log");
        assertEquals(a, b);
    }

    @Test
    void samePathDifferentHostsResolveDifferently(@TempDir Path root) {
        Path a = TempPathResolver.resolve(root, "user@host-a:22", "/tmp/f");
        Path b = TempPathResolver.resolve(root, "user@host-b:22", "/tmp/f");
        assertNotEquals(a, b);
    }

    @Test
    void differentPathsSameHostResolveDifferently(@TempDir Path root) {
        Path a = TempPathResolver.resolve(root, "user@host:22", "/tmp/a");
        Path b = TempPathResolver.resolve(root, "user@host:22", "/tmp/b");
        assertNotEquals(a, b);
    }

    @Test
    void resolvedPathIsBeneathRoot(@TempDir Path root) {
        Path result = TempPathResolver.resolve(root, "user@host:22", "/etc/passwd");
        assertTrue(result.startsWith(root));
    }
}
```

- [ ] **Step 2: Run, confirm fail**

Run: `bash bazel.cmd run //termlab/plugins/editor:editor_test_runner`
Expected: compile failure.

- [ ] **Step 3: Implement `TempPathResolver.java`**

```java
package com.termlab.editor.remote;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic temp-path layout for SFTP-edited files.
 *
 * <pre>
 * {root}/
 *   {hash8(host)}/
 *     {hash8(remotePath)}/
 *       {basename}
 * </pre>
 *
 * The host prefix isolates per-host; the remote-path prefix
 * prevents collisions between files that happen to share a
 * basename across different remote directories. The basename is
 * preserved so TextMate can pick a grammar by extension.
 */
public final class TempPathResolver {

    private TempPathResolver() {}

    public static @NotNull Path resolve(
        @NotNull Path root,
        @NotNull String hostConnectionString,
        @NotNull String absoluteRemotePath
    ) {
        String hostHash = sha1Prefix(hostConnectionString, 8);
        String pathHash = sha1Prefix(absoluteRemotePath, 8);
        String basename = basenameOf(absoluteRemotePath);
        return root.resolve(hostHash).resolve(pathHash).resolve(basename);
    }

    private static @NotNull String basenameOf(@NotNull String remotePath) {
        int slash = remotePath.lastIndexOf('/');
        if (slash < 0 || slash == remotePath.length() - 1) return remotePath;
        return remotePath.substring(slash + 1);
    }

    private static @NotNull String sha1Prefix(@NotNull String input, int hexChars) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hexChars);
            for (int i = 0; i < (hexChars + 1) / 2; i++) {
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.substring(0, hexChars);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
```

- [ ] **Step 4: Run, confirm green**

Run: `bash bazel.cmd run //termlab/plugins/editor:editor_test_runner`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add plugins/editor/src/com/termlab/editor/remote/TempPathResolver.java plugins/editor/test/com/termlab/editor/remote/TempPathResolverTest.java
git commit -m "feat(editor): deterministic temp path resolver for SFTP edits"
```

---

## Task 11: Implement `RemoteFileBinding` and in-memory registry

**Files:**
- Create: `plugins/editor/src/com/termlab/editor/remote/RemoteFileBinding.java`
- Create: `plugins/editor/src/com/termlab/editor/remote/RemoteFileBindingRegistry.java`

- [ ] **Step 1: Create `RemoteFileBinding.java`**

```java
package com.termlab.editor.remote;

import com.termlab.sftp.client.SshSftpSession;
import com.termlab.ssh.model.SshHost;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Association between a local temp file (opened in an editor tab)
 * and the remote file it mirrors. Holds the live SFTP session so
 * save can upload without reopening a connection.
 *
 * <p>The session reference is weak from the caller's perspective:
 * if the user disconnects in the SFTP pane, the session becomes
 * unusable and upload will fail with an IO error. That's the
 * expected behavior — the spec has no reconnection logic in MVP.
 */
public record RemoteFileBinding(
    @NotNull Path tempPath,
    @NotNull SshHost host,
    @NotNull String absoluteRemotePath,
    @NotNull SshSftpSession session
) {}
```

- [ ] **Step 2: Create `RemoteFileBindingRegistry.java`**

```java
package com.termlab.editor.remote;

import com.intellij.openapi.components.Service;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-level registry mapping local temp paths to the
 * {@link RemoteFileBinding} describing their remote source. Owned
 * by the editor plugin and consulted from the save hook and
 * cleanup hooks.
 */
@Service(Service.Level.APP)
public final class RemoteFileBindingRegistry {

    private final Map<String, RemoteFileBinding> bindings = new ConcurrentHashMap<>();

    public void register(@NotNull RemoteFileBinding binding) {
        bindings.put(binding.tempPath().toAbsolutePath().toString(), binding);
    }

    public @Nullable RemoteFileBinding get(@NotNull String tempPathAbsolute) {
        return bindings.get(tempPathAbsolute);
    }

    public @Nullable RemoteFileBinding remove(@NotNull String tempPathAbsolute) {
        return bindings.remove(tempPathAbsolute);
    }

    public @NotNull Collection<RemoteFileBinding> all() {
        return bindings.values();
    }

    public int size() {
        return bindings.size();
    }

    public void clear() {
        bindings.clear();
    }
}
```

- [ ] **Step 3: Build**

Run: `bash bazel.cmd build //termlab/plugins/editor:editor`
Expected: `Build completed successfully`.

- [ ] **Step 4: Commit**

```bash
git add plugins/editor/src/com/termlab/editor/remote/RemoteFileBinding.java plugins/editor/src/com/termlab/editor/remote/RemoteFileBindingRegistry.java
git commit -m "feat(editor): remote file binding model and registry service"
```

---

## Task 12: Implement `RemoteEditService` (local open + remote open)

**Files:**
- Create: `plugins/editor/src/com/termlab/editor/remote/RemoteEditService.java`

- [ ] **Step 1: Create the service class**

```java
package com.termlab.editor.remote;

import com.termlab.editor.guard.BinarySniffer;
import com.termlab.editor.guard.ExtensionBlocklist;
import com.termlab.sftp.client.SshSftpSession;
import com.termlab.sftp.model.LocalFileEntry;
import com.termlab.sftp.model.RemoteFileEntry;
import com.termlab.sftp.transfer.SftpSingleFileTransfer;
import com.termlab.ssh.model.SshHost;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Application service that opens local and remote files for
 * editing in TermLab's main editor area. Guards with the size cap
 * (5 MB), extension blocklist, and (for remote only) a null-byte
 * binary sniff.
 */
@Service(Service.Level.APP)
public final class RemoteEditService {

    private static final long SIZE_CAP_BYTES = 5L * 1024 * 1024;
    private static final String TEMP_ROOT_NAME = "termlab-sftp-edits";
    private static final String NOTIFICATION_GROUP = "TermLab Light Editor";

    public @NotNull Path tempRoot() {
        return Paths.get(PathManager.getSystemPath(), TEMP_ROOT_NAME);
    }

    /**
     * Open a local file (from the SFTP local pane) directly in the
     * main editor area. Applies size cap and extension blocklist.
     */
    public void openLocalFile(@NotNull Project project, @NotNull LocalFileEntry entry) {
        if (entry.isDirectory()) return;
        if (ExtensionBlocklist.isBlocked(entry.name())) {
            notifyError(project, "Cannot edit " + entry.name() + ": binary file type.");
            return;
        }
        if (entry.size() > SIZE_CAP_BYTES) {
            notifyError(project, "File too large (" + formatMb(entry.size())
                + " MB). Maximum editable size is 5 MB.");
            return;
        }
        VirtualFile vf = LocalFileSystem.getInstance()
            .refreshAndFindFileByNioFile(entry.path());
        if (vf == null) {
            notifyError(project, "Could not open " + entry.path() + " — file not found.");
            return;
        }
        FileEditorManager.getInstance(project).openFile(vf, true);
    }

    /**
     * Download a remote file to a deterministic temp path, sniff
     * for binary content, open in the main editor area, and
     * register a {@link RemoteFileBinding} so save uploads back.
     */
    public void openRemoteFile(
        @NotNull Project project,
        @NotNull SshHost host,
        @NotNull SshSftpSession session,
        @NotNull String absoluteRemotePath,
        @NotNull RemoteFileEntry entry
    ) {
        if (entry.isDirectory()) return;
        if (ExtensionBlocklist.isBlocked(entry.name())) {
            notifyError(project, "Cannot edit " + entry.name() + ": binary file type.");
            return;
        }
        if (entry.size() > SIZE_CAP_BYTES) {
            notifyError(project, "File too large (" + formatMb(entry.size())
                + " MB). Maximum editable size is 5 MB.");
            return;
        }

        Path tempPath = TempPathResolver.resolve(
            tempRoot(), connectionString(host), absoluteRemotePath);

        AppExecutorUtil.getAppExecutorService().submit(() -> {
            try {
                Files.createDirectories(tempPath.getParent());
                SftpSingleFileTransfer.download(session.client(), absoluteRemotePath, tempPath);
                if (BinarySniffer.isBinary(tempPath)) {
                    Files.deleteIfExists(tempPath);
                    ApplicationManager.getApplication().invokeLater(() ->
                        notifyError(project, "Binary file detected: " + entry.name()));
                    return;
                }
            } catch (IOException e) {
                try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
                ApplicationManager.getApplication().invokeLater(() ->
                    notifyError(project, "Download failed for "
                        + entry.name() + ": " + e.getMessage()));
                return;
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                VirtualFile vf = LocalFileSystem.getInstance()
                    .refreshAndFindFileByNioFile(tempPath);
                if (vf == null) {
                    notifyError(project, "Could not open downloaded file: " + tempPath);
                    return;
                }
                RemoteFileBindingRegistry registry = ApplicationManager.getApplication()
                    .getService(RemoteFileBindingRegistry.class);
                registry.register(new RemoteFileBinding(tempPath, host, absoluteRemotePath, session));
                FileEditorManager.getInstance(project).openFile(vf, true);
            });
        });
    }

    private static @NotNull String connectionString(@NotNull SshHost host) {
        return host.username() + "@" + host.host() + ":" + host.port();
    }

    private static @NotNull String formatMb(long bytes) {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }

    private static void notifyError(@NotNull Project project, @NotNull String message) {
        Notifications.Bus.notify(
            new Notification(NOTIFICATION_GROUP, "Light Editor", message, NotificationType.ERROR),
            project);
    }
}
```

- [ ] **Step 2: Build**

Run: `bash bazel.cmd build //termlab/plugins/editor:editor`
Expected: `Build completed successfully`. If a symbol is missing (e.g., `SshSftpSession.client()`), re-check `plugins/sftp/src/com/termlab/sftp/client/SshSftpSession.java` for the actual accessor name.

- [ ] **Step 3: Commit**

```bash
git add plugins/editor/src/com/termlab/editor/remote/RemoteEditService.java
git commit -m "feat(editor): RemoteEditService with local + remote file open"
```

---

## Task 13: Add save-and-upload listener for remote-bound files

**Files:**
- Create: `plugins/editor/src/com/termlab/editor/remote/RemoteSaveListener.java`
- Modify: `plugins/editor/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `RemoteSaveListener.java`**

```java
package com.termlab.editor.remote;

import com.termlab.sftp.transfer.SftpSingleFileTransfer;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Listens for document saves and, if the saved document backs a
 * file registered in {@link RemoteFileBindingRegistry}, uploads
 * the new contents back to the remote host.
 *
 * <p>Runs after the platform has written the document to disk:
 * {@link #beforeDocumentSaving} captures the binding, but the
 * upload itself is deferred to {@link #fileContentLoaded} /
 * after the save completes so we read the written bytes. In
 * practice the simplest correct option is to hook
 * {@link #beforeDocumentSaving} and chain the upload on the app
 * executor — the platform write-out is synchronous within the
 * save path so the temp file is up-to-date by the time our
 * executor task runs.
 */
public final class RemoteSaveListener implements FileDocumentManagerListener {

    private static final String NOTIFICATION_GROUP = "TermLab Light Editor";

    @Override
    public void beforeDocumentSaving(@NotNull Document document) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null) return;
        Path path = Paths.get(file.getPath());
        RemoteFileBindingRegistry registry = ApplicationManager.getApplication()
            .getService(RemoteFileBindingRegistry.class);
        RemoteFileBinding binding = registry.get(path.toAbsolutePath().toString());
        if (binding == null) return;

        // The platform writes the document to disk as part of its
        // own save sequence. Kick the upload onto the app executor
        // so it runs off-EDT after that write has landed.
        AppExecutorUtil.getAppExecutorService().submit(() -> uploadAndNotify(binding));
    }

    private void uploadAndNotify(@NotNull RemoteFileBinding binding) {
        try {
            SftpSingleFileTransfer.upload(
                binding.session().client(),
                binding.tempPath(),
                binding.absoluteRemotePath());
        } catch (IOException e) {
            notifyUploadFailure(binding, e);
            return;
        }
        notifyUploadSuccess(binding);
    }

    private static void notifyUploadSuccess(@NotNull RemoteFileBinding binding) {
        String title = binding.host().host() + ":" + binding.absoluteRemotePath();
        Notifications.Bus.notify(new Notification(
            NOTIFICATION_GROUP,
            "Uploaded",
            title,
            NotificationType.INFORMATION));
    }

    private static void notifyUploadFailure(
        @NotNull RemoteFileBinding binding,
        @NotNull IOException cause
    ) {
        String title = binding.host().host() + ":" + binding.absoluteRemotePath();
        Notification n = new Notification(
            NOTIFICATION_GROUP,
            "Upload failed",
            title + "\n" + cause.getMessage(),
            NotificationType.ERROR);
        n.addAction(new NotificationAction("Retry upload") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                notification.expire();
                AppExecutorUtil.getAppExecutorService().submit(() -> {
                    try {
                        SftpSingleFileTransfer.upload(
                            binding.session().client(),
                            binding.tempPath(),
                            binding.absoluteRemotePath());
                        notifyUploadSuccess(binding);
                    } catch (IOException retryErr) {
                        notifyUploadFailure(binding, retryErr);
                    }
                });
            }
        });
        Notifications.Bus.notify(n);
    }
}
```

- [ ] **Step 2: Register the listener and the notification group in `plugins/editor/resources/META-INF/plugin.xml`**

Replace the current contents of `plugin.xml` with:

```xml
<idea-plugin>
    <id>com.termlab.editor</id>
    <name>TermLab Light Editor</name>
    <version>0.1.0</version>
    <vendor>TermLab</vendor>
    <description>
        Opt-in light editor for scratches and SFTP-triggered file editing.
        Disabled by default; enabled via the first-launch notification or
        Settings → TermLab → Light Editor. Requires a restart to
        toggle.
    </description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.termlab.core</depends>
    <depends>com.termlab.sftp</depends>
    <depends>org.jetbrains.plugins.textmate</depends>

    <extensions defaultExtensionNs="com.intellij">
        <notificationGroup
            id="TermLab Light Editor"
            displayType="BALLOON"/>

        <applicationService
            serviceImplementation="com.termlab.editor.remote.RemoteFileBindingRegistry"/>

        <applicationService
            serviceImplementation="com.termlab.editor.remote.RemoteEditService"/>
    </extensions>

    <applicationListeners>
        <listener class="com.termlab.editor.remote.RemoteSaveListener"
                  topic="com.intellij.openapi.fileEditor.FileDocumentManagerListener"/>
    </applicationListeners>
</idea-plugin>
```

- [ ] **Step 3: Build**

Run: `bash bazel.cmd build //termlab/plugins/editor:editor`
Expected: `Build completed successfully`.

- [ ] **Step 4: Commit**

```bash
git add plugins/editor/src/com/termlab/editor/remote/RemoteSaveListener.java plugins/editor/resources/META-INF/plugin.xml
git commit -m "feat(editor): upload remote-bound files on document save"
```

---

## Task 14: Cleanup on tab close, app shutdown, and startup orphan sweep

**Files:**
- Create: `plugins/editor/src/com/termlab/editor/remote/RemoteEditorCleanup.java`
- Create: `plugins/editor/src/com/termlab/editor/remote/RemoteEditorShutdownListener.java`
- Create: `plugins/editor/src/com/termlab/editor/remote/RemoteEditorStartupSweep.java`
- Create: `plugins/editor/src/com/termlab/editor/remote/RemoteEditorProjectListener.java`
- Modify: `plugins/editor/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `RemoteEditorCleanup.java`** — shared helper used by all three triggers

```java
package com.termlab.editor.remote;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Cleanup helpers for SFTP temp files. Each call tolerates
 * missing files and partial failures — cleanup is best-effort.
 */
public final class RemoteEditorCleanup {

    private static final Logger LOG = Logger.getInstance(RemoteEditorCleanup.class);

    private RemoteEditorCleanup() {}

    /**
     * Delete a single temp file and walk up its parent dirs,
     * deleting any that became empty as a result.
     */
    public static void deleteTempFileAndEmptyParents(@NotNull Path tempFile, @NotNull Path stopAt) {
        try { Files.deleteIfExists(tempFile); }
        catch (IOException e) { LOG.warn("Failed to delete temp file: " + tempFile, e); }
        Path parent = tempFile.getParent();
        while (parent != null && !parent.equals(stopAt) && parent.startsWith(stopAt)) {
            if (!isEmptyDirectory(parent)) break;
            try { Files.delete(parent); }
            catch (IOException e) { LOG.warn("Failed to delete empty dir: " + parent, e); break; }
            parent = parent.getParent();
        }
    }

    /**
     * Recursively delete the entire temp root. Used on shutdown
     * and as the startup orphan sweep.
     */
    public static void purgeRoot(@NotNull Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException e) { LOG.warn("Failed to delete " + p, e); }
            });
        } catch (IOException e) {
            LOG.warn("Failed to walk temp root " + root, e);
        }
    }

    private static boolean isEmptyDirectory(@NotNull Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }
}
```

- [ ] **Step 2: Create `RemoteEditorShutdownListener.java`**

```java
package com.termlab.editor.remote;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * On IDE shutdown, clear the temp root entirely. Anything
 * important is either saved-back to remote or explicitly
 * discarded by the user.
 */
public final class RemoteEditorShutdownListener implements AppLifecycleListener {

    @Override
    public void appWillBeClosed(boolean isRestart) {
        RemoteEditService service = ApplicationManager.getApplication()
            .getService(RemoteEditService.class);
        if (service == null) return;
        RemoteEditorCleanup.purgeRoot(service.tempRoot());
        RemoteFileBindingRegistry registry = ApplicationManager.getApplication()
            .getService(RemoteFileBindingRegistry.class);
        if (registry != null) registry.clear();
    }

    // appClosing override point — unused, but listed here in case
    // a future platform contract requires it.
    @Override
    public void appClosing() {}
}
```

- [ ] **Step 3: Create `RemoteEditorStartupSweep.java`** — runs on plugin load to clean up any files left behind after a crash

```java
package com.termlab.editor.remote;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.util.concurrency.AppExecutorUtil;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runs once per project open when the editor plugin is enabled.
 * Sweeps the temp root to remove orphaned files from previous
 * crashed or force-quit sessions.
 *
 * <p>Guarded against multiple sweeps in the same session by a
 * simple atomic flag on the {@link RemoteEditService}.
 */
public final class RemoteEditorStartupSweep implements ProjectActivity {

    private static volatile boolean sweptThisSession = false;

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (sweptThisSession) return Unit.INSTANCE;
        sweptThisSession = true;
        AppExecutorUtil.getAppExecutorService().submit(() -> {
            RemoteEditService service = ApplicationManager.getApplication()
                .getService(RemoteEditService.class);
            if (service != null) {
                RemoteEditorCleanup.purgeRoot(service.tempRoot());
            }
        });
        return Unit.INSTANCE;
    }
}
```

- [ ] **Step 4: Create `RemoteEditorProjectListener.java`** — per-project listener for tab-close cleanup

```java
package com.termlab.editor.remote;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Project-scoped listener that cleans up temp files when their
 * editor tabs close. Registered via {@code <projectListeners>}
 * so it's instantiated per project.
 */
public final class RemoteEditorProjectListener implements FileEditorManagerListener {

    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        Path closedPath = Paths.get(file.getPath()).toAbsolutePath();
        RemoteFileBindingRegistry registry = ApplicationManager.getApplication()
            .getService(RemoteFileBindingRegistry.class);
        if (registry == null) return;
        RemoteFileBinding binding = registry.remove(closedPath.toString());
        if (binding == null) return;
        RemoteEditService service = ApplicationManager.getApplication()
            .getService(RemoteEditService.class);
        if (service == null) return;
        RemoteEditorCleanup.deleteTempFileAndEmptyParents(
            binding.tempPath(), service.tempRoot());
    }
}
```

- [ ] **Step 5: Register listeners and the startup sweep in `plugin.xml`**

In `plugins/editor/resources/META-INF/plugin.xml`, extend the `<extensions>` block by adding `<postStartupActivity>` and extend listeners. The `<extensions>` block becomes:

```xml
    <extensions defaultExtensionNs="com.intellij">
        <notificationGroup
            id="TermLab Light Editor"
            displayType="BALLOON"/>

        <applicationService
            serviceImplementation="com.termlab.editor.remote.RemoteFileBindingRegistry"/>

        <applicationService
            serviceImplementation="com.termlab.editor.remote.RemoteEditService"/>

        <postStartupActivity
            implementation="com.termlab.editor.remote.RemoteEditorStartupSweep"/>
    </extensions>
```

Extend `<applicationListeners>`:

```xml
    <applicationListeners>
        <listener class="com.termlab.editor.remote.RemoteSaveListener"
                  topic="com.intellij.openapi.fileEditor.FileDocumentManagerListener"/>
        <listener class="com.termlab.editor.remote.RemoteEditorShutdownListener"
                  topic="com.intellij.ide.AppLifecycleListener"/>
    </applicationListeners>
```

Add a `<projectListeners>` block directly after `</applicationListeners>`:

```xml
    <projectListeners>
        <listener class="com.termlab.editor.remote.RemoteEditorProjectListener"
                  topic="com.intellij.openapi.fileEditor.FileEditorManagerListener"/>
    </projectListeners>
```

- [ ] **Step 6: Build**

Run: `bash bazel.cmd build //termlab/plugins/editor:editor`
Expected: `Build completed successfully`. If you see unresolved Kotlin `Continuation` / `Unit`, verify `@lib//:kotlin-stdlib` is still in `deps`.

- [ ] **Step 7: Commit**

```bash
git add plugins/editor/src/com/termlab/editor/remote/RemoteEditorCleanup.java plugins/editor/src/com/termlab/editor/remote/RemoteEditorShutdownListener.java plugins/editor/src/com/termlab/editor/remote/RemoteEditorStartupSweep.java plugins/editor/src/com/termlab/editor/remote/RemoteEditorProjectListener.java plugins/editor/resources/META-INF/plugin.xml
git commit -m "feat(editor): temp file cleanup on tab close, shutdown, startup sweep"
```

---

## Task 15: Register the SFTP opener bridge implementations in the editor plugin

**Files:**
- Create: `plugins/editor/src/com/termlab/editor/sftp/EditorRemoteFileOpener.java`
- Create: `plugins/editor/src/com/termlab/editor/sftp/EditorLocalFileOpener.java`
- Modify: `plugins/editor/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `EditorRemoteFileOpener.java`**

```java
package com.termlab.editor.sftp;

import com.termlab.editor.remote.RemoteEditService;
import com.termlab.sftp.client.SshSftpSession;
import com.termlab.sftp.model.RemoteFileEntry;
import com.termlab.sftp.spi.RemoteFileOpener;
import com.termlab.ssh.model.SshHost;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class EditorRemoteFileOpener implements RemoteFileOpener {

    @Override
    public void open(
        @NotNull Project project,
        @NotNull SshHost host,
        @NotNull SshSftpSession session,
        @NotNull String absoluteRemotePath,
        @NotNull RemoteFileEntry entry
    ) {
        RemoteEditService service = ApplicationManager.getApplication()
            .getService(RemoteEditService.class);
        if (service == null) return;
        service.openRemoteFile(project, host, session, absoluteRemotePath, entry);
    }
}
```

- [ ] **Step 2: Create `EditorLocalFileOpener.java`**

```java
package com.termlab.editor.sftp;

import com.termlab.editor.remote.RemoteEditService;
import com.termlab.sftp.model.LocalFileEntry;
import com.termlab.sftp.spi.LocalFileOpener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class EditorLocalFileOpener implements LocalFileOpener {

    @Override
    public void open(@NotNull Project project, @NotNull LocalFileEntry entry) {
        RemoteEditService service = ApplicationManager.getApplication()
            .getService(RemoteEditService.class);
        if (service == null) return;
        service.openLocalFile(project, entry);
    }
}
```

- [ ] **Step 3: Register the extension implementations in `plugins/editor/resources/META-INF/plugin.xml`**

Add a new `<extensions defaultExtensionNs="com.termlab.sftp">` block directly after the existing `<extensions defaultExtensionNs="com.intellij">` block:

```xml
    <extensions defaultExtensionNs="com.termlab.sftp">
        <localFileOpener implementation="com.termlab.editor.sftp.EditorLocalFileOpener"/>
        <remoteFileOpener implementation="com.termlab.editor.sftp.EditorRemoteFileOpener"/>
    </extensions>
```

- [ ] **Step 4: Build**

Run: `bash bazel.cmd build //termlab/plugins/editor:editor`
Expected: `Build completed successfully`.

- [ ] **Step 5: Commit**

```bash
git add plugins/editor/src/com/termlab/editor/sftp/ plugins/editor/resources/META-INF/plugin.xml
git commit -m "feat(editor): register SFTP file opener bridge implementations"
```

---

## Task 16: Implement `NewScratchAction`

**Files:**
- Create: `plugins/editor/src/com/termlab/editor/scratch/ScratchCounter.java`
- Create: `plugins/editor/src/com/termlab/editor/scratch/NewScratchAction.java`
- Modify: `plugins/editor/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `ScratchCounter.java`**

```java
package com.termlab.editor.scratch;

import java.util.concurrent.atomic.AtomicInteger;

/** Session-scoped scratch file counter. Reset every launch. */
public final class ScratchCounter {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    private ScratchCounter() {}

    public static int next() {
        return COUNTER.incrementAndGet();
    }
}
```

- [ ] **Step 2: Create `NewScratchAction.java`**

```java
package com.termlab.editor.scratch;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Opens an empty scratch buffer as a {@link LightVirtualFile} in
 * the main editor area. First save triggers a Save-As dialog via
 * {@link ScratchSaveListener}.
 */
public final class NewScratchAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getData(CommonDataKeys.PROJECT);
        if (project == null) return;
        int n = ScratchCounter.next();
        LightVirtualFile file = new LightVirtualFile(
            "scratch-" + n + ".txt",
            PlainTextFileType.INSTANCE,
            "");
        file.putUserData(ScratchMarker.KEY, Boolean.TRUE);
        FileEditorManager.getInstance(project).openFile(file, true);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
```

- [ ] **Step 3: Create `ScratchMarker.java`** — a tiny marker class so we can identify scratch `LightVirtualFile`s later

`plugins/editor/src/com/termlab/editor/scratch/ScratchMarker.java`:

```java
package com.termlab.editor.scratch;

import com.intellij.openapi.util.Key;

/** Marker key used to distinguish scratch LightVirtualFiles from other LightVirtualFiles. */
public final class ScratchMarker {

    public static final Key<Boolean> KEY = Key.create("termlab.editor.scratchMarker");

    private ScratchMarker() {}
}
```

- [ ] **Step 4: Register the action and add the File menu + keybinding in `plugin.xml`**

Add an `<actions>` block to `plugins/editor/resources/META-INF/plugin.xml`, after the `<projectListeners>` block:

```xml
    <actions>
        <action id="TermLab.Editor.NewScratch"
                class="com.termlab.editor.scratch.NewScratchAction"
                text="New Scratch File"
                description="Create a new empty scratch text file">
            <add-to-group group-id="FileMenu" anchor="first"/>
            <keyboard-shortcut keymap="$default" first-keystroke="control N"/>
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="meta N" replace-all="true"/>
            <keyboard-shortcut keymap="Mac OS X" first-keystroke="meta N" replace-all="true"/>
        </action>
    </actions>
```

- [ ] **Step 5: Build**

Run: `bash bazel.cmd build //termlab/plugins/editor:editor`
Expected: `Build completed successfully`.

- [ ] **Step 6: Commit**

```bash
git add plugins/editor/src/com/termlab/editor/scratch/ plugins/editor/resources/META-INF/plugin.xml
git commit -m "feat(editor): New Scratch File action bound to Cmd/Ctrl+N"
```

---

## Task 17: Save-As hook for scratch files

**Context:** When the user hits `Cmd+S` on a scratch `LightVirtualFile`, the default platform save path tries to write to a light FS and quietly fails. We intercept at `FileDocumentManagerListener.beforeDocumentSaving`, show a save-as dialog, write to the chosen path, close the scratch tab, and open the real file.

Since `LightVirtualFile` has no persistent backing, intercepting the save *without* writing elsewhere is the correct platform behavior. We perform the Save-As side effect here and let the platform's no-op save continue.

**Files:**
- Create: `plugins/editor/src/com/termlab/editor/scratch/ScratchSaveListener.java`
- Modify: `plugins/editor/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `ScratchSaveListener.java`**

```java
package com.termlab.editor.scratch;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.FileSaverDialog;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Intercepts saves of scratch {@link LightVirtualFile}s, shows a
 * Save-As dialog, writes the buffer to the chosen location, and
 * swaps the editor tab to point at the real file.
 */
public final class ScratchSaveListener implements FileDocumentManagerListener {

    @Override
    public void beforeDocumentSaving(@NotNull Document document) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (!(file instanceof LightVirtualFile lvf)) return;
        if (lvf.getUserData(ScratchMarker.KEY) != Boolean.TRUE) return;

        // Scratch saves happen on the EDT (save All is EDT). Dialog
        // call is synchronous — this is the correct thread.
        Project project = firstOpenProject();
        if (project == null) return;

        FileSaverDescriptor descriptor = new FileSaverDescriptor(
            "Save Scratch As",
            "Choose a location for " + lvf.getName());
        FileSaverDialog dialog = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project);
        VirtualFileWrapper wrapper = dialog.save(lvf.getName());
        if (wrapper == null) return;

        try {
            java.nio.file.Path target = wrapper.getFile().toPath();
            java.nio.file.Files.writeString(target, document.getText(),
                java.nio.charset.StandardCharsets.UTF_8);
            VirtualFile saved = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target);
            if (saved == null) saved = VfsUtil.findFileByIoFile(wrapper.getFile(), true);

            // Close the scratch tab and open the new real file.
            FileEditorManager mgr = FileEditorManager.getInstance(project);
            mgr.closeFile(lvf);
            if (saved != null) {
                final VirtualFile toOpen = saved;
                ApplicationManager.getApplication().invokeLater(() -> mgr.openFile(toOpen, true));
            }
        } catch (IOException e) {
            // Leave the tab open; user can try again. A minimal
            // notification isn't critical here — the dialog error
            // surface is enough for MVP.
        }
    }

    private static Project firstOpenProject() {
        Project[] open = ProjectManager.getInstance().getOpenProjects();
        return open.length == 0 ? null : open[0];
    }
}
```

- [ ] **Step 2: Register the listener**

In `plugins/editor/resources/META-INF/plugin.xml`, extend `<applicationListeners>` by adding a second listener:

```xml
    <applicationListeners>
        <listener class="com.termlab.editor.remote.RemoteSaveListener"
                  topic="com.intellij.openapi.fileEditor.FileDocumentManagerListener"/>
        <listener class="com.termlab.editor.scratch.ScratchSaveListener"
                  topic="com.intellij.openapi.fileEditor.FileDocumentManagerListener"/>
        <listener class="com.termlab.editor.remote.RemoteEditorShutdownListener"
                  topic="com.intellij.ide.AppLifecycleListener"/>
    </applicationListeners>
```

- [ ] **Step 3: Build**

Run: `bash bazel.cmd build //termlab/plugins/editor:editor`
Expected: `Build completed successfully`.

- [ ] **Step 4: Commit**

```bash
git add plugins/editor/src/com/termlab/editor/scratch/ScratchSaveListener.java plugins/editor/resources/META-INF/plugin.xml
git commit -m "feat(editor): scratch save-as flow on first save"
```

---

## Task 18: Core — first-launch notification group and notifier

**Files:**
- Modify: `core/resources/META-INF/plugin.xml`
- Create: `core/src/com/termlab/core/editor/FirstLaunchEditorNotifier.java`

- [ ] **Step 1: Add the notification group to `core/resources/META-INF/plugin.xml`**

In the `<extensions defaultExtensionNs="com.intellij">` block, add (next to existing `searchEverywhereContributor` or similar — location within the block doesn't matter):

```xml
        <notificationGroup
            id="com.termlab.editor.firstLaunch"
            displayType="STICKY_BALLOON"/>
```

- [ ] **Step 2: Add a post-startup activity registration in the same `<extensions>` block**

```xml
        <postStartupActivity
            implementation="com.termlab.core.editor.FirstLaunchEditorNotifier"/>
```

- [ ] **Step 3: Create `FirstLaunchEditorNotifier.java`**

```java
package com.termlab.core.editor;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One-time first-launch notification for the opt-in light editor.
 * Runs on the first project-open that sees no prior
 * {@code termlab.editor.firstLaunchHandled} flag and presents the
 * user with an Enable / Not now / Don't ask again choice.
 */
public final class FirstLaunchEditorNotifier implements ProjectActivity {

    public static final String FIRST_LAUNCH_KEY = "termlab.editor.firstLaunchHandled";
    private static final String NOTIFICATION_GROUP_ID = "com.termlab.editor.firstLaunch";

    public static final PluginId EDITOR_PLUGIN_ID = PluginId.getId("com.termlab.editor");
    public static final PluginId TEXTMATE_PLUGIN_ID = PluginId.getId("org.jetbrains.plugins.textmate");

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        PropertiesComponent props = PropertiesComponent.getInstance();
        if (props.getBoolean(FIRST_LAUNCH_KEY, false)) return Unit.INSTANCE;

        Notification n = new Notification(
            NOTIFICATION_GROUP_ID,
            "Light scripting & file editing",
            "TermLab can provide an editor-like environment for light"
                + " scripting and remote file editing. Enable it?",
            NotificationType.INFORMATION);

        n.addAction(new NotificationAction("Enable") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                enablePluginsAndRestart();
                props.setValue(FIRST_LAUNCH_KEY, true);
                notification.expire();
            }
        });
        n.addAction(new NotificationAction("Not now") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                notification.expire();
            }
        });
        n.addAction(new NotificationAction("Don't ask again") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                props.setValue(FIRST_LAUNCH_KEY, true);
                notification.expire();
            }
        });

        Notifications.Bus.notify(n, project);
        return Unit.INSTANCE;
    }

    public static void enablePluginsAndRestart() {
        PluginManagerCore.enablePlugins(List.of(EDITOR_PLUGIN_ID, TEXTMATE_PLUGIN_ID), true);
        ApplicationManager.getApplication().invokeLater(() ->
            ApplicationManagerEx.getApplicationEx().restart(true));
    }

    public static void disablePluginsAndRestart() {
        PluginManagerCore.disablePlugins(List.of(EDITOR_PLUGIN_ID, TEXTMATE_PLUGIN_ID), true);
        ApplicationManager.getApplication().invokeLater(() ->
            ApplicationManagerEx.getApplicationEx().restart(true));
    }

    public static boolean isEditorEnabled() {
        return !PluginManagerCore.isDisabled(EDITOR_PLUGIN_ID);
    }
}
```

- [ ] **Step 4: Build core**

Run: `bash bazel.cmd build //termlab/core:core`
Expected: `Build completed successfully`. If `PluginManagerCore.enablePlugins`/`disablePlugins` signatures don't match (they have shifted across platform versions), try the single-argument variants `enablePlugin(PluginId)` / `disablePlugin(PluginId)` in a loop.

- [ ] **Step 5: Commit**

```bash
git add core/resources/META-INF/plugin.xml core/src/com/termlab/core/editor/FirstLaunchEditorNotifier.java
git commit -m "feat(core): first-launch notification for opt-in light editor"
```

---

## Task 19: Core — Workbench settings page and Light Editor subpage

**Files:**
- Create: `core/src/com/termlab/core/settings/TermLabWorkbenchConfigurable.java`
- Create: `core/src/com/termlab/core/settings/LightEditorConfigurable.java`
- Modify: `core/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `TermLabWorkbenchConfigurable.java`** (parent node, mirror of `TermLabTerminalConfigurable`)

```java
package com.termlab.core.settings;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Parent node for workbench-level (non-terminal) settings. Child
 * configurables provide the actual editable categories.
 */
public final class TermLabWorkbenchConfigurable implements SearchableConfigurable {

    public static final String ID = "termlab.workbench.settings";

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "TermLab";
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel label = new JLabel("Select a category on the left (Light Editor).");
        label.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(label, BorderLayout.NORTH);
        return panel;
    }

    @Override
    public boolean isModified() { return false; }

    @Override
    public void apply() throws ConfigurationException {}

    @Override
    public void reset() {}
}
```

- [ ] **Step 2: Create `LightEditorConfigurable.java`**

```java
package com.termlab.core.settings;

import com.termlab.core.editor.FirstLaunchEditorNotifier;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Settings subpage that exposes a single checkbox to enable or
 * disable the opt-in light editor plugin. Changes take effect
 * after an IDE restart.
 */
public final class LightEditorConfigurable implements SearchableConfigurable {

    public static final String ID = "termlab.workbench.settings.editor";

    private JCheckBox checkbox;
    private JLabel statusLabel;
    private boolean initialEnabled;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Light Editor";
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        checkbox = new JCheckBox("Enable light editor and remote file editing");
        checkbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(checkbox);

        JLabel desc = new JLabel(
            "<html>Allows creating scratch files and editing files from the SFTP panel."
                + " Requires a restart to apply.</html>");
        desc.setAlignmentX(Component.LEFT_ALIGNMENT);
        desc.setBorder(BorderFactory.createEmptyBorder(4, 24, 8, 0));
        panel.add(desc);

        statusLabel = new JLabel(" ");
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        panel.add(statusLabel);

        checkbox.addActionListener(e -> updateStatusLabel());

        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        return checkbox != null && checkbox.isSelected() != initialEnabled;
    }

    @Override
    public void apply() throws ConfigurationException {
        if (checkbox == null) return;
        boolean want = checkbox.isSelected();
        if (want == initialEnabled) return;

        int confirm = Messages.showYesNoDialog(
            (want ? "Enable" : "Disable") + " the light editor? TermLab will restart.",
            "Restart Required",
            Messages.getQuestionIcon());
        if (confirm != Messages.YES) {
            checkbox.setSelected(initialEnabled);
            return;
        }
        if (want) {
            FirstLaunchEditorNotifier.enablePluginsAndRestart();
        } else {
            FirstLaunchEditorNotifier.disablePluginsAndRestart();
        }
    }

    @Override
    public void reset() {
        if (checkbox == null) return;
        initialEnabled = FirstLaunchEditorNotifier.isEditorEnabled();
        checkbox.setSelected(initialEnabled);
        updateStatusLabel();
    }

    private void updateStatusLabel() {
        if (checkbox == null || statusLabel == null) return;
        if (checkbox.isSelected() == initialEnabled) {
            statusLabel.setText(initialEnabled ? "Currently enabled." : "Currently disabled.");
        } else {
            statusLabel.setText("Restart required to apply.");
        }
    }
}
```

- [ ] **Step 3: Register both configurables in `core/resources/META-INF/plugin.xml`**

In the `<extensions defaultExtensionNs="com.intellij">` block, add (after the existing terminal configurables):

```xml
        <applicationConfigurable
            parentId="root"
            instance="com.termlab.core.settings.TermLabWorkbenchConfigurable"
            id="termlab.workbench.settings"
            displayName="TermLab"/>

        <applicationConfigurable
            parentId="termlab.workbench.settings"
            instance="com.termlab.core.settings.LightEditorConfigurable"
            id="termlab.workbench.settings.editor"
            displayName="Light Editor"/>
```

- [ ] **Step 4: Build core**

Run: `bash bazel.cmd build //termlab/core:core`
Expected: `Build completed successfully`.

- [ ] **Step 5: Commit**

```bash
git add core/src/com/termlab/core/settings/TermLabWorkbenchConfigurable.java core/src/com/termlab/core/settings/LightEditorConfigurable.java core/resources/META-INF/plugin.xml
git commit -m "feat(core): Workbench settings page with Light Editor toggle"
```

---

## Task 20: End-to-end manual verification

**Context:** Most of the wired-together behavior can't be unit-tested without a full IntelliJ test fixture. Run through the checklist below against a running TermLab; record any deviations as follow-up tasks.

**Files:** none (validation only)

- [ ] **Step 1: Build the whole product**

Run: `bash bazel.cmd build //termlab:termlab_run`
Expected: `Build completed successfully`.

- [ ] **Step 2: Reset the first-launch flag so the prompt re-appears**

From the TermLab dev config, delete the persisted flag. If `config/termlab/options/other.xml` exists, edit it and remove any line containing `termlab.editor.firstLaunchHandled`. If the file doesn't exist, nothing to do — the flag defaults to false.

- [ ] **Step 3: Launch and verify the first-launch notification appears**

Run: `bash bazel.cmd run //termlab:termlab_run`
Expected: within a few seconds of the terminal appearing, a sticky balloon appears titled "Light scripting & file editing" with three actions.

- [ ] **Step 4: Click Enable; verify restart and plugin state**

Click Enable. TermLab should close and restart. After restart, open `TermLab → Preferences → TermLab → Light Editor`. The checkbox should be checked.

Also: `File → New Scratch File` should now exist in the menu (and `Cmd+N` / `Ctrl+N` should work).

- [ ] **Step 5: Create a scratch, save as, verify the real file opens**

- `Cmd+N` → empty `scratch-1.txt` tab opens.
- Type a few lines.
- `Cmd+S` → Save-As dialog appears. Pick `~/scratch-test.txt` and save.
- The scratch tab should close and a new `scratch-test.txt` tab should open.
- Modify it, `Cmd+S` — should save directly to disk without a dialog.

- [ ] **Step 6: Open a local file from the SFTP local pane**

- Open the SFTP tool window.
- In the local pane, double-click an existing text file (e.g., `README.md`).
- Expected: the file opens in the main editor area as a new tab.

- [ ] **Step 7: Open a blocked file type**

- Double-click an image or `.zip` file in the SFTP local pane.
- Expected: an error balloon "Cannot edit …: binary file type." No tab opens.

- [ ] **Step 8: Open a large file**

- Find or create a file larger than 5 MB in a local directory browsable by the SFTP local pane.
- Double-click it.
- Expected: error balloon "File too large …".

- [ ] **Step 9: Edit a remote file round-trip**

- Connect to an SSH host, navigate to a text file in the remote pane.
- Double-click it. Expected: a brief delay (download), then the file opens in the main editor area.
- Edit and save. Expected: a confirmation balloon "Uploaded" appears.
- From another terminal, SSH into the same host and `cat` the file — contents should match your edits.

- [ ] **Step 10: Verify temp-file cleanup on tab close**

- After step 9, note the path in `PathManager.getSystemPath()/termlab-sftp-edits/` — find it via `ls $(bash bazel.cmd run //termlab:termlab_run -- --print-system-path 2>/dev/null || echo "$HOME/Library/Caches/termlab/termlab-sftp-edits")`. (In practice, `~/Library/Caches/JetBrains/TermLab/termlab-sftp-edits/` or similar.)
- Close the editor tab for the remote file.
- The corresponding temp file should be gone.

- [ ] **Step 11: Disable via Settings and verify restart**

- Preferences → TermLab → Light Editor → uncheck, Apply.
- Confirm the restart prompt, accept.
- After restart: `New Scratch File` should be gone from the File menu; SFTP double-click on a file should be a no-op again.

- [ ] **Step 12: Commit the manual verification record**

Document the verification result in the commit message. If any step failed, create follow-up tasks (in a followups file, not here) before committing.

```bash
git commit --allow-empty -m "chore(editor): manual e2e verification passed for light editor MVP"
```

---

## Out of Scope for This Plan (Follow-ups)

These were flagged in the spec and should not be done here:

1. Run scripts locally or remotely (second half of the original motivation).
2. IdeaVim compatibility verification.
3. Conflict detection and auto-reload for remote edits.
4. Configurable size cap and blocklist.
5. Plaintext-only mode skipping TextMate.
6. Installer-level `disabled_plugins.txt` seeding for packaged artifacts (roadmap Track C).
