# File Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Files" tab to TermLab's Search Everywhere that searches filenames under the currently-selected SFTP pane's local cwd and (when connected) its remote cwd, opens selections in the light editor via existing `LocalFileOpener` / `RemoteFileOpener` extension points, and shows a `local` / `<hostname>` source chip per row.

**Architecture:** One new plugin (`plugins/search/`) containing five focused classes (`FileLister`, `FileSearchFilter`, `FileSearchStatusProgress`, `FileSearchContributor`, `FileHit`). A sixth class, `FileListCache`, lives inside the existing SFTP plugin (`plugins/sftp/src/com/termlab/sftp/search/`) because the pane owns its lifecycle — the new search plugin depends on SFTP, not the other way around. Caches are pane-owned, lazy-built, and invalidated on cwd change / reconnect. Listings shell out to `rg → fd → find` (probed per side), cached in-memory, then filtered with `MinusculeMatcher` on query. If the cache misses, the contributor fires a live query as a fallback.

**Tech Stack:** Java (JDK 21), IntelliJ Platform APIs (`SearchEverywhereContributor`, `MinusculeMatcher`, `Task.Backgroundable`, `PathManager`, `Notification`), Apache MINA SSHD (`ClientSession.createExecChannel`), JUnit 5, Bazel.

**Driving spec:** `docs/superpowers/specs/2026-04-17-file-search-design.md`

---

## File Structure

**New files — search plugin:**
- `plugins/search/BUILD.bazel`
- `plugins/search/resources/META-INF/plugin.xml`
- `plugins/search/src/com/termlab/search/FileHit.java` — record + `Side` enum
- `plugins/search/src/com/termlab/search/FileSearchFilter.java` — `PersistentStateComponent` holding exclusion chips, custom globs, regex, and per-host balloon suppression flags
- `plugins/search/src/com/termlab/search/FileLister.java` — tool probing + command construction + subprocess / exec-channel execution
- `plugins/search/src/com/termlab/search/FileSearchStatusProgress.java` — `Task.Backgroundable` wrapper with title-format and cancel-to-EMPTY
- `plugins/search/src/com/termlab/search/FileSearchContributor.java` — `SearchEverywhereContributor<FileHit>` + `Factory`
- `plugins/search/test/com/termlab/search/TestRunner.java` — JUnit 5 launcher (mirrors other plugins)
- `plugins/search/test/com/termlab/search/FileListerTest.java`
- `plugins/search/test/com/termlab/search/FileSearchFilterTest.java`

**New files — SFTP plugin:**
- `plugins/sftp/src/com/termlab/sftp/search/FileListCache.java` — in-memory state holder + state machine
- `plugins/sftp/test/com/termlab/sftp/search/FileListCacheTest.java`

**Modified files:**
- `plugins/sftp/src/com/termlab/sftp/toolwindow/LocalFilePane.java` — add `FileListCache` field, public accessor, invalidate call in `reload`
- `plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java` — add `FileListCache` field, public accessor, invalidate calls in `navigateRemote`, `connect`, `disconnect`
- `core/src/com/termlab/core/palette/TermLabTabsCustomizationStrategy.java` — add `"TermLabFiles"` to `ALLOWED_TAB_IDS`
- `BUILD.bazel` (repo root) — add `//termlab/plugins/search` to the `termlab_run` runtime deps

---

## Task 1: Plugin scaffolding

**Files:**
- Create: `plugins/search/BUILD.bazel`
- Create: `plugins/search/resources/META-INF/plugin.xml`
- Create: `plugins/search/test/com/termlab/search/TestRunner.java`
- Create: `plugins/search/src/com/termlab/search/package-info.java`

No runtime behavior yet — this commit just gives the plugin a compilable skeleton so subsequent tasks have a place to land.

- [ ] **Step 1: Create `plugins/search/BUILD.bazel`**

```python
load("@rules_java//java:defs.bzl", "java_binary")
load("@rules_jvm//:jvm.bzl", "jvm_library", "resourcegroup")

resourcegroup(
    name = "search_resources",
    srcs = glob(["resources/**/*"]),
    strip_prefix = "resources",
)

jvm_library(
    name = "search",
    module_name = "intellij.termlab.search",
    visibility = ["//visibility:public"],
    srcs = glob(["src/**/*.java"], allow_empty = True),
    resources = [":search_resources"],
    deps = [
        "//termlab/sdk",
        "//termlab/core",
        "//termlab/plugins/ssh",
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
        "//libraries/sshd-osgi",
        "@lib//:jetbrains-annotations",
        "@lib//:kotlin-stdlib",
    ],
)

jvm_library(
    name = "search_test_lib",
    module_name = "intellij.termlab.search.tests",
    visibility = ["//visibility:public"],
    srcs = glob(["test/**/*.java"], allow_empty = True),
    deps = [
        ":search",
        "//termlab/sdk",
        "//termlab/plugins/ssh",
        "//termlab/plugins/sftp",
        "//libraries/junit5",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
        "//platform/ide-core",
        "//platform/platform-api:ide",
        "//platform/projectModel-api:projectModel",
        "//platform/util",
        "//platform/util:util-ui",
        "@lib//:jetbrains-annotations",
        "@lib//:kotlin-stdlib",
    ],
)

java_binary(
    name = "search_test_runner",
    main_class = "com.termlab.search.TestRunner",
    runtime_deps = [
        ":search_test_lib",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
    ],
)

exports_files(["intellij.termlab.search.iml"], visibility = ["//visibility:public"])
```

- [ ] **Step 2: Create `plugins/search/resources/META-INF/plugin.xml`** (minimal — contributor and service registrations come later)

```xml
<idea-plugin>
    <id>com.termlab.search</id>
    <name>File Search</name>
    <version>0.1.0</version>
    <vendor>TermLab</vendor>
    <description>
        Filename-only file search across the SFTP plugin's active
        local + remote panes, surfaced as a "Files" tab in Search
        Everywhere. Shells out to ripgrep / fd / find rather than
        maintaining its own index.
    </description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.termlab.core</depends>
    <depends>com.termlab.ssh</depends>
    <depends>com.termlab.sftp</depends>

    <extensions defaultExtensionNs="com.intellij">
        <notificationGroup
            id="TermLab File Search"
            displayType="BALLOON"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 3: Create `plugins/search/test/com/termlab/search/TestRunner.java`**

```java
package com.termlab.search;

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
            .selectors(DiscoverySelectors.selectPackage("com.termlab.search"))
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

- [ ] **Step 4: Create `plugins/search/src/com/termlab/search/package-info.java`** (placeholder so `glob("src/**/*.java")` is non-empty)

```java
/**
 * TermLab file-search plugin — filename-only search across the SFTP
 * active panes, exposed as a Search Everywhere tab.
 */
package com.termlab.search;
```

- [ ] **Step 5: Build the plugin to verify scaffolding compiles**

Run: `make termlab-build`
Expected: no errors. The plugin target builds even though it has no functional code yet.

- [ ] **Step 6: Commit**

```bash
git add plugins/search/
git commit -m "feat(search): plugin scaffolding for file search

Empty plugin skeleton — BUILD.bazel, plugin.xml, test runner, and
package placeholder — so subsequent tasks have a place to land.
Not yet registered in the root BUILD.bazel runtime deps."
```

---

## Task 2: `FileListCache` — state machine (TDD)

**Files:**
- Create: `plugins/sftp/src/com/termlab/sftp/search/FileListCache.java`
- Create: `plugins/sftp/test/com/termlab/sftp/search/FileListCacheTest.java`

The cache is a plain in-memory state holder — no IntelliJ dependencies, no executor management. Build orchestration lives in `FileSearchContributor` later. The cache is only responsible for: holding state, atomic transitions, storing results, truncation at 200k.

- [ ] **Step 1: Write the failing test**

Create `plugins/sftp/test/com/termlab/sftp/search/FileListCacheTest.java`:

```java
package com.termlab.sftp.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileListCacheTest {

    @Test
    void startsEmpty() {
        FileListCache cache = new FileListCache();
        assertEquals(FileListCache.State.EMPTY, cache.state());
        assertNull(cache.paths());
        assertFalse(cache.truncated());
    }

    @Test
    void beginBuildTransitionsToBuilding() {
        FileListCache cache = new FileListCache();
        boolean acquired = cache.beginBuild("/home/foo");
        assertTrue(acquired);
        assertEquals(FileListCache.State.BUILDING, cache.state());
        assertEquals("/home/foo", cache.root());
    }

    @Test
    void beginBuildIsIdempotentWhileBuilding() {
        FileListCache cache = new FileListCache();
        cache.beginBuild("/home/foo");
        boolean secondAcquire = cache.beginBuild("/home/foo");
        assertFalse(secondAcquire);
        assertEquals(FileListCache.State.BUILDING, cache.state());
    }

    @Test
    void succeedMovesToReady() {
        FileListCache cache = new FileListCache();
        cache.beginBuild("/home/foo");
        cache.succeed(List.of("/home/foo/a", "/home/foo/b"), FileListCache.Tool.FIND, false);

        assertEquals(FileListCache.State.READY, cache.state());
        assertEquals(List.of("/home/foo/a", "/home/foo/b"), cache.paths());
        assertEquals(FileListCache.Tool.FIND, cache.tool());
        assertFalse(cache.truncated());
    }

    @Test
    void succeedTruncatesAt200k() {
        FileListCache cache = new FileListCache();
        cache.beginBuild("/big");
        List<String> big = new ArrayList<>(210_000);
        for (int i = 0; i < 210_000; i++) big.add("/big/" + i);

        cache.succeed(big, FileListCache.Tool.FIND, true);

        assertEquals(FileListCache.State.READY, cache.state());
        assertEquals(200_000, cache.paths().size());
        assertTrue(cache.truncated());
    }

    @Test
    void failMovesToFailedWithMessage() {
        FileListCache cache = new FileListCache();
        cache.beginBuild("/home/foo");
        cache.fail("rg exit 2: permission denied");

        assertEquals(FileListCache.State.FAILED, cache.state());
        assertEquals("rg exit 2: permission denied", cache.failureMessage());
        assertNull(cache.paths());
    }

    @Test
    void invalidateResetsToEmpty() {
        FileListCache cache = new FileListCache();
        cache.beginBuild("/home/foo");
        cache.succeed(List.of("a"), FileListCache.Tool.RG, false);

        cache.invalidate();

        assertEquals(FileListCache.State.EMPTY, cache.state());
        assertNull(cache.paths());
        assertNull(cache.root());
        assertFalse(cache.truncated());
    }

    @Test
    void invalidateDuringBuildIsNoop() {
        FileListCache cache = new FileListCache();
        cache.beginBuild("/home/foo");
        cache.invalidate();
        assertEquals(FileListCache.State.BUILDING, cache.state());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner`
Expected: FAIL — `FileListCache` class does not exist.

- [ ] **Step 3: Write the minimal implementation**

Create `plugins/sftp/src/com/termlab/sftp/search/FileListCache.java`:

```java
package com.termlab.sftp.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * In-memory file-listing cache for one SFTP pane (local or remote).
 * Owns only state — the search plugin's {@code FileLister} does the
 * actual work of filling it.
 *
 * <p>State machine:
 * {@code EMPTY → BUILDING → (READY | FAILED) → (via invalidate) → EMPTY}.
 * {@link #invalidate()} during {@code BUILDING} is a no-op so an
 * in-flight build isn't dropped on a transient UI event.
 */
public final class FileListCache {

    public enum State { EMPTY, BUILDING, READY, FAILED }
    public enum Tool { RG, FD, FIND, WALK }

    static final int MAX_PATHS = 200_000;

    private final Object lock = new Object();
    private volatile State state = State.EMPTY;
    private volatile @Nullable String root;
    private volatile @Nullable Tool tool;
    private volatile @Nullable List<String> paths;
    private volatile boolean truncated;
    private volatile @Nullable String failureMessage;

    public @NotNull State state() { return state; }
    public @Nullable String root() { return root; }
    public @Nullable Tool tool() { return tool; }
    public @Nullable List<String> paths() { return paths; }
    public boolean truncated() { return truncated; }
    public @Nullable String failureMessage() { return failureMessage; }

    /**
     * Atomically transitions {@code EMPTY → BUILDING} and records the
     * root. Returns {@code true} if the caller acquired the build slot;
     * {@code false} if the cache was already building, ready, or failed.
     */
    public boolean beginBuild(@NotNull String rootPath) {
        synchronized (lock) {
            if (state != State.EMPTY) return false;
            state = State.BUILDING;
            root = rootPath;
            return true;
        }
    }

    public void succeed(@NotNull List<String> discovered, @NotNull Tool usedTool, boolean wasTruncated) {
        synchronized (lock) {
            List<String> capped = discovered.size() > MAX_PATHS
                ? List.copyOf(discovered.subList(0, MAX_PATHS))
                : List.copyOf(discovered);
            paths = capped;
            tool = usedTool;
            truncated = wasTruncated || discovered.size() > MAX_PATHS;
            failureMessage = null;
            state = State.READY;
        }
    }

    public void fail(@NotNull String message) {
        synchronized (lock) {
            failureMessage = message;
            paths = null;
            tool = null;
            truncated = false;
            state = State.FAILED;
        }
    }

    /**
     * Resets the cache back to {@code EMPTY}. No-op if currently building.
     */
    public void invalidate() {
        synchronized (lock) {
            if (state == State.BUILDING) return;
            state = State.EMPTY;
            root = null;
            tool = null;
            paths = null;
            truncated = false;
            failureMessage = null;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner`
Expected: PASS — all 8 tests green.

- [ ] **Step 5: Commit**

```bash
git add plugins/sftp/src/com/termlab/sftp/search/FileListCache.java \
        plugins/sftp/test/com/termlab/sftp/search/FileListCacheTest.java
git commit -m "feat(sftp): FileListCache state machine for file search

Plain in-memory state holder with EMPTY → BUILDING → READY/FAILED
transitions, 200k truncation cap, and invalidate-no-op-during-build
semantics. Consumed by the incoming search plugin."
```

---

## Task 3: Wire `FileListCache` into SFTP panes

**Files:**
- Modify: `plugins/sftp/src/com/termlab/sftp/toolwindow/LocalFilePane.java`
- Modify: `plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java`

Add a cache field per pane + public accessor + invalidation on navigation / reconnect / disconnect. No tests — the existing `directoryListeners` / `connectionStateListeners` lists are already platform-touched enough that manual smoke covers this, and there are no corner cases beyond "did we call `invalidate()` at the right points."

- [ ] **Step 1: Add the import + field + accessor in `LocalFilePane.java`**

Add to imports (after the existing SFTP spi import around line 10):
```java
import com.termlab.sftp.search.FileListCache;
```

Add a field near the existing `currentDir` field (around line 60):
```java
    private final FileListCache fileListCache = new FileListCache();
```

Add a public accessor near `currentDirectory()` (around line 401):
```java
    public @NotNull FileListCache fileListCache() {
        return fileListCache;
    }
```

- [ ] **Step 2: Invalidate the cache on cwd change in `LocalFilePane.java`**

Locate the `reload` method (around line 382-392) — specifically the success path that updates `currentDir`. After `currentDir = dir;` and before the listener loop, add:
```java
                fileListCache.invalidate();
```

The block becomes (line 385-391 in the existing code):
```java
                currentDir = dir;
                fileListCache.invalidate();
                pathField.setText(dir.toString());

                for (Runnable listener : directoryListeners) listener.run();
```

- [ ] **Step 3: Add the import + field + accessor + invalidations in `RemoteFilePane.java`**

Add to imports (after the existing SFTP spi import around line 12):
```java
import com.termlab.sftp.search.FileListCache;
```

Add a field alongside `activeSession` / `currentHost` (around line 73):
```java
    private final FileListCache fileListCache = new FileListCache();
```

Add a public accessor near `currentRemotePath()` (around line 412):
```java
    public @NotNull FileListCache fileListCache() {
        return fileListCache;
    }
```

Invalidate on remote navigation — inside the navigate-success path where `currentRemotePath = path;` is assigned (around line 337):
```java
                currentRemotePath = path;
                fileListCache.invalidate();
```

Invalidate on disconnect — locate the existing disconnect logic that sets `currentRemotePath = null;` (around line 352). Add:
```java
        currentRemotePath = null;
        fileListCache.invalidate();
```

Invalidate on fresh connect — locate the `connect` method that assigns `activeSession` after a successful open (search for `activeSession =` assignment in the success callback). Add `fileListCache.invalidate();` immediately after the assignment.

- [ ] **Step 4: Build to verify nothing broke**

Run: `make termlab-build`
Expected: clean build.

- [ ] **Step 5: Run SFTP tests to confirm pane logic still passes**

Run: `bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner`
Expected: all existing tests still green (the `FileListCacheTest` added in Task 2 is part of this run).

- [ ] **Step 6: Commit**

```bash
git add plugins/sftp/src/com/termlab/sftp/toolwindow/LocalFilePane.java \
        plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java
git commit -m "feat(sftp): expose per-pane FileListCache with lifecycle hooks

Local pane: invalidate on reload(). Remote pane: invalidate on
navigateRemote, connect, and disconnect. The cache is currently
unread — the search plugin consumes it in a later commit."
```

---

## Task 4: `FileSearchFilter` — state + chip glob expansion (TDD)

**Files:**
- Create: `plugins/search/src/com/termlab/search/FileSearchFilter.java`
- Create: `plugins/search/test/com/termlab/search/FileSearchFilterTest.java`

This task covers the default chip state, chip-to-glob expansion, and the "is this non-default?" check used for the filter-active dot. Custom globs, regex validation, and tool-flag emission come in Tasks 5 and 6.

- [ ] **Step 1: Write the failing test**

Create `plugins/search/test/com/termlab/search/FileSearchFilterTest.java`:

```java
package com.termlab.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSearchFilterTest {

    @Test
    void defaultStateHasAllChipsExcluded() {
        FileSearchFilter.State s = new FileSearchFilter.State();
        assertTrue(s.excludeGit);
        assertTrue(s.excludeNodeModules);
        assertTrue(s.excludeIdea);
        assertTrue(s.excludeBuild);
        assertTrue(s.excludeCache);
        assertTrue(s.excludeDsStore);
        assertEquals(List.of(), s.customExcludes);
        assertEquals("", s.excludeRegex);
    }

    @Test
    void isDefaultReturnsTrueForFreshState() {
        FileSearchFilter filter = new FileSearchFilter();
        assertTrue(filter.isDefault());
    }

    @Test
    void isDefaultReturnsFalseWhenChipToggled() {
        FileSearchFilter filter = new FileSearchFilter();
        filter.getState().excludeGit = false;
        assertFalse(filter.isDefault());
    }

    @Test
    void isDefaultReturnsFalseWhenCustomGlobAdded() {
        FileSearchFilter filter = new FileSearchFilter();
        filter.getState().customExcludes = List.of("vendor");
        assertFalse(filter.isDefault());
    }

    @Test
    void isDefaultReturnsFalseWhenRegexSet() {
        FileSearchFilter filter = new FileSearchFilter();
        filter.getState().excludeRegex = ".*\\.log$";
        assertFalse(filter.isDefault());
    }

    @Test
    void activeGlobsUnionsChipsAndCustom() {
        FileSearchFilter filter = new FileSearchFilter();
        filter.getState().customExcludes = List.of("vendor", "*.tmp");

        Set<String> globs = filter.activeGlobs();

        assertTrue(globs.contains(".git"));
        assertTrue(globs.contains("node_modules"));
        assertTrue(globs.contains(".idea"));
        assertTrue(globs.contains(".vscode"));
        assertTrue(globs.contains("build"));
        assertTrue(globs.contains("dist"));
        assertTrue(globs.contains("target"));
        assertTrue(globs.contains("out"));
        assertTrue(globs.contains(".cache"));
        assertTrue(globs.contains("__pycache__"));
        assertTrue(globs.contains(".gradle"));
        assertTrue(globs.contains(".DS_Store"));
        assertTrue(globs.contains("Thumbs.db"));
        assertTrue(globs.contains("vendor"));
        assertTrue(globs.contains("*.tmp"));
    }

    @Test
    void activeGlobsOmitsDisabledChipsOnly() {
        FileSearchFilter filter = new FileSearchFilter();
        filter.getState().excludeGit = false;
        filter.getState().excludeBuild = false;

        Set<String> globs = filter.activeGlobs();

        assertFalse(globs.contains(".git"));
        assertFalse(globs.contains("build"));
        assertFalse(globs.contains("dist"));
        assertFalse(globs.contains("target"));
        assertFalse(globs.contains("out"));
        // Other chips remain
        assertTrue(globs.contains("node_modules"));
        assertTrue(globs.contains(".idea"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash bazel.cmd run //termlab/plugins/search:search_test_runner`
Expected: FAIL — `FileSearchFilter` class does not exist.

- [ ] **Step 3: Write the minimal implementation**

Create `plugins/search/src/com/termlab/search/FileSearchFilter.java`:

```java
package com.termlab.search;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Application-level exclusion settings for the file search plugin.
 * Holds the six default toggleable chips, a user-editable custom-glob
 * list, a single optional regex, and per-host balloon-suppression flags.
 *
 * <p>Values are user-visible — see the filter popup in
 * {@link FileSearchContributor}. The chip-to-glob mapping is baked
 * into {@link #globsFor(Chip)} and is not user-editable.
 */
@State(
    name = "TermLabFileSearchFilter",
    storages = @Storage("termlab-file-search.xml")
)
public final class FileSearchFilter implements PersistentStateComponent<FileSearchFilter.State> {

    public enum Chip { GIT, NODE_MODULES, IDEA, BUILD, CACHE, DS_STORE }

    public static final class State {
        public boolean excludeGit = true;
        public boolean excludeNodeModules = true;
        public boolean excludeIdea = true;
        public boolean excludeBuild = true;
        public boolean excludeCache = true;
        public boolean excludeDsStore = true;
        public List<String> customExcludes = new ArrayList<>();
        public String excludeRegex = "";
        public Set<String> dismissedRipgrepHints = new HashSet<>();
    }

    private State state = new State();

    public static @NotNull FileSearchFilter getInstance() {
        return ApplicationManager.getApplication().getService(FileSearchFilter.class);
    }

    @Override public @NotNull State getState() { return state; }
    @Override public void loadState(@NotNull State loaded) { this.state = loaded; }

    /** True when the current state matches factory defaults — used for the filter-active dot. */
    public boolean isDefault() {
        return state.excludeGit
            && state.excludeNodeModules
            && state.excludeIdea
            && state.excludeBuild
            && state.excludeCache
            && state.excludeDsStore
            && (state.customExcludes == null || state.customExcludes.isEmpty())
            && (state.excludeRegex == null || state.excludeRegex.isEmpty());
    }

    /** Globs to exclude at listing time: union of enabled chip globs + custom globs. */
    public @NotNull Set<String> activeGlobs() {
        Set<String> out = new LinkedHashSet<>();
        if (state.excludeGit) out.addAll(globsFor(Chip.GIT));
        if (state.excludeNodeModules) out.addAll(globsFor(Chip.NODE_MODULES));
        if (state.excludeIdea) out.addAll(globsFor(Chip.IDEA));
        if (state.excludeBuild) out.addAll(globsFor(Chip.BUILD));
        if (state.excludeCache) out.addAll(globsFor(Chip.CACHE));
        if (state.excludeDsStore) out.addAll(globsFor(Chip.DS_STORE));
        if (state.customExcludes != null) out.addAll(state.customExcludes);
        return out;
    }

    public static @NotNull List<String> globsFor(@NotNull Chip chip) {
        return switch (chip) {
            case GIT -> List.of(".git", ".svn", ".hg");
            case NODE_MODULES -> List.of("node_modules");
            case IDEA -> List.of(".idea", ".vscode");
            case BUILD -> List.of("build", "dist", "target", "out");
            case CACHE -> List.of(".cache", "__pycache__", ".gradle");
            case DS_STORE -> List.of(".DS_Store", "Thumbs.db");
        };
    }

    public boolean isRipgrepHintDismissed(@NotNull String hostIdOrLocal) {
        return state.dismissedRipgrepHints != null
            && state.dismissedRipgrepHints.contains(hostIdOrLocal);
    }

    public void dismissRipgrepHint(@NotNull String hostIdOrLocal) {
        if (state.dismissedRipgrepHints == null) {
            state.dismissedRipgrepHints = new HashSet<>();
        }
        state.dismissedRipgrepHints.add(hostIdOrLocal);
    }

    /** Used by tests; not to be called from production code. */
    @Nullable
    public static FileSearchFilter forTestingOrNull() {
        return ApplicationManager.getApplication() == null
            ? null
            : ApplicationManager.getApplication().getService(FileSearchFilter.class);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash bazel.cmd run //termlab/plugins/search:search_test_runner`
Expected: PASS — all 7 tests green.

- [ ] **Step 5: Commit**

```bash
git add plugins/search/src/com/termlab/search/FileSearchFilter.java \
        plugins/search/test/com/termlab/search/FileSearchFilterTest.java
git commit -m "feat(search): FileSearchFilter chip state + glob union

Six toggleable chips (git, node_modules, idea, build, cache, ds_store)
with baked-in glob sets, an isDefault() check for the filter-active
dot, and activeGlobs() that unions chip-expansions with user-added
custom globs. Custom regex + tool-specific flag emission land in
subsequent tasks."
```

---

## Task 5: `FileSearchFilter` — regex validation (TDD)

**Files:**
- Modify: `plugins/search/src/com/termlab/search/FileSearchFilter.java`
- Modify: `plugins/search/test/com/termlab/search/FileSearchFilterTest.java`

Regex is applied JVM-side on cached paths. The filter should hand back a compiled `Pattern` (or `null` if the regex field is blank) or a `RegexResult` that carries a compile error for the UI to show.

- [ ] **Step 1: Add failing tests to `FileSearchFilterTest.java`**

Append inside the test class:

```java
    @Test
    void compiledRegexEmptyStringReturnsNullPattern() {
        FileSearchFilter filter = new FileSearchFilter();
        filter.getState().excludeRegex = "";
        FileSearchFilter.RegexResult result = filter.compileRegex();
        assertEquals(null, result.pattern());
        assertEquals(null, result.error());
    }

    @Test
    void compiledRegexValidReturnsPattern() {
        FileSearchFilter filter = new FileSearchFilter();
        filter.getState().excludeRegex = ".*\\.log$";
        FileSearchFilter.RegexResult result = filter.compileRegex();
        assertTrue(result.pattern() != null);
        assertEquals(null, result.error());
        assertTrue(result.pattern().matcher("app.log").matches());
    }

    @Test
    void compiledRegexInvalidReturnsError() {
        FileSearchFilter filter = new FileSearchFilter();
        filter.getState().excludeRegex = "[unterminated";
        FileSearchFilter.RegexResult result = filter.compileRegex();
        assertEquals(null, result.pattern());
        assertTrue(result.error() != null);
        assertTrue(result.error().contains("["));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash bazel.cmd run //termlab/plugins/search:search_test_runner`
Expected: FAIL — `compileRegex` / `RegexResult` don't exist.

- [ ] **Step 3: Extend `FileSearchFilter.java`**

Add the `RegexResult` record as a nested type and the `compileRegex` method. Place the record above `globsFor` and the method below `isDefault`:

```java
    public record RegexResult(@Nullable java.util.regex.Pattern pattern, @Nullable String error) {}

    /**
     * Compile the user's regex. Empty regex returns {@code (null, null)}.
     * Invalid regex returns {@code (null, errorMessage)} so the filter
     * popup can render the compile error inline.
     */
    public @NotNull RegexResult compileRegex() {
        String src = state.excludeRegex == null ? "" : state.excludeRegex;
        if (src.isEmpty()) return new RegexResult(null, null);
        try {
            return new RegexResult(java.util.regex.Pattern.compile(src), null);
        } catch (java.util.regex.PatternSyntaxException e) {
            return new RegexResult(null, e.getMessage());
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash bazel.cmd run //termlab/plugins/search:search_test_runner`
Expected: PASS — 10 tests green.

- [ ] **Step 5: Commit**

```bash
git add plugins/search/src/com/termlab/search/FileSearchFilter.java \
        plugins/search/test/com/termlab/search/FileSearchFilterTest.java
git commit -m "feat(search): FileSearchFilter compileRegex with PatternSyntaxException handling

Empty regex returns (null, null); valid returns a compiled Pattern;
invalid returns the compile error so the filter popup can surface
it inline."
```

---

## Task 6: `FileSearchFilter` — tool-specific exclusion flags (TDD)

**Files:**
- Modify: `plugins/search/src/com/termlab/search/FileSearchFilter.java`
- Modify: `plugins/search/test/com/termlab/search/FileSearchFilterTest.java`

Each tool takes exclusions in a different flag shape. `FileSearchFilter.toListCommandFlags(Tool)` returns the arg list the tool needs, separate from the command skeleton (which `FileLister` builds).

- [ ] **Step 1: Add failing tests**

Add to `FileSearchFilterTest.java`:

```java
    @Test
    void flagsForRgWrapsEachGlobWithNotPrefix() {
        FileSearchFilter filter = new FileSearchFilter();
        filter.getState().customExcludes = List.of("vendor");
        filter.getState().excludeGit = false;
        filter.getState().excludeNodeModules = false;
        filter.getState().excludeIdea = false;
        filter.getState().excludeBuild = false;
        filter.getState().excludeCache = false;
        filter.getState().excludeDsStore = false;

        List<String> flags = filter.toListCommandFlags(FileListCache.Tool.RG);

        assertEquals(List.of("-g", "!vendor"), flags);
    }

    @Test
    void flagsForFdUsesDashE() {
        FileSearchFilter filter = new FileSearchFilter();
        filter.getState().customExcludes = List.of("vendor");
        filter.getState().excludeGit = false;
        filter.getState().excludeNodeModules = false;
        filter.getState().excludeIdea = false;
        filter.getState().excludeBuild = false;
        filter.getState().excludeCache = false;
        filter.getState().excludeDsStore = false;

        List<String> flags = filter.toListCommandFlags(FileListCache.Tool.FD);

        assertEquals(List.of("-E", "vendor"), flags);
    }

    @Test
    void flagsForFindUsesNotPath() {
        FileSearchFilter filter = new FileSearchFilter();
        filter.getState().customExcludes = List.of("vendor");
        filter.getState().excludeGit = false;
        filter.getState().excludeNodeModules = false;
        filter.getState().excludeIdea = false;
        filter.getState().excludeBuild = false;
        filter.getState().excludeCache = false;
        filter.getState().excludeDsStore = false;

        List<String> flags = filter.toListCommandFlags(FileListCache.Tool.FIND);

        assertEquals(List.of("-not", "-path", "*/vendor/*"), flags);
    }

    @Test
    void flagsForWalkIsEmpty() {
        FileSearchFilter filter = new FileSearchFilter();
        assertEquals(List.of(), filter.toListCommandFlags(FileListCache.Tool.WALK));
    }

    @Test
    void flagsIncludeAllEnabledChipsInStableOrder() {
        FileSearchFilter filter = new FileSearchFilter();
        // All chips on (default). Just node_modules is a single-glob chip;
        // ds_store expands to two; verify both appear and chips appear
        // in declaration order (git first).

        List<String> flags = filter.toListCommandFlags(FileListCache.Tool.RG);

        // First three entries: -g !.git, -g !.svn, -g !.hg
        assertEquals("-g", flags.get(0));
        assertEquals("!.git", flags.get(1));
        assertEquals("-g", flags.get(2));
        assertEquals("!.svn", flags.get(3));
    }
```

Import addition at the top of the test file:
```java
import com.termlab.sftp.search.FileListCache;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash bazel.cmd run //termlab/plugins/search:search_test_runner`
Expected: FAIL — `toListCommandFlags` doesn't exist.

- [ ] **Step 3: Add the method**

Import `FileListCache` in `FileSearchFilter.java`:
```java
import com.termlab.sftp.search.FileListCache;
```

Add the method below `activeGlobs`:

```java
    /**
     * Build the tool-specific exclusion flags for the current filter state.
     * Regex is <em>not</em> included — it's applied JVM-side after listing
     * because regex dialects differ across tools.
     */
    public @NotNull List<String> toListCommandFlags(@NotNull FileListCache.Tool tool) {
        List<String> out = new ArrayList<>();
        for (String glob : activeGlobs()) {
            switch (tool) {
                case RG -> { out.add("-g"); out.add("!" + glob); }
                case FD -> { out.add("-E"); out.add(glob); }
                case FIND -> { out.add("-not"); out.add("-path"); out.add("*/" + glob + "/*"); }
                case WALK -> { /* applied JVM-side; no flags */ }
            }
        }
        return out;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash bazel.cmd run //termlab/plugins/search:search_test_runner`
Expected: PASS — 15 tests green.

- [ ] **Step 5: Commit**

```bash
git add plugins/search/src/com/termlab/search/FileSearchFilter.java \
        plugins/search/test/com/termlab/search/FileSearchFilterTest.java
git commit -m "feat(search): FileSearchFilter emits tool-specific exclusion flags

rg gets -g !pattern pairs; fd gets -E pattern; find gets -not -path
*/pattern/* triples; WALK gets an empty list (exclusions applied
JVM-side). Regex stays out of the flags — different regex dialects
across tools would make mapping unreliable."
```

---

## Task 7: `FileLister` — command construction (TDD)

**Files:**
- Create: `plugins/search/src/com/termlab/search/FileLister.java`
- Create: `plugins/search/test/com/termlab/search/FileListerTest.java`

Pure-function tests on the command-list output. No subprocess execution yet.

- [ ] **Step 1: Write the failing test**

Create `plugins/search/test/com/termlab/search/FileListerTest.java`:

```java
package com.termlab.search;

import com.termlab.sftp.search.FileListCache;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileListerTest {

    @Test
    void rgListingCommandIncludesFilesHiddenNoIgnoreAndRoot() {
        List<String> cmd = FileLister.buildListCommand(
            FileListCache.Tool.RG, "/home/foo", List.of());
        assertEquals(List.of("rg", "--files", "--hidden", "--no-ignore-vcs", "/home/foo"), cmd);
    }

    @Test
    void rgListingCommandAppendsExclusionFlagsBeforeRoot() {
        List<String> cmd = FileLister.buildListCommand(
            FileListCache.Tool.RG, "/home/foo", List.of("-g", "!.git"));
        assertEquals(
            List.of("rg", "--files", "--hidden", "--no-ignore-vcs", "-g", "!.git", "/home/foo"),
            cmd);
    }

    @Test
    void fdListingCommandHasTypeFileAndHidden() {
        List<String> cmd = FileLister.buildListCommand(
            FileListCache.Tool.FD, "/home/foo", List.of());
        assertEquals(
            List.of("fd", "--type", "f", "--hidden", "--no-ignore", ".", "/home/foo"),
            cmd);
    }

    @Test
    void findListingCommandPutsRootFirstBeforeTypeAndFlags() {
        List<String> cmd = FileLister.buildListCommand(
            FileListCache.Tool.FIND, "/home/foo",
            List.of("-not", "-path", "*/.git/*"));
        assertEquals(
            List.of("find", "/home/foo", "-type", "f", "-not", "-path", "*/.git/*"),
            cmd);
    }

    @Test
    void fdQueryCommandIncludesPatternBeforeRoot() {
        List<String> cmd = FileLister.buildQueryCommand(
            FileListCache.Tool.FD, "/home/foo", "main", List.of());
        assertEquals(
            List.of("fd", "--type", "f", "--hidden", "--no-ignore", "main", "/home/foo"),
            cmd);
    }

    @Test
    void findQueryCommandUsesInameGlob() {
        List<String> cmd = FileLister.buildQueryCommand(
            FileListCache.Tool.FIND, "/home/foo", "main", List.of());
        assertEquals(
            List.of("find", "/home/foo", "-type", "f", "-iname", "*main*"),
            cmd);
    }

    @Test
    void rgQueryCommandIsListingThenJvmFilter() {
        // We intentionally re-use the listing command for rg queries and
        // filter JVM-side; buildQueryCommand returns the same as
        // buildListCommand for RG.
        List<String> cmd = FileLister.buildQueryCommand(
            FileListCache.Tool.RG, "/home/foo", "main", List.of());
        assertEquals(
            List.of("rg", "--files", "--hidden", "--no-ignore-vcs", "/home/foo"),
            cmd);
    }

    @Test
    void remoteProbeCommandChecksRgThenFdThenFind() {
        String cmd = FileLister.buildRemoteProbeCommand();
        assertTrue(cmd.contains("command -v rg"));
        assertTrue(cmd.contains("command -v fd"));
        assertTrue(cmd.contains("command -v find"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash bazel.cmd run //termlab/plugins/search:search_test_runner`
Expected: FAIL — `FileLister` doesn't exist.

- [ ] **Step 3: Write the implementation**

Create `plugins/search/src/com/termlab/search/FileLister.java`:

```java
package com.termlab.search;

import com.termlab.sftp.search.FileListCache;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds and runs file-listing commands. Only the command-construction
 * portion is covered by unit tests; the subprocess + SSH exec paths
 * are added in subsequent tasks and exercised via integration + manual
 * smoke tests.
 */
public final class FileLister {

    private FileLister() {}

    /** Full-listing command for the given tool. Exclusions come from FileSearchFilter. */
    public static @NotNull List<String> buildListCommand(
        @NotNull FileListCache.Tool tool,
        @NotNull String root,
        @NotNull List<String> exclusionFlags
    ) {
        List<String> out = new ArrayList<>();
        switch (tool) {
            case RG -> {
                out.add("rg");
                out.add("--files");
                out.add("--hidden");
                out.add("--no-ignore-vcs");
                out.addAll(exclusionFlags);
                out.add(root);
            }
            case FD -> {
                out.add("fd");
                out.add("--type"); out.add("f");
                out.add("--hidden");
                out.add("--no-ignore");
                out.addAll(exclusionFlags);
                out.add(".");
                out.add(root);
            }
            case FIND -> {
                out.add("find");
                out.add(root);
                out.add("-type"); out.add("f");
                out.addAll(exclusionFlags);
            }
            case WALK -> {
                // No command — caller uses Files.walk instead.
            }
        }
        return out;
    }

    /** Live-query command: given a substring, return matching files. */
    public static @NotNull List<String> buildQueryCommand(
        @NotNull FileListCache.Tool tool,
        @NotNull String root,
        @NotNull String substring,
        @NotNull List<String> exclusionFlags
    ) {
        List<String> out = new ArrayList<>();
        switch (tool) {
            case RG -> {
                // rg --files is already a listing; we filter its output
                // JVM-side rather than adding a second rg process.
                return buildListCommand(tool, root, exclusionFlags);
            }
            case FD -> {
                out.add("fd");
                out.add("--type"); out.add("f");
                out.add("--hidden");
                out.add("--no-ignore");
                out.addAll(exclusionFlags);
                out.add(substring);
                out.add(root);
            }
            case FIND -> {
                out.add("find");
                out.add(root);
                out.add("-type"); out.add("f");
                out.addAll(exclusionFlags);
                out.add("-iname");
                out.add("*" + substring + "*");
            }
            case WALK -> {
                // Filtered in JVM; caller uses buildListCommand (WALK returns empty).
                return List.of();
            }
        }
        return out;
    }

    /** Single-line shell probe that writes the first tool that exists. */
    public static @NotNull String buildRemoteProbeCommand() {
        return "command -v rg 2>/dev/null"
            + " || command -v fd 2>/dev/null"
            + " || command -v find 2>/dev/null"
            + " || true";
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash bazel.cmd run //termlab/plugins/search:search_test_runner`
Expected: PASS — 8 tests green.

- [ ] **Step 5: Commit**

```bash
git add plugins/search/src/com/termlab/search/FileLister.java \
        plugins/search/test/com/termlab/search/FileListerTest.java
git commit -m "feat(search): FileLister command construction for rg/fd/find

Builds listing + query command arg-lists per tool and a single-line
remote-probe command (command -v rg || fd || find || true). Pure
functions — subprocess + SSH exec live in later tasks."
```

---

## Task 8: `FileLister` — local tool probing (TDD)

**Files:**
- Modify: `plugins/search/src/com/termlab/search/FileLister.java`
- Modify: `plugins/search/test/com/termlab/search/FileListerTest.java`

Local probing order: bundled JB ripgrep via `PathManager.getBinPath()` → `rg` on PATH → `fd` on PATH → `find` (POSIX) or `WALK` (Windows). The probe function takes two injected lookups (`Path binDir` and `Predicate<String> onPath`) so it's testable without touching real PATH.

- [ ] **Step 1: Add failing tests**

Append to `FileListerTest.java`:

```java
    @Test
    void localProbePrefersBundledRipgrep() {
        java.nio.file.Path binDir = java.nio.file.Paths.get("/opt/idea/bin");
        java.util.function.Predicate<java.nio.file.Path> exists = p ->
            p.toString().equals("/opt/idea/bin/rg")
            || p.toString().equals("/opt/idea/bin/rg.exe");

        FileLister.LocalProbeResult r = FileLister.probeLocal(
            binDir, exists, tool -> false, false);

        assertEquals(FileListCache.Tool.RG, r.tool());
        assertEquals("/opt/idea/bin/rg", r.executable().toString());
    }

    @Test
    void localProbePrefersBundledRipgrepExeOnWindows() {
        java.nio.file.Path binDir = java.nio.file.Paths.get("C:\\idea\\bin");
        java.util.function.Predicate<java.nio.file.Path> exists = p ->
            p.toString().endsWith("rg.exe");

        FileLister.LocalProbeResult r = FileLister.probeLocal(
            binDir, exists, tool -> false, /* windows */ true);

        assertEquals(FileListCache.Tool.RG, r.tool());
        assertTrue(r.executable().toString().endsWith("rg.exe"));
    }

    @Test
    void localProbeFallsThroughToPathRgWhenBundledMissing() {
        java.nio.file.Path binDir = java.nio.file.Paths.get("/opt/idea/bin");
        java.util.function.Predicate<java.nio.file.Path> bundledExists = p -> false;
        java.util.function.Predicate<String> onPath = t -> t.equals("rg");

        FileLister.LocalProbeResult r = FileLister.probeLocal(
            binDir, bundledExists, onPath, false);

        assertEquals(FileListCache.Tool.RG, r.tool());
        assertEquals("rg", r.executable().toString());
    }

    @Test
    void localProbeFallsThroughToFdThenFind() {
        java.nio.file.Path binDir = java.nio.file.Paths.get("/opt/idea/bin");
        java.util.function.Predicate<java.nio.file.Path> bundledExists = p -> false;

        FileLister.LocalProbeResult fdResult = FileLister.probeLocal(
            binDir, bundledExists, t -> t.equals("fd"), false);
        assertEquals(FileListCache.Tool.FD, fdResult.tool());

        FileLister.LocalProbeResult findResult = FileLister.probeLocal(
            binDir, bundledExists, t -> t.equals("find"), false);
        assertEquals(FileListCache.Tool.FIND, findResult.tool());
    }

    @Test
    void localProbeOnWindowsFallsThroughToWalkWhenToolsMissing() {
        java.nio.file.Path binDir = java.nio.file.Paths.get("C:\\idea\\bin");
        FileLister.LocalProbeResult r = FileLister.probeLocal(
            binDir, p -> false, t -> false, true);
        assertEquals(FileListCache.Tool.WALK, r.tool());
    }

    @Test
    void localProbeOnPosixFallsThroughToFindWhenToolsMissing() {
        java.nio.file.Path binDir = java.nio.file.Paths.get("/opt/idea/bin");
        FileLister.LocalProbeResult r = FileLister.probeLocal(
            binDir, p -> false, t -> false, false);
        assertEquals(FileListCache.Tool.FIND, r.tool());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash bazel.cmd run //termlab/plugins/search:search_test_runner`
Expected: FAIL — `probeLocal` doesn't exist.

- [ ] **Step 3: Extend `FileLister.java`**

Add this class below `buildRemoteProbeCommand`:

```java
    public record LocalProbeResult(@NotNull FileListCache.Tool tool,
                                   @NotNull java.nio.file.Path executable) {}

    /**
     * Probe for the best-available local file-listing tool.
     *
     * <p>Order:
     * <ol>
     *   <li>Bundled JetBrains ripgrep under {@code binDir/rg} (or {@code rg.exe} on Windows).</li>
     *   <li>{@code rg} on {@code PATH}.</li>
     *   <li>{@code fd} on {@code PATH}.</li>
     *   <li>{@code find} on POSIX; {@link FileListCache.Tool#WALK} on Windows.</li>
     * </ol>
     *
     * @param binDir        the IntelliJ bin directory — {@link com.intellij.openapi.application.PathManager#getBinPath()}
     * @param bundledExists returns true if the given bundled path exists (injectable for tests)
     * @param onPath        returns true if the given command is on {@code PATH}
     * @param windows       whether the local OS is Windows — controls probe order + executable suffix
     */
    public static @NotNull LocalProbeResult probeLocal(
        @NotNull java.nio.file.Path binDir,
        @NotNull java.util.function.Predicate<java.nio.file.Path> bundledExists,
        @NotNull java.util.function.Predicate<String> onPath,
        boolean windows
    ) {
        String rgName = windows ? "rg.exe" : "rg";
        java.nio.file.Path bundledRg = binDir.resolve(rgName);
        if (bundledExists.test(bundledRg)) {
            return new LocalProbeResult(FileListCache.Tool.RG, bundledRg);
        }
        if (onPath.test("rg")) {
            return new LocalProbeResult(FileListCache.Tool.RG, java.nio.file.Paths.get("rg"));
        }
        if (onPath.test("fd")) {
            return new LocalProbeResult(FileListCache.Tool.FD, java.nio.file.Paths.get("fd"));
        }
        if (!windows && onPath.test("find")) {
            return new LocalProbeResult(FileListCache.Tool.FIND, java.nio.file.Paths.get("find"));
        }
        if (!windows) {
            // POSIX find is part of the OS; no separate probe needed.
            return new LocalProbeResult(FileListCache.Tool.FIND, java.nio.file.Paths.get("find"));
        }
        return new LocalProbeResult(FileListCache.Tool.WALK, java.nio.file.Paths.get(""));
    }

    /**
     * Convenience wrapper that sources {@code binDir} from {@link com.intellij.openapi.application.PathManager}
     * and tests {@code PATH} via {@link #commandOnPath(String)}.
     */
    public static @NotNull LocalProbeResult probeLocalDefault() {
        java.nio.file.Path binDir = java.nio.file.Paths.get(
            com.intellij.openapi.application.PathManager.getBinPath());
        boolean windows = com.intellij.openapi.util.SystemInfo.isWindows;
        return probeLocal(binDir,
            p -> java.nio.file.Files.isExecutable(p),
            FileLister::commandOnPath,
            windows);
    }

    /** True if {@code name} is a resolvable executable on the current PATH. */
    public static boolean commandOnPath(@NotNull String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) return false;
        String pathSep = System.getProperty("path.separator", ":");
        boolean windows = com.intellij.openapi.util.SystemInfo.isWindows;
        String[] exts = windows ? new String[]{".exe", ".bat", ".cmd", ""} : new String[]{""};
        for (String dir : path.split(java.util.regex.Pattern.quote(pathSep))) {
            for (String ext : exts) {
                java.nio.file.Path candidate = java.nio.file.Paths.get(dir, name + ext);
                if (java.nio.file.Files.isExecutable(candidate)) return true;
            }
        }
        return false;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash bazel.cmd run //termlab/plugins/search:search_test_runner`
Expected: PASS — 14 tests green.

- [ ] **Step 5: Commit**

```bash
git add plugins/search/src/com/termlab/search/FileLister.java \
        plugins/search/test/com/termlab/search/FileListerTest.java
git commit -m "feat(search): FileLister local tool probing with dependency injection

probeLocal takes injected binDir + bundledExists + onPath so the
probing order (bundled rg → PATH rg → fd → find/WALK) is unit
testable across mac / linux / windows without touching the real
filesystem. probeLocalDefault wires to PathManager + SystemInfo
for production callers."
```

---

## Task 9: `FileLister` — local execution integration test

**Files:**
- Modify: `plugins/search/src/com/termlab/search/FileLister.java`
- Modify: `plugins/search/test/com/termlab/search/FileListerTest.java`

Add `runLocalListing(LocalProbeResult, String root, List<String> flags, ProgressIndicator)` — runs the subprocess, streams stdout, returns the path list. Covered by an integration test against a temp dir with a known shape. Test gates on whether `find` is available (it is on every CI *nix).

- [ ] **Step 1: Write the failing integration test**

Append to `FileListerTest.java`:

```java
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void runLocalListingWithFindEnumeratesTempTree(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws java.io.IOException {
        java.nio.file.Files.createDirectories(tmp.resolve("a/b"));
        java.nio.file.Files.createDirectories(tmp.resolve(".git/objects"));
        java.nio.file.Files.createDirectories(tmp.resolve("node_modules/pkg"));
        java.nio.file.Files.writeString(tmp.resolve("a/b/keep.txt"), "x");
        java.nio.file.Files.writeString(tmp.resolve(".git/objects/junk"), "x");
        java.nio.file.Files.writeString(tmp.resolve("node_modules/pkg/index.js"), "x");
        java.nio.file.Files.writeString(tmp.resolve("README.md"), "x");

        FileLister.LocalProbeResult probe = new FileLister.LocalProbeResult(
            FileListCache.Tool.FIND, java.nio.file.Paths.get("find"));
        List<String> flags = List.of(
            "-not", "-path", "*/.git/*",
            "-not", "-path", "*/node_modules/*");

        FileLister.ListingResult r = FileLister.runLocalListing(
            probe, tmp.toString(), flags, null);

        assertTrue(r.paths().stream().anyMatch(p -> p.endsWith("README.md")));
        assertTrue(r.paths().stream().anyMatch(p -> p.endsWith("keep.txt")));
        assertTrue(r.paths().stream().noneMatch(p -> p.contains("/.git/")));
        assertTrue(r.paths().stream().noneMatch(p -> p.contains("/node_modules/")));
        assertTrue(!r.truncated());
    }

    @Test
    void runLocalListingWithWalkEnumeratesTempTree(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws java.io.IOException {
        java.nio.file.Files.createDirectories(tmp.resolve("a/b"));
        java.nio.file.Files.writeString(tmp.resolve("a/b/keep.txt"), "x");
        java.nio.file.Files.writeString(tmp.resolve("top.txt"), "x");

        FileLister.LocalProbeResult probe = new FileLister.LocalProbeResult(
            FileListCache.Tool.WALK, java.nio.file.Paths.get(""));

        FileLister.ListingResult r = FileLister.runLocalListing(
            probe, tmp.toString(), List.of(), null);

        assertTrue(r.paths().stream().anyMatch(p -> p.endsWith("top.txt")));
        assertTrue(r.paths().stream().anyMatch(p -> p.endsWith("keep.txt")));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash bazel.cmd run //termlab/plugins/search:search_test_runner`
Expected: FAIL — `runLocalListing` / `ListingResult` don't exist.

- [ ] **Step 3: Add the execution logic**

Append to `FileLister.java`:

```java
    public record ListingResult(@NotNull List<String> paths, boolean truncated) {}

    public static @NotNull ListingResult runLocalListing(
        @NotNull LocalProbeResult probe,
        @NotNull String root,
        @NotNull List<String> exclusionFlags,
        @org.jetbrains.annotations.Nullable com.intellij.openapi.progress.ProgressIndicator indicator
    ) throws java.io.IOException {
        if (probe.tool() == FileListCache.Tool.WALK) {
            return walkRoot(root, indicator);
        }
        List<String> cmd = buildListCommand(probe.tool(), root, exclusionFlags);
        // Replace the bare "rg" / "fd" / "find" arg with the resolved executable path.
        cmd.set(0, probe.executable().toString());
        return execAndCollect(cmd, indicator);
    }

    private static @NotNull ListingResult walkRoot(
        @NotNull String root,
        @org.jetbrains.annotations.Nullable com.intellij.openapi.progress.ProgressIndicator indicator
    ) throws java.io.IOException {
        java.nio.file.Path rootPath = java.nio.file.Paths.get(root);
        List<String> out = new ArrayList<>();
        boolean truncated = false;
        try (var stream = java.nio.file.Files.walk(rootPath)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                if (indicator != null && indicator.isCanceled()) break;
                java.nio.file.Path p = it.next();
                if (java.nio.file.Files.isRegularFile(p)) {
                    if (out.size() >= FileListCache.MAX_PATHS) { truncated = true; break; }
                    out.add(p.toString());
                }
            }
        }
        return new ListingResult(out, truncated);
    }

    private static @NotNull ListingResult execAndCollect(
        @NotNull List<String> cmd,
        @org.jetbrains.annotations.Nullable com.intellij.openapi.progress.ProgressIndicator indicator
    ) throws java.io.IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        List<String> out = new ArrayList<>();
        boolean truncated = false;
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (indicator != null && indicator.isCanceled()) {
                    p.destroy();
                    break;
                }
                if (out.size() >= FileListCache.MAX_PATHS) { truncated = true; break; }
                out.add(line);
            }
        }
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new ListingResult(out, truncated);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash bazel.cmd run //termlab/plugins/search:search_test_runner`
Expected: PASS — 16 tests green.

- [ ] **Step 5: Commit**

```bash
git add plugins/search/src/com/termlab/search/FileLister.java \
        plugins/search/test/com/termlab/search/FileListerTest.java
git commit -m "feat(search): FileLister runs local listings via subprocess or Files.walk

runLocalListing either forks rg/fd/find via ProcessBuilder and
reads stdout line-by-line, or falls back to Files.walk for the
Windows-no-POSIX-tools case. Integration-tested against a temp
dir tree with find-path exclusions."
```

---

## Task 10: `FileLister` — remote probing + exec

**Files:**
- Modify: `plugins/search/src/com/termlab/search/FileLister.java`

Remote execution goes through `ClientSession.createExecChannel`. Not unit-tested — covered by manual smoke. Keep the surface minimal.

- [ ] **Step 1: Add remote probing + exec helpers to `FileLister.java`**

Append to the class:

```java
    public record RemoteProbeResult(@NotNull FileListCache.Tool tool,
                                    @NotNull String executable) {}

    /**
     * Probe the remote host for rg / fd / find in that order. Runs
     * {@link #buildRemoteProbeCommand()}. If the probe succeeds and
     * returns a path, the path's basename decides the tool. If the
     * probe exec itself fails or returns blank, returns
     * {@link FileListCache.Tool#WALK} with an empty executable — the
     * caller should then fall back to {@code ls -R} streaming.
     */
    public static @NotNull RemoteProbeResult probeRemote(
        @NotNull org.apache.sshd.client.session.ClientSession session
    ) throws java.io.IOException {
        String probe = buildRemoteProbeCommand();
        String output = runRemoteCapture(session, probe, null).trim();
        if (output.isEmpty()) {
            return new RemoteProbeResult(FileListCache.Tool.WALK, "");
        }
        String basename = output.contains("/")
            ? output.substring(output.lastIndexOf('/') + 1)
            : output;
        FileListCache.Tool tool = switch (basename) {
            case "rg" -> FileListCache.Tool.RG;
            case "fd" -> FileListCache.Tool.FD;
            case "find" -> FileListCache.Tool.FIND;
            default -> FileListCache.Tool.WALK;
        };
        return new RemoteProbeResult(tool, output);
    }

    public static @NotNull ListingResult runRemoteListing(
        @NotNull org.apache.sshd.client.session.ClientSession session,
        @NotNull RemoteProbeResult probe,
        @NotNull String root,
        @NotNull List<String> exclusionFlags,
        @org.jetbrains.annotations.Nullable com.intellij.openapi.progress.ProgressIndicator indicator
    ) throws java.io.IOException {
        List<String> cmd;
        if (probe.tool() == FileListCache.Tool.WALK) {
            cmd = List.of("sh", "-c", "ls -R " + shellQuote(root));
        } else {
            cmd = buildListCommand(probe.tool(), root, exclusionFlags);
            cmd.set(0, probe.executable());
        }
        String commandLine = String.join(" ", cmd.stream().map(FileLister::shellQuote).toList());
        return collectRemoteLines(session, commandLine, indicator);
    }

    public static @NotNull ListingResult runRemoteQuery(
        @NotNull org.apache.sshd.client.session.ClientSession session,
        @NotNull RemoteProbeResult probe,
        @NotNull String root,
        @NotNull String substring,
        @NotNull List<String> exclusionFlags,
        @org.jetbrains.annotations.Nullable com.intellij.openapi.progress.ProgressIndicator indicator
    ) throws java.io.IOException {
        if (probe.tool() == FileListCache.Tool.RG || probe.tool() == FileListCache.Tool.WALK) {
            // RG: re-run listing, filter JVM-side (same as local).
            // WALK: listing already returns everything; caller filters JVM-side.
            return runRemoteListing(session, probe, root, exclusionFlags, indicator);
        }
        List<String> cmd = buildQueryCommand(probe.tool(), root, substring, exclusionFlags);
        cmd.set(0, probe.executable());
        String commandLine = String.join(" ", cmd.stream().map(FileLister::shellQuote).toList());
        return collectRemoteLines(session, commandLine, indicator);
    }

    private static @NotNull String runRemoteCapture(
        @NotNull org.apache.sshd.client.session.ClientSession session,
        @NotNull String command,
        @org.jetbrains.annotations.Nullable com.intellij.openapi.progress.ProgressIndicator indicator
    ) throws java.io.IOException {
        org.apache.sshd.client.channel.ChannelExec channel = session.createExecChannel(command);
        channel.open().verify(java.time.Duration.ofSeconds(10));
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(channel.getInvertedOut(),
                    java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (indicator != null && indicator.isCanceled()) break;
                sb.append(line).append('\n');
            }
            channel.waitFor(java.util.EnumSet.of(
                org.apache.sshd.client.channel.ClientChannelEvent.CLOSED), 5_000L);
            return sb.toString();
        } finally {
            try { channel.close(true); } catch (Exception ignored) {}
        }
    }

    private static @NotNull ListingResult collectRemoteLines(
        @NotNull org.apache.sshd.client.session.ClientSession session,
        @NotNull String commandLine,
        @org.jetbrains.annotations.Nullable com.intellij.openapi.progress.ProgressIndicator indicator
    ) throws java.io.IOException {
        org.apache.sshd.client.channel.ChannelExec channel = session.createExecChannel(commandLine);
        channel.open().verify(java.time.Duration.ofSeconds(10));
        List<String> out = new ArrayList<>();
        boolean truncated = false;
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(channel.getInvertedOut(),
                    java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (indicator != null && indicator.isCanceled()) break;
                if (out.size() >= FileListCache.MAX_PATHS) { truncated = true; break; }
                out.add(line);
            }
            channel.waitFor(java.util.EnumSet.of(
                org.apache.sshd.client.channel.ClientChannelEvent.CLOSED), 60_000L);
        } finally {
            try { channel.close(true); } catch (Exception ignored) {}
        }
        return new ListingResult(out, truncated);
    }

    /** Single-quote a string for /bin/sh. Minimal — no non-ASCII handling needed here. */
    private static @NotNull String shellQuote(@NotNull String s) {
        if (s.matches("[A-Za-z0-9_/.:@=+,-]+")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }
```

- [ ] **Step 2: Build to verify compile**

Run: `make termlab-build`
Expected: clean build. No new tests in this task — remote paths are manual-smoke-only.

- [ ] **Step 3: Commit**

```bash
git add plugins/search/src/com/termlab/search/FileLister.java
git commit -m "feat(search): FileLister remote probing + exec-channel execution

probeRemote runs 'command -v rg || fd || find || true' via an
exec channel, parses the basename to decide the Tool, falls back
to WALK if all three are absent. runRemoteListing / runRemoteQuery
build the arg list, shell-quote, and stream stdout through the
channel. Unit-test-free — manual smoke covers the remote path."
```

---

## Task 11: `FileSearchStatusProgress` wrapper

**Files:**
- Create: `plugins/search/src/com/termlab/search/FileSearchStatusProgress.java`

A small `Task.Backgroundable` factory that keeps the title format and the cancel-to-EMPTY glue out of `FileSearchContributor`.

- [ ] **Step 1: Create the class**

```java
package com.termlab.search;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.termlab.sftp.search.FileListCache;
import org.jetbrains.annotations.NotNull;

/**
 * Fires a background {@link Task.Backgroundable} that a file-list
 * cache build runs under, so the build surfaces as a status-bar
 * progress entry. Cancelling the indicator walks the cache back to
 * {@code EMPTY} so the next Search Everywhere open re-triggers a
 * fresh build.
 */
public final class FileSearchStatusProgress {

    private FileSearchStatusProgress() {}

    public interface Build {
        void run(@NotNull ProgressIndicator indicator) throws Exception;
    }

    public static void runLocal(
        @NotNull Project project,
        @NotNull String rootDisplay,
        @NotNull FileListCache cache,
        @NotNull Build build
    ) {
        run(project, "Indexing files in " + rootDisplay, cache, build);
    }

    public static void runRemote(
        @NotNull Project project,
        @NotNull String hostLabel,
        @NotNull FileListCache cache,
        @NotNull Build build
    ) {
        run(project, "Indexing files on " + hostLabel, cache, build);
    }

    private static void run(
        @NotNull Project project,
        @NotNull String title,
        @NotNull FileListCache cache,
        @NotNull Build build
    ) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, title, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    build.run(indicator);
                } catch (Exception e) {
                    cache.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
                    return;
                }
                if (indicator.isCanceled()) {
                    cache.invalidate();
                }
            }
        });
    }
}
```

- [ ] **Step 2: Build to verify compile**

Run: `make termlab-build`
Expected: clean build.

- [ ] **Step 3: Commit**

```bash
git add plugins/search/src/com/termlab/search/FileSearchStatusProgress.java
git commit -m "feat(search): FileSearchStatusProgress wraps cache builds in status-bar task

Single entry point for 'Indexing files in <root>' / 'Indexing
files on <hostname>' progress. On cancel, walks the cache back
to EMPTY; on exception, marks it FAILED with a short reason."
```

---

## Task 12: `FileHit` record + cell renderer

**Files:**
- Create: `plugins/search/src/com/termlab/search/FileHit.java`
- Create: `plugins/search/src/com/termlab/search/FileHitRenderer.java`

`FileHit` is the result type. Renderer shows the filename, parent dir (greyed), and a `local` / `<hostname>` chip on the right.

- [ ] **Step 1: Create `FileHit.java`**

```java
package com.termlab.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One search result: a path plus the side it came from. Ranking score
 * is carried as a field so the contributor can merge local + remote
 * hits by score before emitting.
 */
public record FileHit(
    @NotNull String path,
    @NotNull Side side,
    @Nullable String hostName,
    int score
) {
    public enum Side { LOCAL, REMOTE }

    public @NotNull String fileName() {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public @NotNull String parentDir() {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? "" : path.substring(0, slash);
    }

    public @NotNull String sourceLabel() {
        return side == Side.LOCAL ? "local" : (hostName == null ? "remote" : hostName);
    }
}
```

- [ ] **Step 2: Create `FileHitRenderer.java`**

```java
package com.termlab.search;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Two-line cell renderer: filename primary, parent dir secondary
 * (greyed). Source chip on the right — computer icon for local,
 * server icon + hostname for remote.
 */
public final class FileHitRenderer implements ListCellRenderer<Object> {

    @Override
    public @NotNull Component getListCellRendererComponent(
        @NotNull JList<?> list,
        Object value,
        int index,
        boolean isSelected,
        boolean cellHasFocus
    ) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBorder(new EmptyBorder(JBUI.insets(2, 6)));
        row.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());

        if (!(value instanceof FileHit hit)) {
            JLabel fallback = new JLabel(String.valueOf(value));
            fallback.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            row.add(fallback, BorderLayout.CENTER);
            return row;
        }

        JPanel textStack = new JPanel(new GridLayout(2, 1));
        textStack.setOpaque(false);
        JLabel name = new JLabel(hit.fileName(), AllIcons.FileTypes.Any_type, SwingConstants.LEFT);
        name.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
        JLabel path = new JLabel(hit.parentDir());
        path.setForeground(isSelected ? list.getSelectionForeground() : JBColor.GRAY);
        path.setFont(path.getFont().deriveFont(Math.max(10f, path.getFont().getSize2D() - 1f)));
        textStack.add(name);
        textStack.add(path);
        row.add(textStack, BorderLayout.CENTER);

        Icon chipIcon = hit.side() == FileHit.Side.LOCAL
            ? AllIcons.Nodes.HomeFolder
            : AllIcons.Webreferences.Server;
        JLabel chip = new JLabel(hit.sourceLabel(), chipIcon, SwingConstants.RIGHT);
        chip.setForeground(isSelected ? list.getSelectionForeground() : JBColor.GRAY);
        chip.setBorder(new EmptyBorder(JBUI.insets(0, 8, 0, 0)));
        row.add(chip, BorderLayout.EAST);

        return row;
    }
}
```

- [ ] **Step 3: Build to verify compile**

Run: `make termlab-build`
Expected: clean build.

- [ ] **Step 4: Commit**

```bash
git add plugins/search/src/com/termlab/search/FileHit.java \
        plugins/search/src/com/termlab/search/FileHitRenderer.java
git commit -m "feat(search): FileHit record + two-line cell renderer

FileHit carries path, side, hostName, score. Renderer shows the
filename primary, parent dir secondary greyed, and a local /
hostname chip on the right with a matching icon."
```

---

## Task 13: `FileSearchContributor` skeleton

**Files:**
- Create: `plugins/search/src/com/termlab/search/FileSearchContributor.java`

Empty fetchElements returning nothing — just the `SearchEverywhereContributor` / `Factory` shape. Later tasks fill in cache, live fallback, filter toolbar, and enter handling.

- [ ] **Step 1: Create the skeleton**

```java
package com.termlab.search;

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;
import java.util.Objects;

public final class FileSearchContributor implements SearchEverywhereContributor<FileHit> {

    private final Project project;

    public FileSearchContributor(@NotNull Project project) {
        this.project = project;
    }

    @Override public @NotNull String getSearchProviderId() { return "TermLabFiles"; }
    @Override public @NotNull String getGroupName() { return "Files"; }
    @Override public int getSortWeight() { return 40; }
    @Override public boolean showInFindResults() { return false; }
    @Override public boolean isShownInSeparateTab() { return true; }
    @Override public boolean isEmptyPatternSupported() { return true; }

    @Override
    public void fetchElements(@NotNull String pattern,
                              @NotNull ProgressIndicator progressIndicator,
                              @NotNull Processor<? super FileHit> consumer) {
        // Filled in by subsequent tasks.
    }

    @Override
    public boolean processSelectedItem(@NotNull FileHit selected, int modifiers, @NotNull String searchText) {
        // Filled in by Task 17.
        return true;
    }

    @Override
    public @NotNull ListCellRenderer<? super FileHit> getElementsRenderer() {
        return new FileHitRenderer();
    }

    @Override
    public @Nullable Object getDataForItem(@NotNull FileHit element, @NotNull String dataId) {
        return null;
    }

    @Override
    public @NotNull List<AnAction> getActions(@NotNull Runnable onChanged) {
        // Filter popup lands in Task 16.
        return List.of();
    }

    public static final class Factory implements SearchEverywhereContributorFactory<FileHit> {
        @Override
        public @NotNull SearchEverywhereContributor<FileHit> createContributor(@NotNull AnActionEvent initEvent) {
            Project project = Objects.requireNonNull(
                initEvent.getProject(),
                "Project required for FileSearchContributor");
            return new FileSearchContributor(project);
        }
    }
}
```

- [ ] **Step 2: Build to verify compile**

Run: `make termlab-build`
Expected: clean build.

- [ ] **Step 3: Commit**

```bash
git add plugins/search/src/com/termlab/search/FileSearchContributor.java
git commit -m "feat(search): FileSearchContributor skeleton

SearchEverywhereContributor<FileHit> + Factory with id TermLabFiles,
group Files, sortWeight 40. fetchElements is a no-op; filter popup
and processSelectedItem land in later tasks. Not yet registered in
plugin.xml."
```

---

## Task 14: `FileSearchContributor.fetchElements` — cache path

**Files:**
- Modify: `plugins/search/src/com/termlab/search/FileSearchContributor.java`

Resolve the selected SFTP content, trigger cache builds if EMPTY, filter cached paths via `MinusculeMatcher`, apply the regex, emit `FileHit`s. No live fallback yet (Task 15).

- [ ] **Step 1: Implement `fetchElements` cache path**

Replace the `fetchElements` method body with:

```java
    @Override
    public void fetchElements(@NotNull String pattern,
                              @NotNull ProgressIndicator progressIndicator,
                              @NotNull Processor<? super FileHit> consumer) {
        PaneRefs panes = resolvePanes();
        if (panes == null) return;

        // Trigger builds for any EMPTY cache. Emits no results this tick;
        // the next SE re-query (as the user types another character) will
        // see READY caches.
        triggerIfEmpty(panes.localPane, panes.localCache(), FileHit.Side.LOCAL, /*hostName*/ null);
        if (panes.remotePane != null) {
            triggerIfEmpty(panes.remotePane, panes.remoteCache(),
                FileHit.Side.REMOTE, panes.remoteHostLabel());
        }

        com.intellij.psi.codeStyle.MinusculeMatcher matcher = pattern.isEmpty()
            ? null
            : com.intellij.psi.codeStyle.NameUtil.buildMatcher("*" + pattern).build();

        java.util.regex.Pattern excludeRegex =
            FileSearchFilter.getInstance().compileRegex().pattern();

        List<FileHit> hits = new java.util.ArrayList<>();
        collectFrom(panes.localCache(), FileHit.Side.LOCAL, /*hostName*/ null,
            matcher, excludeRegex, hits, progressIndicator);
        if (panes.remotePane != null) {
            collectFrom(panes.remoteCache(), FileHit.Side.REMOTE,
                panes.remoteHostLabel(), matcher, excludeRegex, hits, progressIndicator);
        }

        hits.sort(java.util.Comparator
            .comparingInt(FileHit::score).reversed()
            .thenComparing(h -> h.path().length())
            .thenComparing(FileHit::path));

        for (FileHit hit : hits) {
            if (progressIndicator.isCanceled()) return;
            if (!consumer.process(hit)) return;
        }
    }

    private void triggerIfEmpty(
        @NotNull Object paneOwner,
        @NotNull com.termlab.sftp.search.FileListCache cache,
        @NotNull FileHit.Side side,
        @Nullable String hostLabel
    ) {
        if (cache.state() != com.termlab.sftp.search.FileListCache.State.EMPTY) return;

        String rootDisplay;
        Runnable buildFn;

        if (side == FileHit.Side.LOCAL) {
            var lp = (com.termlab.sftp.toolwindow.LocalFilePane) paneOwner;
            java.nio.file.Path cwd = lp.currentDirectory();
            if (cwd == null) return;
            if (!cache.beginBuild(cwd.toString())) return;
            rootDisplay = cwd.toString();
            buildFn = () -> FileSearchStatusProgress.runLocal(project, rootDisplay, cache,
                indicator -> runLocalBuild(cache, cwd.toString(), indicator));
        } else {
            var rp = (com.termlab.sftp.toolwindow.RemoteFilePane) paneOwner;
            String cwd = rp.currentRemotePath();
            if (cwd == null) return;
            var session = rp.activeSftpSessionOrNull();
            if (session == null) return;
            if (!cache.beginBuild(cwd)) return;
            String label = hostLabel == null ? "remote" : hostLabel;
            rootDisplay = cwd;
            buildFn = () -> FileSearchStatusProgress.runRemote(project, label, cache,
                indicator -> runRemoteBuild(cache, session.session(), cwd, indicator));
        }
        buildFn.run();
    }

    private static void runLocalBuild(
        @NotNull com.termlab.sftp.search.FileListCache cache,
        @NotNull String root,
        @NotNull ProgressIndicator indicator
    ) throws java.io.IOException {
        FileLister.LocalProbeResult probe = FileLister.probeLocalDefault();
        List<String> flags = FileSearchFilter.getInstance()
            .toListCommandFlags(probe.tool());
        FileLister.ListingResult r = FileLister.runLocalListing(
            probe, root, flags, indicator);
        cache.succeed(r.paths(), probe.tool(), r.truncated());
    }

    private static void runRemoteBuild(
        @NotNull com.termlab.sftp.search.FileListCache cache,
        @NotNull org.apache.sshd.client.session.ClientSession session,
        @NotNull String root,
        @NotNull ProgressIndicator indicator
    ) throws java.io.IOException {
        FileLister.RemoteProbeResult probe = FileLister.probeRemote(session);
        List<String> flags = FileSearchFilter.getInstance()
            .toListCommandFlags(probe.tool());
        FileLister.ListingResult r = FileLister.runRemoteListing(
            session, probe, root, flags, indicator);
        cache.succeed(r.paths(), probe.tool(), r.truncated());
    }

    private static void collectFrom(
        @NotNull com.termlab.sftp.search.FileListCache cache,
        @NotNull FileHit.Side side,
        @Nullable String hostLabel,
        @Nullable com.intellij.psi.codeStyle.MinusculeMatcher matcher,
        @Nullable java.util.regex.Pattern excludeRegex,
        @NotNull List<FileHit> out,
        @NotNull ProgressIndicator indicator
    ) {
        if (cache.state() != com.termlab.sftp.search.FileListCache.State.READY) return;
        List<String> paths = cache.paths();
        if (paths == null) return;
        for (String path : paths) {
            if (indicator.isCanceled()) return;
            if (excludeRegex != null && excludeRegex.matcher(path).find()) continue;
            String base = baseName(path);
            int score;
            if (matcher == null) {
                score = 0;
            } else {
                score = matcher.matchingDegree(base);
                if (score <= 0) continue;
            }
            out.add(new FileHit(path, side, hostLabel, score));
        }
    }

    private static @NotNull String baseName(@NotNull String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * Snapshot of the currently-selected SFTP content's panes. Returns
     * null when there is no SFTP tool window or no selected content.
     */
    private @Nullable PaneRefs resolvePanes() {
        com.intellij.openapi.wm.ToolWindow tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow(com.termlab.sftp.toolwindow.SftpToolWindowFactory.ID);
        if (tw == null) return null;
        com.intellij.ui.content.Content content = tw.getContentManager().getSelectedContent();
        if (content == null) return null;
        if (!(content.getComponent() instanceof com.termlab.sftp.toolwindow.SftpToolWindow win)) return null;
        return new PaneRefs(win.localPane(), win.remotePane());
    }

    private record PaneRefs(
        @NotNull com.termlab.sftp.toolwindow.LocalFilePane localPane,
        @Nullable com.termlab.sftp.toolwindow.RemoteFilePane remotePane
    ) {
        com.termlab.sftp.search.FileListCache localCache() { return localPane.fileListCache(); }
        com.termlab.sftp.search.FileListCache remoteCache() {
            return remotePane == null ? null : remotePane.fileListCache();
        }
        @Nullable String remoteHostLabel() {
            return remotePane == null ? null : remotePane.currentHostLabel();
        }
    }
```

Note: `SftpToolWindow.localPane()`, `SftpToolWindow.remotePane()`, `RemoteFilePane.activeSftpSessionOrNull()`, and `RemoteFilePane.currentHostLabel()` are new accessors we need. Add them in Step 2.

- [ ] **Step 2: Add SFTP accessors to existing classes**

In `plugins/sftp/src/com/termlab/sftp/toolwindow/SftpToolWindow.java`, add:

```java
    public @NotNull LocalFilePane localPane() { return local; }
    public @NotNull RemoteFilePane remotePane() { return remote; }
```

(Replace `local` and `remote` with whatever the actual field names are — inspect the file to confirm; from the grep in the investigation, they're referenced as `local.currentDirectory()` and `remote.currentRemotePath()`, so the field names are `local` and `remote`.)

In `plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java`, add near `currentRemotePath()`:

```java
    public @Nullable com.termlab.sftp.client.SshSftpSession activeSftpSessionOrNull() {
        return activeSession;
    }

    public @Nullable String currentHostLabel() {
        return currentHost == null ? null : currentHost.label();
    }
```

- [ ] **Step 3: Build to verify compile**

Run: `make termlab-build`
Expected: clean build. No new tests — manual smoke test after Task 21 exercises the full flow.

- [ ] **Step 4: Commit**

```bash
git add plugins/search/src/com/termlab/search/FileSearchContributor.java \
        plugins/sftp/src/com/termlab/sftp/toolwindow/SftpToolWindow.java \
        plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java
git commit -m "feat(search): FileSearchContributor cache-backed fetchElements

Resolves the selected SFTP content, kicks off lazy cache builds
via FileSearchStatusProgress, filters cached paths with
MinusculeMatcher + exclude regex, sorts by score/length/path,
and streams FileHits to Search Everywhere. Live fallback lands
in the next task.

Adds SftpToolWindow.localPane() / remotePane() and
RemoteFilePane.activeSftpSessionOrNull() / currentHostLabel()
accessors consumed by the contributor."
```

---

## Task 15: `FileSearchContributor` — hybrid live fallback

**Files:**
- Modify: `plugins/search/src/com/termlab/search/FileSearchContributor.java`

When the cache-backed pass produces fewer than 3 matches for a query ≥ 3 chars, fire live `runLocalListing` / `runRemoteQuery` calls for missing results, dedupe against cache results, stream those too.

- [ ] **Step 1: Extend `fetchElements`**

At the end of `fetchElements` (after the `for (FileHit hit : hits)` loop but still inside the method), add:

```java
        if (pattern.length() >= 3 && hits.size() < 3) {
            runLiveFallback(pattern, panes, matcher, excludeRegex, hits, consumer, progressIndicator);
        }
    }

    private void runLiveFallback(
        @NotNull String pattern,
        @NotNull PaneRefs panes,
        @Nullable com.intellij.psi.codeStyle.MinusculeMatcher matcher,
        @Nullable java.util.regex.Pattern excludeRegex,
        @NotNull List<FileHit> alreadyEmitted,
        @NotNull Processor<? super FileHit> consumer,
        @NotNull ProgressIndicator indicator
    ) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (FileHit h : alreadyEmitted) seen.add(h.path());

        // Local fallback
        try {
            FileLister.LocalProbeResult probe = FileLister.probeLocalDefault();
            java.nio.file.Path cwd = panes.localPane.currentDirectory();
            if (cwd != null) {
                List<String> flags = FileSearchFilter.getInstance()
                    .toListCommandFlags(probe.tool());
                FileLister.ListingResult r = probe.tool() == com.termlab.sftp.search.FileListCache.Tool.RG
                    || probe.tool() == com.termlab.sftp.search.FileListCache.Tool.WALK
                    ? FileLister.runLocalListing(probe, cwd.toString(), flags, indicator)
                    : FileLister.runLocalListing(probe, cwd.toString(),
                        appendQueryFlag(probe.tool(), flags, pattern), indicator);
                streamLiveHits(r.paths(), FileHit.Side.LOCAL, null, pattern,
                    matcher, excludeRegex, seen, consumer, indicator);
            }
        } catch (Exception ignore) {
            // Live fallback is best-effort. Cache-backed results already emitted.
        }

        // Remote fallback
        if (panes.remotePane != null) {
            try {
                var rpSession = panes.remotePane.activeSftpSessionOrNull();
                String cwd = panes.remotePane.currentRemotePath();
                if (rpSession != null && cwd != null) {
                    FileLister.RemoteProbeResult probe = FileLister.probeRemote(rpSession.session());
                    List<String> flags = FileSearchFilter.getInstance()
                        .toListCommandFlags(probe.tool());
                    FileLister.ListingResult r = FileLister.runRemoteQuery(
                        rpSession.session(), probe, cwd, pattern, flags, indicator);
                    streamLiveHits(r.paths(), FileHit.Side.REMOTE, panes.remoteHostLabel(),
                        pattern, matcher, excludeRegex, seen, consumer, indicator);
                }
            } catch (Exception ignore) {
                // Ditto.
            }
        }
    }

    private static List<String> appendQueryFlag(
        @NotNull com.termlab.sftp.search.FileListCache.Tool tool,
        @NotNull List<String> existing,
        @NotNull String pattern
    ) {
        // Only fd/find take the pattern as an arg; rg and walk filter JVM-side.
        if (tool != com.termlab.sftp.search.FileListCache.Tool.FD
            && tool != com.termlab.sftp.search.FileListCache.Tool.FIND) {
            return existing;
        }
        List<String> out = new java.util.ArrayList<>(existing);
        if (tool == com.termlab.sftp.search.FileListCache.Tool.FD) {
            out.add(pattern);
        } else {
            out.add("-iname");
            out.add("*" + pattern + "*");
        }
        return out;
    }

    private static void streamLiveHits(
        @NotNull List<String> paths,
        @NotNull FileHit.Side side,
        @Nullable String hostLabel,
        @NotNull String pattern,
        @Nullable com.intellij.psi.codeStyle.MinusculeMatcher matcher,
        @Nullable java.util.regex.Pattern excludeRegex,
        @NotNull java.util.Set<String> seen,
        @NotNull Processor<? super FileHit> consumer,
        @NotNull ProgressIndicator indicator
    ) {
        for (String path : paths) {
            if (indicator.isCanceled()) return;
            if (!seen.add(path)) continue;
            if (excludeRegex != null && excludeRegex.matcher(path).find()) continue;
            String base = baseName(path);
            int score = matcher == null ? 0 : matcher.matchingDegree(base);
            if (matcher != null && score <= 0) continue;
            if (!consumer.process(new FileHit(path, side, hostLabel, score))) return;
        }
    }
```

- [ ] **Step 2: Build to verify compile**

Run: `make termlab-build`
Expected: clean build.

- [ ] **Step 3: Commit**

```bash
git add plugins/search/src/com/termlab/search/FileSearchContributor.java
git commit -m "feat(search): FileSearchContributor hybrid live fallback

When cached results for a ≥3-char query return <3 hits, fires a
live FileLister call on each side, dedupes against cache output,
and streams the additional matches. Best-effort — exceptions are
swallowed because the cache-backed pass already emitted the
primary results."
```

---

## Task 16: Filter popup (`getActions`)

**Files:**
- Modify: `plugins/search/src/com/termlab/search/FileSearchContributor.java`
- Create: `plugins/search/src/com/termlab/search/FileSearchFilterPopup.java`

One action (funnel icon) in `getActions`. Click opens a popup with chip checkboxes, custom-globs text area, regex field, reset link, refresh button. Changes persist to `FileSearchFilter` and call `onChanged`.

- [ ] **Step 1: Create `FileSearchFilterPopup.java`**

```java
package com.termlab.search;

import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.termlab.sftp.search.FileListCache;
import com.termlab.sftp.toolwindow.LocalFilePane;
import com.termlab.sftp.toolwindow.RemoteFilePane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

final class FileSearchFilterPopup {

    private FileSearchFilterPopup() {}

    static void show(
        @NotNull Component owner,
        @NotNull Runnable onChanged,
        @NotNull Runnable invalidateCaches
    ) {
        FileSearchFilter filter = FileSearchFilter.getInstance();
        FileSearchFilter.State s = filter.getState();

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(JBUI.insets(8, 12, 8, 12)));

        panel.add(makeChip("Exclude .git / .svn / .hg", s.excludeGit, v -> { s.excludeGit = v; onExclusionChanged(invalidateCaches, onChanged); }));
        panel.add(makeChip("Exclude node_modules", s.excludeNodeModules, v -> { s.excludeNodeModules = v; onExclusionChanged(invalidateCaches, onChanged); }));
        panel.add(makeChip("Exclude .idea / .vscode", s.excludeIdea, v -> { s.excludeIdea = v; onExclusionChanged(invalidateCaches, onChanged); }));
        panel.add(makeChip("Exclude build / dist / target / out", s.excludeBuild, v -> { s.excludeBuild = v; onExclusionChanged(invalidateCaches, onChanged); }));
        panel.add(makeChip("Exclude .cache / __pycache__ / .gradle", s.excludeCache, v -> { s.excludeCache = v; onExclusionChanged(invalidateCaches, onChanged); }));
        panel.add(makeChip("Exclude .DS_Store / Thumbs.db", s.excludeDsStore, v -> { s.excludeDsStore = v; onExclusionChanged(invalidateCaches, onChanged); }));

        panel.add(Box.createVerticalStrut(8));

        JLabel customLbl = new JLabel("Custom patterns (one per line):");
        panel.add(customLbl);
        JTextArea customArea = new JTextArea(4, 24);
        customArea.setText(String.join("\n", s.customExcludes == null ? List.of() : s.customExcludes));
        customArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { persist(); }
            @Override public void removeUpdate(DocumentEvent e) { persist(); }
            @Override public void changedUpdate(DocumentEvent e) { persist(); }
            private void persist() {
                List<String> lines = new ArrayList<>();
                for (String line : customArea.getText().split("\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) lines.add(trimmed);
                }
                s.customExcludes = lines;
                onExclusionChanged(invalidateCaches, onChanged);
            }
        });
        panel.add(new JScrollPane(customArea));

        panel.add(Box.createVerticalStrut(8));

        JLabel regexLbl = new JLabel("Custom regex (applied after listing):");
        panel.add(regexLbl);
        JBTextField regexField = new JBTextField(s.excludeRegex == null ? "" : s.excludeRegex);
        JLabel regexError = new JLabel(" ");
        regexError.setForeground(Color.RED);
        regexField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { persist(); }
            @Override public void removeUpdate(DocumentEvent e) { persist(); }
            @Override public void changedUpdate(DocumentEvent e) { persist(); }
            private void persist() {
                s.excludeRegex = regexField.getText();
                FileSearchFilter.RegexResult r = filter.compileRegex();
                regexError.setText(r.error() == null ? " " : "Regex invalid: " + r.error());
                onChanged.run();
            }
        });
        panel.add(regexField);
        panel.add(regexError);

        panel.add(Box.createVerticalStrut(8));

        JButton reset = new JButton("Reset to defaults");
        reset.addActionListener(e -> {
            s.excludeGit = true;
            s.excludeNodeModules = true;
            s.excludeIdea = true;
            s.excludeBuild = true;
            s.excludeCache = true;
            s.excludeDsStore = true;
            s.customExcludes = new ArrayList<>();
            s.excludeRegex = "";
            onExclusionChanged(invalidateCaches, onChanged);
            // Redraw by re-opening the popup.
            SwingUtilities.invokeLater(() -> show(owner, onChanged, invalidateCaches));
        });
        JButton refresh = new JButton("Refresh listing");
        refresh.addActionListener(e -> {
            invalidateCaches.run();
            onChanged.run();
        });
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT));
        footer.add(reset);
        footer.add(refresh);
        panel.add(footer);

        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, regexField)
            .setRequestFocus(true)
            .setMovable(true)
            .setResizable(true)
            .setCancelOnClickOutside(true)
            .createPopup()
            .showUnderneathOf(owner);

        IdeFocusManager.getGlobalInstance().requestFocus(regexField, true);
    }

    private static void onExclusionChanged(@NotNull Runnable invalidate, @NotNull Runnable onChanged) {
        invalidate.run();
        onChanged.run();
    }

    private static @NotNull JBCheckBox makeChip(
        @NotNull String text, boolean initial, @NotNull java.util.function.Consumer<Boolean> onToggle
    ) {
        JBCheckBox cb = new JBCheckBox(text, initial);
        cb.addActionListener(e -> onToggle.accept(cb.isSelected()));
        return cb;
    }
}
```

- [ ] **Step 2: Wire `getActions` in `FileSearchContributor.java`**

Replace the existing `getActions` method:

```java
    @Override
    public @NotNull List<AnAction> getActions(@NotNull Runnable onChanged) {
        AnAction filterAction = new com.intellij.openapi.actionSystem.AnAction(
            () -> "File search filter",
            () -> "Toggle default exclusions, add custom globs, or set a regex",
            com.intellij.icons.AllIcons.General.Filter
        ) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setIcon(FileSearchFilter.getInstance().isDefault()
                    ? com.intellij.icons.AllIcons.General.Filter
                    : new com.intellij.ui.LayeredIcon(
                        com.intellij.icons.AllIcons.General.Filter,
                        com.intellij.icons.AllIcons.Nodes.TabPin));
            }

            @Override
            public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
                return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                java.awt.Component ownerComp = e.getInputEvent() != null
                    ? e.getInputEvent().getComponent()
                    : (java.awt.Component) e.getData(
                        com.intellij.openapi.actionSystem.PlatformCoreDataKeys.CONTEXT_COMPONENT);
                if (ownerComp == null) return;
                FileSearchFilterPopup.show(ownerComp, onChanged, this::invalidateCachesOnChange);
            }

            private void invalidateCachesOnChange() {
                PaneRefs panes = resolvePanes();
                if (panes == null) return;
                panes.localCache().invalidate();
                if (panes.remoteCache() != null) panes.remoteCache().invalidate();
            }
        };
        return List.of(filterAction);
    }
```

- [ ] **Step 3: Build to verify compile**

Run: `make termlab-build`
Expected: clean build.

- [ ] **Step 4: Commit**

```bash
git add plugins/search/src/com/termlab/search/FileSearchContributor.java \
        plugins/search/src/com/termlab/search/FileSearchFilterPopup.java
git commit -m "feat(search): filter popup with chips, custom patterns, regex

Funnel-icon action in the Files tab opens a popup with six chip
checkboxes, a custom-patterns text area, a regex field with inline
error surfacing, reset-to-defaults, and refresh-listing. Chip /
custom-pattern changes invalidate both caches; regex changes just
re-filter in memory. Dot overlay on the icon when non-default."
```

---

## Task 17: `processSelectedItem` — open in light editor

**Files:**
- Modify: `plugins/search/src/com/termlab/search/FileSearchContributor.java`

Local → build `LocalFileEntry.of(path)`, call `LocalFileOpener.EP_NAME`. Remote → resolve `RemoteFilePane.activeSftpSessionOrNull()` + `currentHost`, build a minimal `RemoteFileEntry`, call `RemoteFileOpener.EP_NAME`.

- [ ] **Step 1: Replace `processSelectedItem`**

```java
    @Override
    public boolean processSelectedItem(@NotNull FileHit selected, int modifiers, @NotNull String searchText) {
        if (selected.side() == FileHit.Side.LOCAL) {
            return openLocal(selected.path());
        }
        return openRemote(selected.path());
    }

    private boolean openLocal(@NotNull String path) {
        try {
            java.nio.file.Path nio = java.nio.file.Paths.get(path);
            com.termlab.sftp.model.LocalFileEntry entry = com.termlab.sftp.model.LocalFileEntry.of(nio);
            var openers = com.termlab.sftp.spi.LocalFileOpener.EP_NAME.getExtensionList();
            if (openers.isEmpty()) return true;
            openers.get(0).open(project, entry);
        } catch (java.io.IOException ignored) {
            // Couldn't stat the file — path may be gone. Silently swallow;
            // user's next SE re-query will see an updated cache (or none).
        }
        return true;
    }

    private boolean openRemote(@NotNull String path) {
        PaneRefs panes = resolvePanes();
        if (panes == null || panes.remotePane == null) return true;
        var rpSession = panes.remotePane.activeSftpSessionOrNull();
        com.termlab.ssh.model.SshHost host = panes.remotePane.currentHostOrNull();
        if (rpSession == null || host == null) return true;

        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash < 0 ? path : path.substring(slash + 1);
        // Minimal RemoteFileEntry — we don't have stat info from the listing,
        // and the opener plugins don't require size/perms to open.
        com.termlab.sftp.model.RemoteFileEntry stub = new com.termlab.sftp.model.RemoteFileEntry(
            name, 0L, null, false, false, "");
        var openers = com.termlab.sftp.spi.RemoteFileOpener.EP_NAME.getExtensionList();
        if (openers.isEmpty()) return true;
        openers.get(0).open(project, host, rpSession, path, stub);
        return true;
    }
```

- [ ] **Step 2: Add `RemoteFilePane.currentHostOrNull()`**

In `plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java`, add near `currentHostLabel`:

```java
    public @Nullable com.termlab.ssh.model.SshHost currentHostOrNull() {
        return currentHost;
    }
```

- [ ] **Step 3: Build to verify compile**

Run: `make termlab-build`
Expected: clean build.

- [ ] **Step 4: Commit**

```bash
git add plugins/search/src/com/termlab/search/FileSearchContributor.java \
        plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java
git commit -m "feat(search): processSelectedItem opens hits in the light editor

Local side builds a LocalFileEntry from the path and dispatches
to LocalFileOpener.EP_NAME. Remote side resolves the active
SftpSession + current host from the pane, stubs a minimal
RemoteFileEntry (openers don't need stat fields to open), and
dispatches to RemoteFileOpener.EP_NAME."
```

---

## Task 18: Tool-fallback & probe-failure balloons

**Files:**
- Modify: `plugins/search/src/com/termlab/search/FileSearchContributor.java`

After each cache build succeeds, if the tool used is `FIND` or `WALK`, fire a one-shot balloon with a "Don't show again for this host" action. Suppression keyed by `"local"` / `hostId` in `FileSearchFilter.State.dismissedRipgrepHints`.

- [ ] **Step 1: Add balloon helpers to `FileSearchContributor.java`**

Add near the top of the class, above `fetchElements`:

```java
    private static final String NOTIFICATION_GROUP_ID = "TermLab File Search";
```

Inside `runLocalBuild`, after the `cache.succeed` call, invoke a follow-up balloon check on the EDT:

Replace `runLocalBuild` with:

```java
    private void runLocalBuild(
        @NotNull com.termlab.sftp.search.FileListCache cache,
        @NotNull String root,
        @NotNull ProgressIndicator indicator
    ) throws java.io.IOException {
        FileLister.LocalProbeResult probe = FileLister.probeLocalDefault();
        List<String> flags = FileSearchFilter.getInstance()
            .toListCommandFlags(probe.tool());
        FileLister.ListingResult r = FileLister.runLocalListing(
            probe, root, flags, indicator);
        cache.succeed(r.paths(), probe.tool(), r.truncated());
        maybeShowFallbackBalloon(probe.tool(), "local", "local machine");
    }
```

And replace `runRemoteBuild` similarly to capture hostId:

```java
    private void runRemoteBuild(
        @NotNull com.termlab.sftp.search.FileListCache cache,
        @NotNull org.apache.sshd.client.session.ClientSession session,
        @NotNull String root,
        @NotNull String hostIdKey,
        @NotNull String hostDisplay,
        @NotNull ProgressIndicator indicator
    ) throws java.io.IOException {
        FileLister.RemoteProbeResult probe = FileLister.probeRemote(session);
        List<String> flags = FileSearchFilter.getInstance()
            .toListCommandFlags(probe.tool());
        FileLister.ListingResult r = FileLister.runRemoteListing(
            session, probe, root, flags, indicator);
        cache.succeed(r.paths(), probe.tool(), r.truncated());
        maybeShowFallbackBalloon(probe.tool(), hostIdKey, hostDisplay);
    }

    private void maybeShowFallbackBalloon(
        @NotNull com.termlab.sftp.search.FileListCache.Tool tool,
        @NotNull String suppressionKey,
        @NotNull String displayLocation
    ) {
        if (tool != com.termlab.sftp.search.FileListCache.Tool.FIND
            && tool != com.termlab.sftp.search.FileListCache.Tool.WALK) return;
        FileSearchFilter filter = FileSearchFilter.getInstance();
        if (filter.isRipgrepHintDismissed(suppressionKey)) return;

        com.intellij.notification.Notification n = new com.intellij.notification.Notification(
            NOTIFICATION_GROUP_ID,
            "TermLab file search",
            "Using " + tool.name().toLowerCase() + " for file search on " + displayLocation
                + ". Install ripgrep for much faster indexing.",
            com.intellij.notification.NotificationType.INFORMATION);
        n.addAction(com.intellij.openapi.actionSystem.ex.ActionUtil.createActionListener(
            "Open ripgrep install docs",
            e -> com.intellij.ide.BrowserUtil.browse(
                "https://github.com/BurntSushi/ripgrep#installation")));
        n.addAction(new com.intellij.notification.NotificationAction("Don't show again for " + displayLocation) {
            @Override
            public void actionPerformed(
                @NotNull com.intellij.openapi.actionSystem.AnActionEvent e,
                @NotNull com.intellij.notification.Notification notification
            ) {
                filter.dismissRipgrepHint(suppressionKey);
                notification.expire();
            }
        });
        n.notify(project);
    }
```

- [ ] **Step 2: Update `triggerIfEmpty` to pass the new remote args**

Replace the remote branch of `triggerIfEmpty` with:

```java
        } else {
            var rp = (com.termlab.sftp.toolwindow.RemoteFilePane) paneOwner;
            String cwd = rp.currentRemotePath();
            if (cwd == null) return;
            var session = rp.activeSftpSessionOrNull();
            com.termlab.ssh.model.SshHost host = rp.currentHostOrNull();
            if (session == null || host == null) return;
            if (!cache.beginBuild(cwd)) return;
            String label = hostLabel == null ? "remote" : hostLabel;
            String hostIdKey = host.id().toString();
            rootDisplay = cwd;
            buildFn = () -> FileSearchStatusProgress.runRemote(project, label, cache,
                indicator -> runRemoteBuild(cache, session.session(), cwd, hostIdKey, label, indicator));
        }
```

`ActionUtil.createActionListener` may not be the right helper — if it's missing, inline the link as plain text: remove the "Open ripgrep install docs" action and let the user copy the URL from the balloon body. Check the platform API before committing.

- [ ] **Step 3: Build to verify compile**

Run: `make termlab-build`
Expected: clean build.

- [ ] **Step 4: Commit**

```bash
git add plugins/search/src/com/termlab/search/FileSearchContributor.java
git commit -m "feat(search): balloon prompt to install ripgrep when falling back to find/walk

After a cache build completes with FIND or WALK as the chosen tool,
fires a one-shot balloon with 'Don't show again for this host'.
Dismissal is persisted in FileSearchFilter so the balloon stops
appearing for that host across sessions."
```

---

## Task 19: Plugin registration + allowlist + root BUILD.bazel

**Files:**
- Modify: `plugins/search/resources/META-INF/plugin.xml`
- Modify: `core/src/com/termlab/core/palette/TermLabTabsCustomizationStrategy.java`
- Modify: `BUILD.bazel` (root)

This is the commit that makes the tab appear.

- [ ] **Step 1: Update `plugins/search/resources/META-INF/plugin.xml`**

Replace the `<extensions>` block with:

```xml
    <extensions defaultExtensionNs="com.intellij">
        <notificationGroup
            id="TermLab File Search"
            displayType="BALLOON"/>

        <applicationService
            serviceImplementation="com.termlab.search.FileSearchFilter"/>

        <searchEverywhereContributor
            implementation="com.termlab.search.FileSearchContributor$Factory"/>
    </extensions>
```

- [ ] **Step 2: Update `core/src/com/termlab/core/palette/TermLabTabsCustomizationStrategy.java`**

Change `ALLOWED_TAB_IDS`:

```java
    private static final Set<String> ALLOWED_TAB_IDS = Set.of(
        "ActionSearchEverywhereContributor",
        "TermLabTerminals",
        "TermLabHosts",
        "TermLabVault",
        "TermLabTunnels",
        "TermLabSftp",
        "TermLabFiles"
    );
```

- [ ] **Step 3: Update root `BUILD.bazel`**

In the `termlab_run` `java_binary` target's `runtime_deps`, add `"//termlab/plugins/search",` after the existing `//termlab/plugins/runner,` line. Match the existing comment style:

```python
        # Script runner: lightweight script execution on local/remote hosts.
        "//termlab/plugins/runner",
        # File search: filename search over SFTP local + remote panes.
        "//termlab/plugins/search",
```

- [ ] **Step 4: Build to verify the full product compiles**

Run: `make termlab-build`
Expected: clean build.

- [ ] **Step 5: Commit**

```bash
git add plugins/search/resources/META-INF/plugin.xml \
        core/src/com/termlab/core/palette/TermLabTabsCustomizationStrategy.java \
        BUILD.bazel
git commit -m "feat(search): register File Search contributor + allowlist + runtime dep

plugin.xml registers the contributor factory + FileSearchFilter
service. Core allowlist gains TermLabFiles so the tab is visible
in Search Everywhere. Root BUILD.bazel runtime deps gain the new
plugin so it's loaded in 'make termlab'."
```

---

## Task 20: Manual smoke test pass

**Files:** none modified.

Walk through the manual smoke list from the spec, end to end, on a running TermLab. Capture any issues as follow-up tasks — do not patch inline unless the defect is a one-line fix.

- [ ] **Step 1: Launch TermLab**

Run: `make termlab`

- [ ] **Step 2: Verify SE Files tab with no SFTP open**

Open Search Everywhere (`Cmd+Shift+P` / `Ctrl+Shift+P`). Tab to "Files".
Expected: empty state message: *"Open an SFTP tool window to search files."*

- [ ] **Step 3: Verify local-only search**

Open SFTP tool window (local pane visible, remote not connected). Return to SE, Files tab, type a few characters.
Expected: status-bar indicator *"Indexing files in <home>"*, then results appear with local icon + "local" chip. Filter dot is not present on the funnel.

- [ ] **Step 4: Verify remote search after connect**

Connect the SFTP remote pane to a host. Re-open SE Files tab.
Expected: second status-bar indicator *"Indexing files on <host>"*, results interleave with "<hostname>" chips on rows from the host.

- [ ] **Step 5: Verify hybrid live fallback**

Create a file on the remote in a subdir (e.g. `ssh host touch /tmp/unusualnamexyz.txt`). Without navigating away, type `unusualname` in SE. The cache doesn't have it.
Expected: result appears as a live-fallback hit (may take 1-2s).

- [ ] **Step 6: Verify filter popup + invalidation**

Click the funnel icon in the Files tab. Toggle off "Exclude .git".
Expected: popup closes / stays as expected; funnel icon gains dot overlay; next query re-indexes and shows `.git` internals.

- [ ] **Step 7: Verify regex filter**

Open filter popup. Enter regex `\.(log|tmp)$`. Close.
Expected: `.log` and `.tmp` results disappear from subsequent queries. No cache rebuild (fast).

- [ ] **Step 8: Verify regex invalid**

Open filter popup. Enter `[unterminated`.
Expected: inline red error *"Regex invalid: …"*. Files tab still usable — invalid regex is not applied.

- [ ] **Step 9: Verify Enter on local hit**

Select a local result, press Enter.
Expected: file opens in the TermLab light editor.

- [ ] **Step 10: Verify Enter on remote hit**

Select a remote result, press Enter.
Expected: file opens in light editor via `sftp://` VFS.

- [ ] **Step 11: Verify disconnect handling**

Disconnect the remote pane while SE is open, then re-query.
Expected: remote results disappear; local still searchable.

- [ ] **Step 12: Verify cwd-change invalidation**

Navigate local SFTP pane to a different directory. Re-open SE Files tab.
Expected: indicator fires again for the new root; results now come from the new directory.

- [ ] **Step 13: Verify tool-fallback balloon**

On a host without `rg` or `fd` (test by SSH-ing and confirming `command -v rg` returns blank), re-open SE Files tab.
Expected: balloon *"Using find for file search on \<host\>. Install ripgrep…"* with "Don't show again for this host". Click dismiss. Re-open SE — no balloon.

- [ ] **Step 14: Verify status-bar cancel**

Point the SFTP remote pane at a large home. Open SE Files tab to trigger a build. While indexing, click the X on the status-bar indicator.
Expected: build aborts, cache returns to EMPTY, no crash. Re-opening SE kicks off a fresh build.

- [ ] **Step 15: Verify 200k overflow**

Point the remote pane at `/` on a host with a big filesystem. Open SE Files tab.
Expected: listing truncates at 200k; empty-state / warning message *"Listing truncated at 200k — narrow the SFTP root to search more."* appears when no query matches. No OOM.

- [ ] **Step 16: Commit the smoke run as closed**

If no defects:

```bash
git commit --allow-empty -m "chore(search): manual smoke pass — spec sections 1-6 covered

Tested golden path + filter popup + fallback balloon + cancel +
truncation. No defects found. File Search feature ready for use."
```

If defects found: do not commit; raise each as a follow-up.

---

## Self-Review

(Run this against the spec before considering the plan done.)

**Spec coverage checklist:**

- Architecture: 5 search-plugin classes + 1 SFTP-plugin class covered by Tasks 1-13.
- Tool probing local (bundled rg → PATH rg → fd → find / WALK): Task 8.
- Tool probing remote (`command -v rg || fd || find`): Task 10.
- Command construction per tool: Task 7.
- Cache state machine + 200k cap + invalidation: Tasks 2, 3.
- Hybrid live fallback (<3 results, ≥3 query chars): Task 15.
- Filter state + chips + regex validation + tool-specific flags: Tasks 4, 5, 6.
- Filter popup UI with chips + custom globs + regex + reset + refresh: Task 16.
- Single Files SE tab, sortWeight 40, groupName "Files", id TermLabFiles: Task 13.
- Status bar progress with cancel-to-EMPTY: Task 11; wired in Tasks 14, 18.
- Balloon on tool fallback with per-host suppression: Task 18.
- Result renderer with local / hostname chip: Task 12.
- Enter opens local via LocalFileOpener, remote via RemoteFileOpener: Task 17.
- Allowlist update + plugin.xml registration + root BUILD.bazel: Task 19.
- Manual smoke of all spec behaviors: Task 20.

**Potentially missing:**

- Empty-state text for the six spec conditions (no SFTP, building, failed, truncated, no matches, regex invalid). The plan surfaces a couple of these inline but doesn't emit dedicated empty-state rows. If the platform's default "No results" is acceptable, leave as-is; otherwise add a follow-up to render explicit rows via a sentinel `FileHit`. Logged as a manual-smoke observation (Task 20 Step 15).
- Time-based cache invalidation (spec explicitly out-of-scope).

**Placeholder scan:** no "TBD"/"TODO" left in the plan. Every step has concrete code.

**Type consistency:**

- `FileListCache.Tool` (in SFTP plugin) is the single source of truth; `FileLister.LocalProbeResult.tool()` / `RemoteProbeResult.tool()` / `FileListCache.tool()` all use it.
- `FileHit.Side` enum — used consistently.
- `probeLocalDefault()` names match across `runLocalBuild` (Task 14) and `runLiveFallback` (Task 15).
- `runRemoteBuild` signature grew an extra `hostIdKey`/`hostDisplay` parameter in Task 18; the Task 14 definition and the Task 18 caller update are aligned.
- `currentHostOrNull()` / `activeSftpSessionOrNull()` / `currentHostLabel()` accessors are added in Task 14 (and one more in Task 17). All consumers appear after their definitions.
