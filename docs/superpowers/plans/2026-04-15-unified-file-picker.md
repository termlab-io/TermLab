# Unified File Picker Dialog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a custom Swing file picker dialog owned by `core/` with a pluggable `FileSource` interface. Replace the broken `FileChooser.chooseFile` call in `SaveScratchToRemoteAction` with this dialog. Extract the existing `RemoteFilePane`/`LocalFilePane` table logic into a shared `FileBrowserTable` widget in core so the table implementation lives in exactly one place.

**Architecture:** Core owns the dialog (`UnifiedFilePickerDialog`), the `FileSource` interface, the `FileSourceProvider` extension point, the built-in `LocalFileSource`, and the extracted `FileBrowserTable` widget. The SFTP plugin registers `SftpFileSourceProvider` which returns one `SftpFileSource` per configured host. The editor plugin calls `UnifiedFilePickerDialog.showSaveDialog(...)` from `SaveScratchToRemoteAction`. Core has no SFTP dependency.

**Tech Stack:** Java 21, Bazel, IntelliJ Platform (`DialogWrapper`, `JBTable`, `ProgressManager.Task.Modal`), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-04-15-unified-file-picker-design.md`

---

## Orientation for the Implementing Engineer

Read these files before starting:

- `docs/superpowers/specs/2026-04-15-unified-file-picker-design.md` — the design doc for this feature
- `plugins/sftp/src/com/termlab/sftp/toolwindow/FileTableModel.java` — the existing 4-column `AbstractTableModel` (Name, Size, Modified, Permissions) that will move to core
- `plugins/sftp/src/com/termlab/sftp/model/FileEntry.java` — the existing interface that will move to core
- `plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java` — existing pane that will refactor to use the new shared widget
- `plugins/sftp/src/com/termlab/sftp/toolwindow/LocalFilePane.java` — same
- `plugins/sftp/src/com/termlab/sftp/vfs/SftpVirtualFile.java` — contains the `writeAtomically(byte[])` helper that will extract into `AtomicSftpWrite`
- `plugins/sftp/src/com/termlab/sftp/session/SftpSessionManager.java` — session management used by `SftpFileSource`
- `plugins/sftp/src/com/termlab/sftp/vfs/SftpUrl.java` — URL format used for the post-save tab transition in `SaveScratchToRemoteAction`
- `plugins/editor/src/com/termlab/editor/scratch/SaveScratchToRemoteAction.java` — the immediate consumer that will shrink significantly
- `plugins/vault/BUILD.bazel` + `plugins/vault/test/com/termlab/vault/TestRunner.java` — template for the new `core_test_runner`

**Build commands** (run from `/Users/dustin/projects/intellij-community/`):

- Build core: `bash bazel.cmd build //termlab/core:core`
- Build sftp: `bash bazel.cmd build //termlab/plugins/sftp:sftp`
- Build editor: `bash bazel.cmd build //termlab/plugins/editor:editor`
- Build product: `bash bazel.cmd build //termlab:termlab_run`
- Run core tests: `bash bazel.cmd run //termlab/core:core_test_runner` (target created in Task 1)
- Run sftp tests: `bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner`

**Commit convention:** lowercase conventional-commit prefixes (`feat(core):`, `feat(sftp):`, `refactor(core):`, etc.). Each task ends with an explicit commit step.

**Order matters:** tasks must be done in order. Later tasks depend on types/classes introduced by earlier ones, and some tasks intentionally leave the build broken for one task (the move+refactor sequence).

**Pre-existing SFTP test runner:** already exists (added in the SFTP VFS plan). `sftp_test_runner` runs 20+ tests today; this plan's Task 9 adds more.

---

## Task 1: Add `core` plugin test infrastructure

The `core` plugin doesn't currently have a JUnit 5 test runner. Tasks 4 and 5 need one.

**Files:**
- Modify: `core/BUILD.bazel`
- Create: `core/test/com/termlab/core/TestRunner.java`

- [ ] **Step 1: Add test targets to `core/BUILD.bazel`**

Read the current `core/BUILD.bazel` file. It contains a `jvm_library` called `core` that globs `src/**/*.java`. Add (after the existing jvm_library, modeled on `plugins/vault/BUILD.bazel`):

```bazel
jvm_library(
    name = "core_test_lib",
    module_name = "intellij.termlab.core.tests",
    visibility = ["//visibility:public"],
    srcs = glob(["test/**/*.java"], allow_empty = True),
    deps = [
        ":core",
        "//termlab/sdk",
        "//libraries/junit5",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
        "@lib//:jetbrains-annotations",
        "@lib//:kotlin-stdlib",
    ],
)

java_binary(
    name = "core_test_runner",
    main_class = "com.termlab.core.TestRunner",
    runtime_deps = [
        ":core_test_lib",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
    ],
)
```

Also add `load("@rules_java//java:defs.bzl", "java_binary")` near the top of `core/BUILD.bazel` if it's not already there.

- [ ] **Step 2: Create the test runner**

`core/test/com/termlab/core/TestRunner.java`:

```java
package com.termlab.core;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

/**
 * Standalone JUnit 5 runner for the core plugin's unit tests.
 *
 * <pre>
 *   bash bazel.cmd run //termlab/core:core_test_runner
 * </pre>
 */
public final class TestRunner {

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("com.termlab.core"))
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

- [ ] **Step 3: Build the test library**

Run: `bash bazel.cmd build //termlab/core:core_test_lib`
Expected: `Build completed successfully`.

- [ ] **Step 4: Run the empty test runner**

Run: `bash bazel.cmd run //termlab/core:core_test_runner`
Expected: summary showing `0 tests found`, exit code 0.

- [ ] **Step 5: Commit**

```bash
git add core/BUILD.bazel core/test/com/termlab/core/TestRunner.java
git commit -m "feat(core): add JUnit 5 test runner target for core plugin"
```

---

## Task 2: Move `FileEntry` interface from SFTP to core

The `FileEntry` interface is platform-agnostic and needs to be in core so `FileSource` can reference it. The existing `LocalFileEntry` and `RemoteFileEntry` records (in `plugins/sftp/src/com/termlab/sftp/model/`) keep their package; only the interface moves and they update their `implements` clause.

**Files:**
- Create: `core/src/com/termlab/core/filepicker/FileEntry.java`
- Delete: `plugins/sftp/src/com/termlab/sftp/model/FileEntry.java`
- Modify: `plugins/sftp/src/com/termlab/sftp/model/LocalFileEntry.java`
- Modify: `plugins/sftp/src/com/termlab/sftp/model/RemoteFileEntry.java`
- Modify: `plugins/sftp/src/com/termlab/sftp/toolwindow/FileTableModel.java`

- [ ] **Step 1: Create the new interface in core**

`core/src/com/termlab/core/filepicker/FileEntry.java`:

```java
package com.termlab.core.filepicker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Platform-agnostic view of a single directory entry. Used by the
 * unified file picker and by the SFTP tool window panes. Implementations
 * include {@code com.termlab.sftp.model.LocalFileEntry} (backed by
 * {@code java.nio.file.Path}) and {@code com.termlab.sftp.model.RemoteFileEntry}
 * (backed by Apache SSHD SFTP).
 */
public interface FileEntry {

    @NotNull String name();

    long size();

    @Nullable Instant modified();

    boolean isDirectory();

    boolean isSymlink();

    /** POSIX permissions string, e.g. "rwxr-xr--", or empty if unknown. */
    @NotNull String permissions();
}
```

- [ ] **Step 2: Delete the old interface**

```bash
rm plugins/sftp/src/com/termlab/sftp/model/FileEntry.java
```

- [ ] **Step 3: Update `LocalFileEntry.java` imports**

Read `plugins/sftp/src/com/termlab/sftp/model/LocalFileEntry.java`. Find the `implements FileEntry` clause (the interface is currently in the same package, so no import is needed). After the move, `FileEntry` lives in `com.termlab.core.filepicker`. Add the import and leave the `implements FileEntry` clause as-is.

Add this import to the file's import block:

```java
import com.termlab.core.filepicker.FileEntry;
```

- [ ] **Step 4: Update `RemoteFileEntry.java` imports**

Same change to `plugins/sftp/src/com/termlab/sftp/model/RemoteFileEntry.java`:

```java
import com.termlab.core.filepicker.FileEntry;
```

- [ ] **Step 5: Update `FileTableModel.java` imports**

`plugins/sftp/src/com/termlab/sftp/toolwindow/FileTableModel.java` imports `com.termlab.sftp.model.FileEntry`. Change that line to:

```java
import com.termlab.core.filepicker.FileEntry;
```

- [ ] **Step 6: Grep for any other references**

Run:
```
cd /Users/dustin/projects/termlab_workbench && grep -rn "com.termlab.sftp.model.FileEntry" --include="*.java"
```

Expected: no matches. If any match appears, update that file's import the same way.

- [ ] **Step 7: Verify the SFTP plugin depends on core**

Read `plugins/sftp/BUILD.bazel`. Confirm `//termlab/core` is in the `sftp` jvm_library's `deps`. It should already be there (the SFTP plugin already depends on core for many things). If not, add it.

- [ ] **Step 8: Build**

Run: `bash bazel.cmd build //termlab/plugins/sftp:sftp //termlab/core:core`
Expected: both build successfully.

- [ ] **Step 9: Run existing sftp tests**

Run: `bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner`
Expected: all 20+ tests still pass.

- [ ] **Step 10: Commit**

```bash
git add core/src/com/termlab/core/filepicker/FileEntry.java plugins/sftp/src/com/termlab/sftp/model/LocalFileEntry.java plugins/sftp/src/com/termlab/sftp/model/RemoteFileEntry.java plugins/sftp/src/com/termlab/sftp/toolwindow/FileTableModel.java
git commit -m "refactor(core): move FileEntry interface to com.termlab.core.filepicker"
```

(The deletion of `plugins/sftp/src/com/termlab/sftp/model/FileEntry.java` is staged by `git add` on the renamed location — verify with `git status` before committing.)

---

## Task 3: Create the `FileSource` interface

Pluggable data source for the picker. Declared in core, has no implementation yet.

**Files:**
- Create: `core/src/com/termlab/core/filepicker/FileSource.java`
- Create: `core/src/com/termlab/core/filepicker/FilePickerResult.java`

- [ ] **Step 1: Create `FileSource.java`**

`core/src/com/termlab/core/filepicker/FileSource.java`:

```java
package com.termlab.core.filepicker;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * A backing source for the unified file picker. Each source represents
 * one navigable root (a local filesystem, one SFTP host, a cloud bucket,
 * etc.). Sources are contributed via the
 * {@code com.termlab.core.fileSourceProvider} extension point.
 */
public interface FileSource {

    /** Human-readable label shown in the picker's source dropdown. */
    @NotNull String label();

    /** Icon shown next to the label in the source dropdown. */
    @NotNull Icon icon();

    /**
     * Stable identifier for this source. Used to persist the
     * "last-used source" preference and deduplicate sources with
     * the same label. For SFTP, this is {@code "sftp:" + host.id()}.
     * For the built-in local source, this is the literal {@code "local"}.
     */
    @NotNull String id();

    /**
     * The path the picker should open at when this source is first
     * selected. Typically the user's home directory for local, or
     * the remote home for SFTP.
     *
     * <p>Must only be called AFTER {@link #open} has completed
     * successfully. Implementations that need a live session (SFTP)
     * rely on this ordering.
     */
    @NotNull String initialPath();

    /**
     * Ensure the source is ready for listing operations. For local
     * this is a no-op; for SFTP this acquires the session via
     * {@code SftpSessionManager}. Called on a background thread by
     * the dialog under modal progress. Throws if the source cannot
     * be brought online.
     *
     * @param owner reference-count owner for any underlying resources
     *              (sessions). The dialog passes its own identity;
     *              the source releases at dialog close.
     */
    void open(@NotNull Project project, @NotNull Object owner) throws IOException;

    /** Release any resources acquired by {@link #open}. */
    void close(@NotNull Object owner);

    /**
     * List the directory at {@code absolutePath}. Returns entries in
     * no particular order; the dialog sorts for display. Must NOT
     * include "." or "..".
     */
    @NotNull List<FileEntry> list(@NotNull String absolutePath) throws IOException;

    /** True if the path exists and is a directory. */
    boolean isDirectory(@NotNull String absolutePath) throws IOException;

    /** True if the path exists (file OR directory). */
    boolean exists(@NotNull String absolutePath) throws IOException;

    /**
     * The parent path of {@code absolutePath}, or null if it's
     * already at the source's top-level.
     */
    @Nullable String parentOf(@NotNull String absolutePath);

    /**
     * Join a directory path and a child name into a new absolute path.
     */
    @NotNull String resolve(@NotNull String directoryPath, @NotNull String childName);

    /**
     * Write bytes to {@code absolutePath}, creating or overwriting.
     * Implementations handle atomic writes internally: SFTP uses
     * .tmp+rename, local uses Files.write with CREATE+TRUNCATE.
     */
    void writeFile(@NotNull String absolutePath, byte @NotNull [] content) throws IOException;

    /** Read bytes at {@code absolutePath}. Used by the Open flow. */
    @NotNull InputStream readFile(@NotNull String absolutePath) throws IOException;
}
```

- [ ] **Step 2: Create `FilePickerResult.java`**

`core/src/com/termlab/core/filepicker/FilePickerResult.java`:

```java
package com.termlab.core.filepicker;

import org.jetbrains.annotations.NotNull;

/**
 * Return value of {@code UnifiedFilePickerDialog.showSaveDialog} and
 * {@code showOpenDialog}. The caller uses
 * {@code result.source().writeFile(result.absolutePath(), bytes)} for
 * save flows and {@code result.source().readFile(result.absolutePath())}
 * for open flows.
 */
public record FilePickerResult(
    @NotNull FileSource source,
    @NotNull String absolutePath
) {}
```

- [ ] **Step 3: Build core**

Run: `bash bazel.cmd build //termlab/core:core`
Expected: `Build completed successfully`.

- [ ] **Step 4: Commit**

```bash
git add core/src/com/termlab/core/filepicker/FileSource.java core/src/com/termlab/core/filepicker/FilePickerResult.java
git commit -m "feat(core): FileSource interface and FilePickerResult record"
```

---

## Task 4: Create `FileSourceProvider` extension point

Providers contribute lists of sources to the picker. The core plugin declares the extension point; sources are registered by their respective plugins.

**Files:**
- Create: `core/src/com/termlab/core/filepicker/FileSourceProvider.java`
- Modify: `core/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create the interface**

`core/src/com/termlab/core/filepicker/FileSourceProvider.java`:

```java
package com.termlab.core.filepicker;

import com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Extension point interface that contributes file sources to the
 * unified file picker. Providers may return a dynamic list of
 * sources (e.g., one source per configured SFTP host). The dialog
 * flat-maps {@code listSources()} across every registered provider
 * to build its source dropdown.
 */
public interface FileSourceProvider {

    ExtensionPointName<FileSourceProvider> EP_NAME =
        ExtensionPointName.create("com.termlab.core.fileSourceProvider");

    @NotNull List<FileSource> listSources();
}
```

- [ ] **Step 2: Declare the extension point in core plugin.xml**

Read `core/resources/META-INF/plugin.xml`. Find the existing `<extensionPoints>` block (if one exists). If it does, add a new `<extensionPoint>` child:

```xml
        <extensionPoint name="fileSourceProvider"
                        interface="com.termlab.core.filepicker.FileSourceProvider"
                        dynamic="true"/>
```

If there is no existing `<extensionPoints>` block, add one immediately after the `<depends>` entries (before `<extensions>`):

```xml
    <extensionPoints>
        <extensionPoint name="fileSourceProvider"
                        interface="com.termlab.core.filepicker.FileSourceProvider"
                        dynamic="true"/>
    </extensionPoints>
```

- [ ] **Step 3: Build core**

Run: `bash bazel.cmd build //termlab/core:core`
Expected: `Build completed successfully`.

- [ ] **Step 4: Commit**

```bash
git add core/src/com/termlab/core/filepicker/FileSourceProvider.java core/resources/META-INF/plugin.xml
git commit -m "feat(core): FileSourceProvider extension point"
```

---

## Task 5: Create `LocalFileSource` + `LocalFileSourceProvider` (TDD)

The built-in local source. Simple `java.nio.file.Files`-backed implementation, fully unit-testable.

**Files:**
- Create: `core/src/com/termlab/core/filepicker/LocalFileEntry.java` — tiny record implementing `FileEntry` for `Path` objects. We can't reuse `com.termlab.sftp.model.LocalFileEntry` because core must not depend on sftp.
- Create: `core/src/com/termlab/core/filepicker/LocalFileSource.java`
- Create: `core/src/com/termlab/core/filepicker/LocalFileSourceProvider.java`
- Create: `core/test/com/termlab/core/filepicker/LocalFileSourceTest.java`
- Modify: `core/resources/META-INF/plugin.xml` — register the provider

- [ ] **Step 1: Create `LocalFileEntry.java`** (core's own entry type, separate from the SFTP plugin's `com.termlab.sftp.model.LocalFileEntry`)

`core/src/com/termlab/core/filepicker/LocalFileEntry.java`:

```java
package com.termlab.core.filepicker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

record LocalFileEntry(
    @NotNull String name,
    long size,
    @Nullable Instant modified,
    boolean isDirectory,
    boolean isSymlink,
    @NotNull String permissions
) implements FileEntry {

    static @NotNull LocalFileEntry of(@NotNull Path path) throws IOException {
        BasicFileAttributes basic = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String perms = "";
        try {
            PosixFileAttributes posix = Files.readAttributes(
                path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            perms = PosixFilePermissions.toString(posix.permissions());
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (Windows). Leave permissions empty.
        }
        String fileName = path.getFileName() == null
            ? path.toString()
            : path.getFileName().toString();
        return new LocalFileEntry(
            fileName,
            basic.size(),
            Instant.ofEpochMilli(basic.lastModifiedTime().toMillis()),
            basic.isDirectory(),
            basic.isSymbolicLink(),
            perms);
    }
}
```

Note: package-private (no `public` keyword on class or method) — this is an internal detail of `LocalFileSource`.

- [ ] **Step 2: Write the failing test for `LocalFileSource`**

`core/test/com/termlab/core/filepicker/LocalFileSourceTest.java`:

```java
package com.termlab.core.filepicker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileSourceTest {

    @Test
    void idIsLocal() {
        assertEquals("local", new LocalFileSource().id());
    }

    @Test
    void labelIsLocal() {
        assertEquals("Local", new LocalFileSource().label());
    }

    @Test
    void listReturnsEntriesInDirectory(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("subdir"));
        Files.writeString(tmp.resolve("a.txt"), "hello");
        Files.writeString(tmp.resolve("b.txt"), "world");

        LocalFileSource source = new LocalFileSource();
        List<FileEntry> entries = source.list(tmp.toString());

        List<String> names = entries.stream().map(FileEntry::name).sorted().collect(Collectors.toList());
        assertEquals(List.of("a.txt", "b.txt", "subdir"), names);
    }

    @Test
    void listOfMissingPathThrows(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist");
        LocalFileSource source = new LocalFileSource();
        assertThrows(IOException.class, () -> source.list(missing.toString()));
    }

    @Test
    void isDirectoryTrueForDirectories(@TempDir Path tmp) {
        LocalFileSource source = new LocalFileSource();
        assertTrue(source.isDirectory(tmp.toString()));
    }

    @Test
    void isDirectoryFalseForFiles(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("x.txt");
        Files.writeString(file, "content");
        LocalFileSource source = new LocalFileSource();
        assertFalse(source.isDirectory(file.toString()));
    }

    @Test
    void isDirectoryFalseForMissing(@TempDir Path tmp) {
        LocalFileSource source = new LocalFileSource();
        assertFalse(source.isDirectory(tmp.resolve("missing").toString()));
    }

    @Test
    void existsTrueForDirectoriesAndFiles(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("x.txt");
        Files.writeString(file, "content");
        LocalFileSource source = new LocalFileSource();
        assertTrue(source.exists(tmp.toString()));
        assertTrue(source.exists(file.toString()));
    }

    @Test
    void existsFalseForMissing(@TempDir Path tmp) {
        LocalFileSource source = new LocalFileSource();
        assertFalse(source.exists(tmp.resolve("missing").toString()));
    }

    @Test
    void parentOfRootReturnsNull() {
        LocalFileSource source = new LocalFileSource();
        assertNull(source.parentOf("/"));
    }

    @Test
    void parentOfNestedPath() {
        LocalFileSource source = new LocalFileSource();
        assertEquals("/a", source.parentOf("/a/b"));
        assertEquals("/a/b", source.parentOf("/a/b/c"));
    }

    @Test
    void resolveJoinsPaths() {
        LocalFileSource source = new LocalFileSource();
        assertEquals("/a/b", source.resolve("/a", "b"));
        assertEquals("/a/b/c.txt", source.resolve("/a/b", "c.txt"));
    }

    @Test
    void writeFileCreatesNewFile(@TempDir Path tmp) throws IOException {
        LocalFileSource source = new LocalFileSource();
        Path target = tmp.resolve("new.txt");
        source.writeFile(target.toString(), "hello".getBytes());
        assertEquals("hello", Files.readString(target));
    }

    @Test
    void writeFileOverwritesExisting(@TempDir Path tmp) throws IOException {
        LocalFileSource source = new LocalFileSource();
        Path target = tmp.resolve("x.txt");
        Files.writeString(target, "old");
        source.writeFile(target.toString(), "new".getBytes());
        assertEquals("new", Files.readString(target));
    }

    @Test
    void readFileReturnsContent(@TempDir Path tmp) throws IOException {
        LocalFileSource source = new LocalFileSource();
        Path target = tmp.resolve("x.txt");
        Files.writeString(target, "content");
        try (InputStream in = source.readFile(target.toString())) {
            assertEquals("content", new String(in.readAllBytes()));
        }
    }
}
```

- [ ] **Step 3: Run tests, confirm red**

Run: `bash bazel.cmd run //termlab/core:core_test_runner`
Expected: compile failure ("cannot find symbol: class LocalFileSource").

- [ ] **Step 4: Implement `LocalFileSource.java`**

`core/src/com/termlab/core/filepicker/LocalFileSource.java`:

```java
package com.termlab.core.filepicker;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Built-in {@link FileSource} for the local filesystem via
 * {@link java.nio.file.Files}. Always registered via
 * {@link LocalFileSourceProvider}.
 */
public final class LocalFileSource implements FileSource {

    @Override
    public @NotNull String id() {
        return "local";
    }

    @Override
    public @NotNull String label() {
        return "Local";
    }

    @Override
    public @NotNull Icon icon() {
        return AllIcons.Nodes.HomeFolder;
    }

    @Override
    public @NotNull String initialPath() {
        return System.getProperty("user.home");
    }

    @Override
    public void open(@NotNull Project project, @NotNull Object owner) {
        // No-op for local.
    }

    @Override
    public void close(@NotNull Object owner) {
        // No-op for local.
    }

    @Override
    public @NotNull List<FileEntry> list(@NotNull String absolutePath) throws IOException {
        Path dir = Paths.get(absolutePath);
        List<FileEntry> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                try {
                    result.add(LocalFileEntry.of(it.next()));
                } catch (IOException ignored) {
                    // Skip unreadable entries but keep listing.
                }
            }
        }
        return result;
    }

    @Override
    public boolean isDirectory(@NotNull String absolutePath) {
        return Files.isDirectory(Paths.get(absolutePath));
    }

    @Override
    public boolean exists(@NotNull String absolutePath) {
        return Files.exists(Paths.get(absolutePath));
    }

    @Override
    public @Nullable String parentOf(@NotNull String absolutePath) {
        Path parent = Paths.get(absolutePath).getParent();
        return parent == null ? null : parent.toString();
    }

    @Override
    public @NotNull String resolve(@NotNull String directoryPath, @NotNull String childName) {
        return Paths.get(directoryPath).resolve(childName).toString();
    }

    @Override
    public void writeFile(@NotNull String absolutePath, byte @NotNull [] content) throws IOException {
        Files.write(Paths.get(absolutePath), content,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public @NotNull InputStream readFile(@NotNull String absolutePath) throws IOException {
        return Files.newInputStream(Paths.get(absolutePath));
    }
}
```

- [ ] **Step 5: Run tests, confirm green**

Run: `bash bazel.cmd run //termlab/core:core_test_runner`
Expected: all 14 tests pass.

- [ ] **Step 6: Create `LocalFileSourceProvider.java`**

`core/src/com/termlab/core/filepicker/LocalFileSourceProvider.java`:

```java
package com.termlab.core.filepicker;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Built-in provider that always contributes the {@link LocalFileSource}
 * to the unified file picker.
 */
public final class LocalFileSourceProvider implements FileSourceProvider {

    private static final LocalFileSource SINGLETON = new LocalFileSource();

    @Override
    public @NotNull List<FileSource> listSources() {
        return List.of(SINGLETON);
    }
}
```

- [ ] **Step 7: Register the provider in `core/resources/META-INF/plugin.xml`**

Inside the `<extensions defaultExtensionNs="com.intellij">` block (or add a new `<extensions defaultExtensionNs="com.termlab.core">` block if needed — the extension point we declared uses the `com.termlab.core` namespace), register the local provider:

```xml
    <extensions defaultExtensionNs="com.termlab.core">
        <fileSourceProvider implementation="com.termlab.core.filepicker.LocalFileSourceProvider"/>
    </extensions>
```

- [ ] **Step 8: Build and run tests**

Run: `bash bazel.cmd build //termlab/core:core`
Expected: `Build completed successfully`.

Run: `bash bazel.cmd run //termlab/core:core_test_runner`
Expected: 14 tests successful.

- [ ] **Step 9: Commit**

```bash
git add core/src/com/termlab/core/filepicker/LocalFileEntry.java core/src/com/termlab/core/filepicker/LocalFileSource.java core/src/com/termlab/core/filepicker/LocalFileSourceProvider.java core/test/com/termlab/core/filepicker/LocalFileSourceTest.java core/resources/META-INF/plugin.xml
git commit -m "feat(core): LocalFileSource and LocalFileSourceProvider"
```

---

## Task 6: Move `FileTableModel` and cell renderers to core

Relocate the table model and its three cell renderers (`FileNameCellRenderer`, `SizeCellRenderer`, `ModifiedCellRenderer`) from `plugins/sftp/src/com/termlab/sftp/toolwindow/` to `core/src/com/termlab/core/filepicker/ui/`. Update imports in the existing panes. No behavior change.

**Files:**
- Create: `core/src/com/termlab/core/filepicker/ui/FileTableModel.java` (moved from sftp)
- Create: `core/src/com/termlab/core/filepicker/ui/FileNameCellRenderer.java` (moved)
- Create: `core/src/com/termlab/core/filepicker/ui/SizeCellRenderer.java` (moved)
- Create: `core/src/com/termlab/core/filepicker/ui/ModifiedCellRenderer.java` (moved)
- Delete: `plugins/sftp/src/com/termlab/sftp/toolwindow/FileTableModel.java`
- Delete: `plugins/sftp/src/com/termlab/sftp/toolwindow/FileNameCellRenderer.java`
- Delete: `plugins/sftp/src/com/termlab/sftp/toolwindow/SizeCellRenderer.java`
- Delete: `plugins/sftp/src/com/termlab/sftp/toolwindow/ModifiedCellRenderer.java`
- Modify: `plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java` — update imports
- Modify: `plugins/sftp/src/com/termlab/sftp/toolwindow/LocalFilePane.java` — update imports

- [ ] **Step 1: Read the four files to move**

Read:
- `plugins/sftp/src/com/termlab/sftp/toolwindow/FileTableModel.java`
- `plugins/sftp/src/com/termlab/sftp/toolwindow/FileNameCellRenderer.java`
- `plugins/sftp/src/com/termlab/sftp/toolwindow/SizeCellRenderer.java`
- `plugins/sftp/src/com/termlab/sftp/toolwindow/ModifiedCellRenderer.java`

- [ ] **Step 2: Copy each file to its new location with an updated package declaration**

Create the four new files under `core/src/com/termlab/core/filepicker/ui/` with identical content EXCEPT:
- Change the package declaration from `package com.termlab.sftp.toolwindow;` to `package com.termlab.core.filepicker.ui;`
- If `FileTableModel.java` imports `com.termlab.core.filepicker.FileEntry`, keep that import (added in Task 2). If it still shows `com.termlab.sftp.model.FileEntry`, update it.
- The three renderers import `FileEntry` — same update.

- [ ] **Step 3: Delete the old files**

```bash
rm plugins/sftp/src/com/termlab/sftp/toolwindow/FileTableModel.java
rm plugins/sftp/src/com/termlab/sftp/toolwindow/FileNameCellRenderer.java
rm plugins/sftp/src/com/termlab/sftp/toolwindow/SizeCellRenderer.java
rm plugins/sftp/src/com/termlab/sftp/toolwindow/ModifiedCellRenderer.java
```

- [ ] **Step 4: Update imports in `RemoteFilePane.java`**

Find the imports in `plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java` that reference the moved classes:
- `import com.termlab.sftp.toolwindow.FileTableModel;` — doesn't exist as an import because they're in the same package. After the move, add `import com.termlab.core.filepicker.ui.FileTableModel;` to the imports block.
- Same for `FileNameCellRenderer`, `SizeCellRenderer`, `ModifiedCellRenderer`.

Also grep for bare references to these class names in the source that don't need an explicit import (same-package references); each becomes an explicit import after the move. Update them all.

- [ ] **Step 5: Update imports in `LocalFilePane.java`**

Same changes to `plugins/sftp/src/com/termlab/sftp/toolwindow/LocalFilePane.java`.

- [ ] **Step 6: Grep for any other references**

Run:
```
cd /Users/dustin/projects/termlab_workbench && grep -rn "com.termlab.sftp.toolwindow.FileTableModel\|com.termlab.sftp.toolwindow.FileNameCellRenderer\|com.termlab.sftp.toolwindow.SizeCellRenderer\|com.termlab.sftp.toolwindow.ModifiedCellRenderer" --include="*.java"
```

Expected: no matches.

- [ ] **Step 7: Build**

Run: `bash bazel.cmd build //termlab/core:core //termlab/plugins/sftp:sftp`
Expected: both build successfully.

- [ ] **Step 8: Run tests**

Run: `bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner`
Expected: 20+ tests still pass.

- [ ] **Step 9: Commit**

```bash
git add core/src/com/termlab/core/filepicker/ui/ plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java plugins/sftp/src/com/termlab/sftp/toolwindow/LocalFilePane.java
git commit -m "refactor(core): move FileTableModel and cell renderers to core.filepicker.ui"
```

(The `git add` of the new core location stages the renames; the deletions are included implicitly.)

---

## Task 7: Create the `FileBrowserTable` widget

Encapsulate the `JBTable` + `FileTableModel` + cell renderers into a single `FileBrowserTable` class that both the dialog and the existing panes can embed. This task creates the widget but does NOT yet refactor the panes to use it — that's Task 8.

**Files:**
- Create: `core/src/com/termlab/core/filepicker/ui/FileBrowserTable.java`

- [ ] **Step 1: Create the widget**

`core/src/com/termlab/core/filepicker/ui/FileBrowserTable.java`:

```java
package com.termlab.core.filepicker.ui;

import com.termlab.core.filepicker.FileEntry;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Reusable file browser widget: a sortable table with Name / Size /
 * Modified / Permissions columns backed by a {@link FileTableModel}.
 * Used by the unified file picker dialog and by the SFTP tool window
 * panes.
 *
 * <p>Consumers:
 * <ul>
 *   <li>{@link #setEntries} to populate the list</li>
 *   <li>{@link #getSelectedEntry} to read the current selection</li>
 *   <li>{@link #addDoubleClickListener} to handle row activation</li>
 *   <li>{@link #addSelectionListener} to react to selection changes</li>
 *   <li>{@link #getComponent} to embed the widget into a larger layout</li>
 * </ul>
 */
public final class FileBrowserTable {

    private final FileTableModel model = new FileTableModel();
    private final JBTable table = new JBTable(model);
    private final JScrollPane scrollPane;
    private final CopyOnWriteArrayList<Consumer<FileEntry>> doubleClickListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> selectionListeners = new CopyOnWriteArrayList<>();

    public FileBrowserTable() {
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_LAST_COLUMN);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(FileTableModel.COL_NAME)
            .setCellRenderer(new FileNameCellRenderer());
        table.getColumnModel().getColumn(FileTableModel.COL_SIZE)
            .setCellRenderer(new SizeCellRenderer());
        table.getColumnModel().getColumn(FileTableModel.COL_MODIFIED)
            .setCellRenderer(new ModifiedCellRenderer());

        TableRowSorter<FileTableModel> sorter = new TableRowSorter<>(model);
        sorter.setSortsOnUpdates(true);
        table.setRowSorter(sorter);

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    int viewRow = table.getSelectedRow();
                    FileEntry entry = entryAtViewRow(viewRow);
                    if (entry != null) {
                        for (Consumer<FileEntry> listener : doubleClickListeners) {
                            listener.accept(entry);
                        }
                    }
                }
            }
        });

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                for (Runnable listener : selectionListeners) {
                    listener.run();
                }
            }
        });

        scrollPane = new JScrollPane(table);
    }

    public void setEntries(@NotNull List<? extends FileEntry> entries) {
        model.setEntries(entries);
    }

    public @Nullable FileEntry getSelectedEntry() {
        return entryAtViewRow(table.getSelectedRow());
    }

    public void addDoubleClickListener(@NotNull Consumer<FileEntry> listener) {
        doubleClickListeners.add(listener);
    }

    public void addSelectionListener(@NotNull Runnable listener) {
        selectionListeners.add(listener);
    }

    /**
     * The embeddable component. Put this into your layout wherever you
     * want the table to appear.
     */
    public @NotNull JComponent getComponent() {
        return scrollPane;
    }

    /**
     * Direct access to the underlying {@link JBTable}. Used by the
     * existing SFTP tool window panes for DnD source/target setup.
     * New callers should prefer the higher-level API methods.
     */
    public @NotNull JBTable getTable() {
        return table;
    }

    private @Nullable FileEntry entryAtViewRow(int viewRow) {
        if (viewRow < 0) return null;
        int modelRow = table.convertRowIndexToModel(viewRow);
        return model.getEntryAt(modelRow);
    }
}
```

- [ ] **Step 2: Build**

Run: `bash bazel.cmd build //termlab/core:core`
Expected: `Build completed successfully`.

- [ ] **Step 3: Commit**

```bash
git add core/src/com/termlab/core/filepicker/ui/FileBrowserTable.java
git commit -m "feat(core): FileBrowserTable widget wrapping JBTable + FileTableModel"
```

---

## Task 8: Refactor `RemoteFilePane` and `LocalFilePane` to embed `FileBrowserTable`

This is the highest-risk task — it touches working tool-window code. The rewrite is mechanical: the panes keep their outer chrome (host dropdown, buttons, path field, DnD, context menus) but the internal `JBTable` setup is replaced with a `new FileBrowserTable(...)`. Behavior must be byte-identical to the pre-refactor state.

**Files:**
- Modify: `plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java`
- Modify: `plugins/sftp/src/com/termlab/sftp/toolwindow/LocalFilePane.java`

- [ ] **Step 1: Read the full `RemoteFilePane.java`**

Familiarize yourself with its current structure. Identify:
1. The field declarations for `model` (`FileTableModel`) and `table` (`JBTable`).
2. The constructor's table-setup block (column renderers, row sorter, selection mode, mouse listener for double-click).
3. The DnD `TransferHandler` setup, which calls `table.setDragEnabled(true)` and `table.setTransferHandler(...)`.
4. The mouse listener that triggers `onRowActivated(viewRow)` on double-click.
5. The context-menu mouse listener for `maybeShowPopup(MouseEvent)`.

- [ ] **Step 2: Replace the field declarations and constructor block in `RemoteFilePane.java`**

Find and replace the two field declarations:

```java
    private final FileTableModel model = new FileTableModel();
    private final JBTable table = new JBTable(model);
```

with:

```java
    private final FileBrowserTable browser = new FileBrowserTable();
```

In the constructor, replace the table setup block (column renderers, row sorter, selection model, mouse listeners for double-click and popup) with:

```java
        browser.addDoubleClickListener(entry -> onRowActivatedWithEntry(entry));
        browser.addSelectionListener(this::fireConnectionStateChanged);

        // Still need direct JBTable access for DnD, context menu, and
        // selection-on-popup-open.
        JBTable table = browser.getTable();
        table.setDragEnabled(true);
        table.setDropMode(DropMode.ON);
        table.setTransferHandler(new RemoteRowTransferHandler());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }
        });
```

Then in the layout, replace `add(new JScrollPane(table), BorderLayout.CENTER);` with `add(browser.getComponent(), BorderLayout.CENTER);`.

- [ ] **Step 3: Update the existing `onRowActivated(int viewRow)` method to take a `FileEntry`**

The current method takes a view-row index and does `table.convertRowIndexToModel(viewRow)` + `model.getEntryAt(modelRow)`. Since the widget hands us a `FileEntry` directly, add a new method `onRowActivatedWithEntry(FileEntry entry)` that contains the same business logic:

```java
    private void onRowActivatedWithEntry(@NotNull FileEntry entry) {
        if (activeSession == null || currentRemotePath == null) return;
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

Delete the old `onRowActivated(int viewRow)` method once nothing references it.

- [ ] **Step 4: Update any other table references**

Grep the file for remaining `table.` method calls. Update them to use `browser.getTable().` or, where possible, migrate to `browser`'s higher-level API. Common ones to check:
- `table.getSelectedRows()` → used by `selectedRemotePaths()` and the DnD transfer handler. Keep as `browser.getTable().getSelectedRows()`.
- `table.getSelectionModel()` → add a method `addSelectionListener(ListSelectionListener)` if needed, or use `browser.getTable().getSelectionModel()`.
- `table.convertRowIndexToModel(int)` → expose via `browser.getTable()`.

The `model` field in the old code was also used directly for `setEntries`, `getEntryAt`, etc. Replace `model.setEntries(snapshot)` with `browser.setEntries(snapshot)`; replace `model.getEntryAt(modelRow)` with `browser.getTable().convertRowIndexToModel(...)` + `browser.getSelectedEntry()` or similar, depending on context.

- [ ] **Step 5: Update `refreshHostPicker` and similar if they reference `model`**

Grep for `model.` in the file. The `model` reference is gone; usage needs to flow through `browser`. Most setEntries calls can become `browser.setEntries(list)`.

- [ ] **Step 6: Add missing import**

Add at the top of `RemoteFilePane.java`:

```java
import com.termlab.core.filepicker.ui.FileBrowserTable;
```

Remove now-unused imports (`JBTable`, `FileTableModel`, `FileNameCellRenderer`, `SizeCellRenderer`, `ModifiedCellRenderer`, `TableRowSorter`, `JScrollPane` — keep whichever the file still needs).

- [ ] **Step 7: Apply the same refactor to `LocalFilePane.java`**

Same pattern: replace `model` and `table` fields with `browser`, replace the constructor's table-setup block with `FileBrowserTable` setup, add `browser.addDoubleClickListener(...)`, update `onRowActivated` to take a `FileEntry`, update `setEntries` calls.

- [ ] **Step 8: Build**

Run: `bash bazel.cmd build //termlab/plugins/sftp:sftp`
Expected: `Build completed successfully`. If compile errors appear, they're almost certainly from stale references to `table` or `model` fields — grep the file for them and update.

- [ ] **Step 9: Run the existing sftp test suite**

Run: `bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner`
Expected: 20+ tests still pass.

- [ ] **Step 10: Build the whole product to catch cross-plugin regressions**

Run: `bash bazel.cmd build //termlab:termlab_run`
Expected: `Build completed successfully`.

- [ ] **Step 11: Commit**

```bash
git add plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java plugins/sftp/src/com/termlab/sftp/toolwindow/LocalFilePane.java
git commit -m "refactor(sftp): embed FileBrowserTable widget in SFTP tool window panes"
```

**IMPORTANT: manual smoke test required before proceeding.** Before moving on to Task 9, the user (or the executing engineer) should launch TermLab, open the SFTP tool window, connect to a host, browse directories, disconnect, and use context menus — the existing behavior must be identical. Flag any visual or interaction regression before landing further tasks.

---

## Task 9: Extract `AtomicSftpWrite` helper from `SftpVirtualFile`

Move the `.tmp`+rename atomic-write logic out of `SftpVirtualFile.writeAtomically(byte[])` into a standalone helper so `SftpFileSource.writeFile` can call it without duplicating code. Add unit tests.

**Files:**
- Create: `plugins/sftp/src/com/termlab/sftp/vfs/AtomicSftpWrite.java`
- Create: `plugins/sftp/test/com/termlab/sftp/vfs/AtomicSftpWriteTest.java`
- Modify: `plugins/sftp/src/com/termlab/sftp/vfs/SftpVirtualFile.java`

- [ ] **Step 1: Read the existing `writeAtomically` method in `SftpVirtualFile.java`**

The method body handles three paths: simple rename (POSIX), fallback backup+rename (non-POSIX), and restore-from-backup on failure. The extracted helper should preserve this logic exactly.

- [ ] **Step 2: Create `AtomicSftpWrite.java`**

`plugins/sftp/src/com/termlab/sftp/vfs/AtomicSftpWrite.java`:

```java
package com.termlab.sftp.vfs;

import com.intellij.openapi.diagnostic.Logger;
import org.apache.sshd.sftp.client.SftpClient;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Atomic write helper for SFTP: writes to a sibling temp file, then
 * renames into place (POSIX fast path). On non-POSIX servers that
 * reject rename-over-existing, falls back to a backup-then-rename
 * pattern that restores the original on failure.
 */
public final class AtomicSftpWrite {

    private static final Logger LOG = Logger.getInstance(AtomicSftpWrite.class);

    private AtomicSftpWrite() {}

    /**
     * Write {@code content} to {@code remotePath} atomically via the
     * given {@link SftpClient}. On POSIX servers, this is a
     * straightforward .tmp-file + rename. On non-POSIX servers that
     * reject rename-over-existing, falls back to a backup-then-rename
     * that restores the original if the final rename fails.
     *
     * @throws IOException if the write fails. On failure, any
     *   orphaned temp file is best-effort-cleaned up, and if a backup
     *   was made and the restore succeeded, the original file is
     *   preserved. If the restore itself fails, a CRITICAL log entry
     *   records the temp paths for manual recovery.
     */
    public static void writeAtomically(
        @NotNull SftpClient client,
        @NotNull String remotePath,
        byte @NotNull [] content
    ) throws IOException {
        String randomSuffix = Long.toHexString(ThreadLocalRandom.current().nextLong());
        String writeTmp = remotePath + "." + randomSuffix + ".tmp";
        String backupTmp = remotePath + "." + randomSuffix + ".bak";

        // Step 1: write the new content to a sibling temp file.
        try {
            try (OutputStream out = client.write(writeTmp)) {
                out.write(content);
            }
        } catch (IOException e) {
            try { client.remove(writeTmp); } catch (IOException ignored) {}
            throw e;
        }

        // Step 2: try the simple atomic rename. POSIX SFTP servers
        // succeed even if the target exists.
        try {
            client.rename(writeTmp, remotePath);
            return;
        } catch (IOException renameErr) {
            LOG.warn("Atomic rename failed for " + remotePath
                + " (" + renameErr.getMessage() + "), falling back to backup+rename");
        }

        // Step 3: fallback for non-POSIX servers.
        boolean backedUp = false;
        try {
            try {
                client.rename(remotePath, backupTmp);
                backedUp = true;
            } catch (IOException backupErr) {
                LOG.warn("Backup rename failed for " + remotePath
                    + " (" + backupErr.getMessage() + "); proceeding without backup");
            }
            try {
                client.rename(writeTmp, remotePath);
            } catch (IOException finalRenameErr) {
                if (backedUp) {
                    try {
                        client.rename(backupTmp, remotePath);
                    } catch (IOException restoreErr) {
                        LOG.error("CRITICAL: failed to restore " + remotePath
                            + " from backup " + backupTmp
                            + ". Original content is at " + backupTmp
                            + ". New content is at " + writeTmp, restoreErr);
                    }
                }
                throw finalRenameErr;
            }
            if (backedUp) {
                try { client.remove(backupTmp); } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            try { client.remove(writeTmp); } catch (IOException ignored) {}
            throw e;
        }
    }
}
```

- [ ] **Step 3: Update `SftpVirtualFile.writeAtomically(byte[])` to delegate**

In `plugins/sftp/src/com/termlab/sftp/vfs/SftpVirtualFile.java`, replace the body of `private void writeAtomically(byte[] content)` with a one-liner:

```java
    private void writeAtomically(byte @NotNull [] content) throws IOException {
        AtomicSftpWrite.writeAtomically(session.client(), remotePath, content);
    }
```

Remove any now-unused imports (e.g., `ThreadLocalRandom`, `OutputStream` imports in `SftpVirtualFile` if they were only used by the write helper).

- [ ] **Step 4: Write unit tests for `AtomicSftpWrite`**

`plugins/sftp/test/com/termlab/sftp/vfs/AtomicSftpWriteTest.java`:

```java
package com.termlab.sftp.vfs;

import org.apache.sshd.sftp.client.SftpClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicSftpWriteTest {

    /**
     * Minimal SftpClient test double that records rename/remove calls
     * and holds file content in a map. We only stub the four methods
     * AtomicSftpWrite actually uses: write, rename, remove.
     *
     * <p>SftpClient is an interface with ~50 methods; we use a
     * reflection Proxy so we only have to implement the four we care
     * about.
     */
    private static final class FakeClient {
        final Map<String, byte[]> files = new HashMap<>();
        final List<String> events = new ArrayList<>();

        /** Configures behavior for specific operations. */
        boolean renameFailsForExisting = false;
        boolean finalRenameAlwaysFails = false;
        boolean restoreFails = false;
        boolean writeFails = false;

        SftpClient asProxy() {
            return (SftpClient) java.lang.reflect.Proxy.newProxyInstance(
                SftpClient.class.getClassLoader(),
                new Class<?>[]{SftpClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "write" -> handleWrite((String) args[0]);
                    case "rename" -> { handleRename((String) args[0], (String) args[1]); yield null; }
                    case "remove" -> { handleRemove((String) args[0]); yield null; }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }

        private OutputStream handleWrite(String path) throws IOException {
            if (writeFails) throw new IOException("simulated write failure");
            events.add("write:" + path);
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    files.put(path, this.toByteArray());
                }
            };
        }

        private void handleRename(String from, String to) throws IOException {
            events.add("rename:" + from + "->" + to);
            if (renameFailsForExisting && files.containsKey(to)) {
                throw new IOException("rename over existing not supported");
            }
            if (finalRenameAlwaysFails && !from.endsWith(".bak")) {
                throw new IOException("simulated final rename failure");
            }
            if (restoreFails && from.endsWith(".bak")) {
                throw new IOException("simulated restore failure");
            }
            if (!files.containsKey(from)) {
                throw new IOException("source does not exist: " + from);
            }
            files.put(to, files.remove(from));
        }

        private void handleRemove(String path) {
            events.add("remove:" + path);
            files.remove(path);
        }
    }

    @Test
    void happyPathWritesAndRenamesSuccessfully() throws IOException {
        FakeClient fake = new FakeClient();
        fake.files.put("/etc/foo.conf", "original".getBytes());
        AtomicSftpWrite.writeAtomically(fake.asProxy(), "/etc/foo.conf", "new content".getBytes());
        assertArrayEquals("new content".getBytes(), fake.files.get("/etc/foo.conf"));
    }

    @Test
    void writeFailureLeavesNoOrphanedTemp() {
        FakeClient fake = new FakeClient();
        fake.writeFails = true;
        fake.files.put("/etc/foo.conf", "original".getBytes());
        assertThrows(IOException.class, () ->
            AtomicSftpWrite.writeAtomically(fake.asProxy(), "/etc/foo.conf", "new".getBytes()));
        // Original should still exist
        assertArrayEquals("original".getBytes(), fake.files.get("/etc/foo.conf"));
        // No orphan temp files
        assertFalse(fake.files.keySet().stream().anyMatch(k -> k.contains(".tmp")));
    }

    @Test
    void fallbackPathBackupsAndRenames() throws IOException {
        FakeClient fake = new FakeClient();
        fake.renameFailsForExisting = true;
        fake.files.put("/etc/foo.conf", "original".getBytes());
        AtomicSftpWrite.writeAtomically(fake.asProxy(), "/etc/foo.conf", "new content".getBytes());
        assertArrayEquals("new content".getBytes(), fake.files.get("/etc/foo.conf"));
        // Backup should have been cleaned up
        assertFalse(fake.files.keySet().stream().anyMatch(k -> k.contains(".bak")));
    }

    @Test
    void fallbackRestoresOriginalOnFinalRenameFailure() {
        FakeClient fake = new FakeClient();
        fake.renameFailsForExisting = true;
        fake.finalRenameAlwaysFails = true;
        fake.files.put("/etc/foo.conf", "original".getBytes());
        assertThrows(IOException.class, () ->
            AtomicSftpWrite.writeAtomically(fake.asProxy(), "/etc/foo.conf", "new".getBytes()));
        // Original should be restored
        assertArrayEquals("original".getBytes(), fake.files.get("/etc/foo.conf"));
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner`
Expected: all previous tests + 4 new `AtomicSftpWriteTest` tests passing.

- [ ] **Step 6: Commit**

```bash
git add plugins/sftp/src/com/termlab/sftp/vfs/AtomicSftpWrite.java plugins/sftp/test/com/termlab/sftp/vfs/AtomicSftpWriteTest.java plugins/sftp/src/com/termlab/sftp/vfs/SftpVirtualFile.java
git commit -m "refactor(sftp): extract AtomicSftpWrite helper and add tests"
```

---

## Task 10: Create `SftpFileSource` + `SftpFileSourceProvider`

Implement `FileSource` for SFTP hosts and the provider that registers one source per configured host via `HostStore`.

**Files:**
- Create: `plugins/sftp/src/com/termlab/sftp/filepicker/SftpFileSource.java`
- Create: `plugins/sftp/src/com/termlab/sftp/filepicker/SftpFileSourceProvider.java`
- Modify: `plugins/sftp/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `SftpFileSource.java`**

`plugins/sftp/src/com/termlab/sftp/filepicker/SftpFileSource.java`:

```java
package com.termlab.sftp.filepicker;

import com.termlab.core.filepicker.FileEntry;
import com.termlab.core.filepicker.FileSource;
import com.termlab.sftp.client.SshSftpSession;
import com.termlab.sftp.model.RemoteFileEntry;
import com.termlab.sftp.session.SftpSessionManager;
import com.termlab.sftp.vfs.AtomicSftpWrite;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.model.SshHost;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import org.apache.sshd.sftp.client.SftpClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link FileSource} backed by a single SFTP host. One instance per
 * configured {@link SshHost}. Sessions are owned by
 * {@link SftpSessionManager}; this source acquires/releases them.
 */
public final class SftpFileSource implements FileSource {

    private final SshHost host;
    private volatile SshSftpSession session;
    private volatile String cachedInitialPath;

    public SftpFileSource(@NotNull SshHost host) {
        this.host = host;
    }

    @Override
    public @NotNull String id() {
        return "sftp:" + host.id();
    }

    @Override
    public @NotNull String label() {
        return host.label();
    }

    @Override
    public @NotNull Icon icon() {
        return AllIcons.Nodes.WebFolder;
    }

    @Override
    public @NotNull String initialPath() {
        String cached = cachedInitialPath;
        if (cached != null) return cached;
        if (session == null) {
            throw new IllegalStateException("initialPath() called before open() on " + id());
        }
        try {
            String canonical = session.client().canonicalPath(".");
            cachedInitialPath = (canonical == null || canonical.isBlank()) ? "/" : canonical;
        } catch (IOException e) {
            cachedInitialPath = "/";
        }
        return cachedInitialPath;
    }

    @Override
    public void open(@NotNull Project project, @NotNull Object owner) throws IOException {
        try {
            session = SftpSessionManager.getInstance().acquire(host, owner);
        } catch (SshConnectException e) {
            throw new IOException("Could not connect to " + host.label() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close(@NotNull Object owner) {
        SftpSessionManager.getInstance().release(host.id(), owner);
        session = null;
    }

    @Override
    public @NotNull List<FileEntry> list(@NotNull String absolutePath) throws IOException {
        SshSftpSession s = requireSession();
        List<FileEntry> result = new ArrayList<>();
        for (SftpClient.DirEntry entry : s.client().readDir(absolutePath)) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            result.add(RemoteFileEntry.of(entry));
        }
        return result;
    }

    @Override
    public boolean isDirectory(@NotNull String absolutePath) throws IOException {
        try {
            return requireSession().client().stat(absolutePath).isDirectory();
        } catch (IOException e) {
            if (isNotFound(e)) return false;
            throw e;
        }
    }

    @Override
    public boolean exists(@NotNull String absolutePath) throws IOException {
        try {
            requireSession().client().stat(absolutePath);
            return true;
        } catch (IOException e) {
            if (isNotFound(e)) return false;
            throw e;
        }
    }

    @Override
    public @Nullable String parentOf(@NotNull String absolutePath) {
        if ("/".equals(absolutePath) || absolutePath.isEmpty()) return null;
        String trimmed = absolutePath.endsWith("/")
            ? absolutePath.substring(0, absolutePath.length() - 1)
            : absolutePath;
        int slash = trimmed.lastIndexOf('/');
        if (slash <= 0) return "/";
        return trimmed.substring(0, slash);
    }

    @Override
    public @NotNull String resolve(@NotNull String directoryPath, @NotNull String childName) {
        if (directoryPath.endsWith("/")) return directoryPath + childName;
        return directoryPath + "/" + childName;
    }

    @Override
    public void writeFile(@NotNull String absolutePath, byte @NotNull [] content) throws IOException {
        AtomicSftpWrite.writeAtomically(requireSession().client(), absolutePath, content);
    }

    @Override
    public @NotNull InputStream readFile(@NotNull String absolutePath) throws IOException {
        return requireSession().client().read(absolutePath);
    }

    private @NotNull SshSftpSession requireSession() {
        SshSftpSession s = session;
        if (s == null) {
            throw new IllegalStateException(
                "SftpFileSource operation called before open() on " + id());
        }
        return s;
    }

    private static boolean isNotFound(@NotNull IOException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("no such file") || msg.contains("not found");
    }
}
```

- [ ] **Step 2: Create `SftpFileSourceProvider.java`**

`plugins/sftp/src/com/termlab/sftp/filepicker/SftpFileSourceProvider.java`:

```java
package com.termlab.sftp.filepicker;

import com.termlab.core.filepicker.FileSource;
import com.termlab.core.filepicker.FileSourceProvider;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.SshHost;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers one {@link SftpFileSource} per configured {@link SshHost}
 * with the unified file picker. Queries {@link HostStore} fresh on
 * each call so newly-added hosts appear in the next opened picker
 * without requiring a restart.
 */
public final class SftpFileSourceProvider implements FileSourceProvider {

    @Override
    public @NotNull List<FileSource> listSources() {
        HostStore store = ApplicationManager.getApplication().getService(HostStore.class);
        if (store == null) return List.of();
        List<FileSource> sources = new ArrayList<>();
        for (SshHost host : store.getHosts()) {
            sources.add(new SftpFileSource(host));
        }
        return sources;
    }
}
```

- [ ] **Step 3: Register the provider in `plugins/sftp/resources/META-INF/plugin.xml`**

Inside the existing `<extensions>` block (or as a new one using the `com.termlab.core` namespace), add:

```xml
    <extensions defaultExtensionNs="com.termlab.core">
        <fileSourceProvider implementation="com.termlab.sftp.filepicker.SftpFileSourceProvider"/>
    </extensions>
```

- [ ] **Step 4: Build**

Run: `bash bazel.cmd build //termlab/plugins/sftp:sftp`
Expected: `Build completed successfully`.

- [ ] **Step 5: Commit**

```bash
git add plugins/sftp/src/com/termlab/sftp/filepicker/ plugins/sftp/resources/META-INF/plugin.xml
git commit -m "feat(sftp): SftpFileSource and SftpFileSourceProvider"
```

---

## Task 11: Create `ErrorMessages` helper

Small utility that translates common IOException messages into friendly sentences for the dialog's error card.

**Files:**
- Create: `core/src/com/termlab/core/filepicker/ui/ErrorMessages.java`
- Create: `core/test/com/termlab/core/filepicker/ui/ErrorMessagesTest.java`

- [ ] **Step 1: Write the failing test**

`core/test/com/termlab/core/filepicker/ui/ErrorMessagesTest.java`:

```java
package com.termlab.core.filepicker.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorMessagesTest {

    @Test
    void authFailTranslated() {
        String msg = ErrorMessages.translate(new IOException("Auth fail"));
        assertTrue(msg.contains("Permission denied") || msg.contains("credentials"));
    }

    @Test
    void permissionDeniedTranslated() {
        String msg = ErrorMessages.translate(new IOException("permission denied"));
        assertTrue(msg.toLowerCase().contains("permission denied"));
    }

    @Test
    void connectionRefusedTranslated() {
        String msg = ErrorMessages.translate(new IOException("Connection refused"));
        assertTrue(msg.toLowerCase().contains("refused"));
    }

    @Test
    void unknownHostTranslated() {
        String msg = ErrorMessages.translate(new IOException("Unknown host: example.com"));
        assertTrue(msg.toLowerCase().contains("reach"));
    }

    @Test
    void timedOutTranslated() {
        String msg = ErrorMessages.translate(new IOException("Connection timed out"));
        assertTrue(msg.toLowerCase().contains("timed out"));
    }

    @Test
    void noSuchFileTranslated() {
        String msg = ErrorMessages.translate(new IOException("No such file or directory"));
        assertTrue(msg.toLowerCase().contains("not found"));
    }

    @Test
    void unknownMessageFallsThrough() {
        String msg = ErrorMessages.translate(new IOException("some random error"));
        assertEquals("Error: some random error", msg);
    }

    @Test
    void nullMessageFallsThroughToClassName() {
        String msg = ErrorMessages.translate(new IOException());
        assertTrue(msg.contains("Error:"));
        assertTrue(msg.contains("IOException"));
    }
}
```

- [ ] **Step 2: Run, confirm red**

Run: `bash bazel.cmd run //termlab/core:core_test_runner`
Expected: compile failure.

- [ ] **Step 3: Implement `ErrorMessages.java`**

`core/src/com/termlab/core/filepicker/ui/ErrorMessages.java`:

```java
package com.termlab.core.filepicker.ui;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Translates common IOException messages into friendly sentences
 * for display in the unified file picker's error card.
 */
public final class ErrorMessages {

    private ErrorMessages() {}

    public static @NotNull String translate(@NotNull IOException e) {
        String raw = e.getMessage();
        if (raw == null) return "Error: " + e.getClass().getSimpleName();
        String msg = raw.toLowerCase();
        if (msg.contains("auth fail"))
            return "Permission denied. Check credentials in the vault.";
        if (msg.contains("permission denied"))
            return "Permission denied on the remote. Check folder permissions.";
        if (msg.contains("connection refused"))
            return "The host refused the connection. Is SSH running on the expected port?";
        if (msg.contains("unknown host") || msg.contains("no route to host"))
            return "Could not reach the host. Check the hostname and network connection.";
        if (msg.contains("timed out"))
            return "The connection timed out.";
        if (msg.contains("no such file") || msg.contains("not found"))
            return "File or directory not found.";
        return "Error: " + raw;
    }
}
```

- [ ] **Step 4: Run, confirm green**

Run: `bash bazel.cmd run //termlab/core:core_test_runner`
Expected: 22 tests passing (14 LocalFileSource + 8 ErrorMessages).

- [ ] **Step 5: Commit**

```bash
git add core/src/com/termlab/core/filepicker/ui/ErrorMessages.java core/test/com/termlab/core/filepicker/ui/ErrorMessagesTest.java
git commit -m "feat(core): ErrorMessages helper for picker error card"
```

---

## Task 12: Create `UnifiedFilePickerDialog` skeleton

Build the dialog structure: source dropdown, path bar, file list area with `CardLayout`, (optional) filename input, OK/Cancel buttons. No source-switching or listing logic yet — just the layout and the static entry points.

**Files:**
- Create: `core/src/com/termlab/core/filepicker/ui/UnifiedFilePickerDialog.java`

- [ ] **Step 1: Create the dialog class**

`core/src/com/termlab/core/filepicker/ui/UnifiedFilePickerDialog.java`:

```java
package com.termlab.core.filepicker.ui;

import com.termlab.core.filepicker.FilePickerResult;
import com.termlab.core.filepicker.FileSource;
import com.termlab.core.filepicker.FileSourceProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.AsyncProcessIcon;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Unified file picker dialog. Supports two modes — Save and Open —
 * through the static entry points {@link #showSaveDialog} and
 * {@link #showOpenDialog}. See the spec document at
 * {@code docs/superpowers/specs/2026-04-15-unified-file-picker-design.md}
 * for the full design.
 */
public final class UnifiedFilePickerDialog extends DialogWrapper {

    enum Mode { SAVE, OPEN }

    private static final String CARD_LOADING = "loading";
    private static final String CARD_TABLE = "table";
    private static final String CARD_ERROR = "error";

    private final Project project;
    private final Mode mode;
    private final String suggestedFileName;
    private final String preferredSourceId;

    private final JComboBox<FileSource> sourceCombo = new JComboBox<>();
    private final JTextField pathField = new JTextField();
    private final JTextField filenameField = new JTextField();
    private final FileBrowserTable browser = new FileBrowserTable();
    private final JPanel fileListCard = new JPanel(new CardLayout());
    private final JLabel errorLabel = new JLabel();
    private final JButton retryButton = new JButton("Retry");

    private @Nullable FileSource currentSource;
    private @Nullable String currentPath;
    private @Nullable FilePickerResult result;

    // Static entry points ---------------------------------------------------

    public static @Nullable FilePickerResult showSaveDialog(
        @NotNull Project project,
        @NotNull String title,
        @NotNull String suggestedFileName,
        @Nullable String preferredSourceId
    ) {
        UnifiedFilePickerDialog dialog = new UnifiedFilePickerDialog(
            project, Mode.SAVE, title, suggestedFileName, preferredSourceId);
        return dialog.showAndReturn();
    }

    public static @Nullable FilePickerResult showOpenDialog(
        @NotNull Project project,
        @NotNull String title,
        @Nullable String preferredSourceId
    ) {
        UnifiedFilePickerDialog dialog = new UnifiedFilePickerDialog(
            project, Mode.OPEN, title, "", preferredSourceId);
        return dialog.showAndReturn();
    }

    // Construction ----------------------------------------------------------

    private UnifiedFilePickerDialog(
        @NotNull Project project,
        @NotNull Mode mode,
        @NotNull String title,
        @NotNull String suggestedFileName,
        @Nullable String preferredSourceId
    ) {
        super(project, true);
        this.project = project;
        this.mode = mode;
        this.suggestedFileName = suggestedFileName;
        this.preferredSourceId = preferredSourceId;
        setTitle(title);
        setOKButtonText(mode == Mode.SAVE ? "Save" : "Open");
        init();
        // Source population and initial listing happens in Task 13/14.
        populateSources();
    }

    private @Nullable FilePickerResult showAndReturn() {
        if (showAndGet()) return result;
        return null;
    }

    // Layout ----------------------------------------------------------------

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout());
        root.setPreferredSize(new Dimension(700, 500));

        root.add(buildNorthPanel(), BorderLayout.NORTH);
        root.add(buildFileListCard(), BorderLayout.CENTER);
        if (mode == Mode.SAVE) {
            root.add(buildSouthPanel(), BorderLayout.SOUTH);
        }
        return root;
    }

    private JComponent buildNorthPanel() {
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setBorder(JBUI.Borders.empty(4, 8));

        JPanel sourceRow = new JPanel(new BorderLayout(6, 0));
        sourceRow.add(new JLabel("Where: "), BorderLayout.WEST);
        sourceRow.add(sourceCombo, BorderLayout.CENTER);
        sourceRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(sourceRow);

        north.add(Box.createVerticalStrut(4));

        JPanel pathRow = new JPanel(new BorderLayout(6, 0));
        JButton upButton = new JButton("▲");
        upButton.setToolTipText("Parent directory");
        pathRow.add(upButton, BorderLayout.WEST);
        pathRow.add(pathField, BorderLayout.CENTER);
        pathRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(pathRow);

        return north;
    }

    private JComponent buildFileListCard() {
        // Table card (default)
        JPanel tableCard = new JPanel(new BorderLayout());
        tableCard.add(browser.getComponent(), BorderLayout.CENTER);

        // Loading card
        JPanel loadingCard = new JPanel(new BorderLayout());
        JPanel loadingCenter = new JPanel();
        loadingCenter.setLayout(new BoxLayout(loadingCenter, BoxLayout.Y_AXIS));
        loadingCenter.add(Box.createVerticalGlue());
        JPanel loadingRow = new JPanel();
        loadingRow.add(new AsyncProcessIcon("Loading"));
        loadingRow.add(new JLabel("Loading…"));
        loadingCenter.add(loadingRow);
        loadingCenter.add(Box.createVerticalGlue());
        loadingCard.add(loadingCenter, BorderLayout.CENTER);

        // Error card
        JPanel errorCard = new JPanel(new BorderLayout());
        JPanel errorCenter = new JPanel();
        errorCenter.setLayout(new BoxLayout(errorCenter, BoxLayout.Y_AXIS));
        errorCenter.add(Box.createVerticalGlue());
        errorLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        errorCenter.add(errorLabel);
        errorCenter.add(Box.createVerticalStrut(8));
        retryButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        errorCenter.add(retryButton);
        errorCenter.add(Box.createVerticalGlue());
        errorCard.add(errorCenter, BorderLayout.CENTER);

        fileListCard.add(tableCard, CARD_TABLE);
        fileListCard.add(loadingCard, CARD_LOADING);
        fileListCard.add(errorCard, CARD_ERROR);
        fileListCard.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        showCard(CARD_LOADING);
        return fileListCard;
    }

    private JComponent buildSouthPanel() {
        JPanel south = new JPanel(new BorderLayout(6, 0));
        south.setBorder(JBUI.Borders.empty(4, 8));
        south.add(new JLabel("File name: "), BorderLayout.WEST);
        filenameField.setText(suggestedFileName);
        south.add(filenameField, BorderLayout.CENTER);
        return south;
    }

    private void showCard(@NotNull String name) {
        ((CardLayout) fileListCard.getLayout()).show(fileListCard, name);
    }

    // Source population -----------------------------------------------------

    private void populateSources() {
        List<FileSource> sources = collectSources();
        for (FileSource s : sources) {
            sourceCombo.addItem(s);
        }
        sourceCombo.setRenderer(new SourceComboRenderer());
        if (sourceCombo.getItemCount() > 0) {
            sourceCombo.setSelectedIndex(0);
        }
    }

    private @NotNull List<FileSource> collectSources() {
        List<FileSource> all = new ArrayList<>();
        for (FileSourceProvider provider : FileSourceProvider.EP_NAME.getExtensionList()) {
            all.addAll(provider.listSources());
        }
        // Sort: preferred id first, then alphabetically by label.
        all.sort(Comparator
            .<FileSource, Boolean>comparing(s -> !s.id().equals(preferredSourceId))
            .thenComparing(FileSource::label, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    // Dialog completion -----------------------------------------------------

    @Override
    protected void doOKAction() {
        // Implemented in Task 13 for Save and Task 14 for Open.
        // For the skeleton, just close with no result.
        super.doOKAction();
    }

    @Override
    public void dispose() {
        // Release any acquired source references.
        if (currentSource != null) {
            currentSource.close(this);
            currentSource = null;
        }
        super.dispose();
    }
}
```

Note: `SourceComboRenderer` is used but not yet defined. Add it as a nested class or a separate file in Task 13. For now, a placeholder:

Add this inner class at the bottom of `UnifiedFilePickerDialog.java`:

```java
    private static final class SourceComboRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(
            javax.swing.JList<?> list, Object value, int index,
            boolean isSelected, boolean cellHasFocus
        ) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof FileSource source) {
                setText(source.label());
                setIcon(source.icon());
            }
            return this;
        }
    }
```

- [ ] **Step 2: Build**

Run: `bash bazel.cmd build //termlab/core:core`
Expected: `Build completed successfully`.

- [ ] **Step 3: Commit**

```bash
git add core/src/com/termlab/core/filepicker/ui/UnifiedFilePickerDialog.java
git commit -m "feat(core): UnifiedFilePickerDialog skeleton with layout and source dropdown"
```

---

## Task 13: Wire up source switching, directory navigation, and the Save flow

Add the dialog's state machine: opening a source, listing directories, handling path-bar input, the Save button flow with overwrite confirmation.

**Files:**
- Modify: `core/src/com/termlab/core/filepicker/ui/UnifiedFilePickerDialog.java`

- [ ] **Step 1: Add the `openAndLoadCurrentSource` helper**

Add this method to the dialog class. It runs the source-open + initial-list under modal progress:

```java
    private void openAndLoadCurrentSource() {
        FileSource source = (FileSource) sourceCombo.getSelectedItem();
        if (source == null) return;
        showCard(CARD_LOADING);
        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            new com.intellij.openapi.progress.Task.Modal(
                project, "Connecting to " + source.label() + "…", true
            ) {
                private java.io.IOException error;
                private java.util.List<com.termlab.core.filepicker.FileEntry> entries;
                private String loadPath;

                @Override
                public void run(@NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    try {
                        source.open(project, UnifiedFilePickerDialog.this);
                        loadPath = source.initialPath();
                        entries = source.list(loadPath);
                    } catch (java.io.IOException e) {
                        error = e;
                    }
                }

                @Override
                public void onSuccess() {
                    if (error != null) {
                        errorLabel.setText(ErrorMessages.translate(error));
                        showCard(CARD_ERROR);
                        return;
                    }
                    // Close previously-open source.
                    if (currentSource != null && currentSource != source) {
                        currentSource.close(UnifiedFilePickerDialog.this);
                    }
                    currentSource = source;
                    currentPath = loadPath;
                    pathField.setText(loadPath);
                    browser.setEntries(entries);
                    showCard(CARD_TABLE);
                }
            });
    }
```

- [ ] **Step 2: Wire up the source dropdown listener**

In the constructor, after `populateSources()`, add:

```java
        sourceCombo.addItemListener(e -> {
            if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                openAndLoadCurrentSource();
            }
        });

        retryButton.addActionListener(e -> openAndLoadCurrentSource());
```

- [ ] **Step 3: Trigger initial load after dialog construction**

The dialog needs to load the initial source after its UI is built. IntelliJ's `DialogWrapper` calls `init()` synchronously; we need to kick off the load after the dialog becomes visible. Override `show()` or use an `invokeLater`:

Add at the end of the constructor, after the listener wiring:

```java
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
            this::openAndLoadCurrentSource);
```

- [ ] **Step 4: Wire up directory navigation**

Add this helper method:

```java
    private void navigateTo(@NotNull String path) {
        FileSource source = currentSource;
        if (source == null) return;
        showCard(CARD_LOADING);
        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            new com.intellij.openapi.progress.Task.Modal(project, "Loading…", false) {
                private java.io.IOException error;
                private java.util.List<com.termlab.core.filepicker.FileEntry> entries;

                @Override
                public void run(@NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    try {
                        entries = source.list(path);
                    } catch (java.io.IOException e) {
                        error = e;
                    }
                }

                @Override
                public void onSuccess() {
                    if (error != null) {
                        errorLabel.setText(ErrorMessages.translate(error));
                        showCard(CARD_ERROR);
                        return;
                    }
                    currentPath = path;
                    pathField.setText(path);
                    browser.setEntries(entries);
                    showCard(CARD_TABLE);
                }
            });
    }
```

And wire the double-click handler (add in the constructor, after the other listener wiring):

```java
        browser.addDoubleClickListener(entry -> {
            FileSource source = currentSource;
            if (source == null || currentPath == null) return;
            if (entry.isDirectory()) {
                navigateTo(source.resolve(currentPath, entry.name()));
                return;
            }
            // File clicked
            if (mode == Mode.SAVE) {
                filenameField.setText(entry.name());
            } else {
                // Open mode: treat as OK
                result = new FilePickerResult(source, source.resolve(currentPath, entry.name()));
                close(OK_EXIT_CODE);
            }
        });
```

- [ ] **Step 5: Wire the Up button and path-field Enter**

Find the `upButton` declaration in `buildNorthPanel()`. After the JPanel declaration but before adding upButton to the row, add:

```java
        upButton.addActionListener(e -> {
            FileSource source = currentSource;
            if (source == null || currentPath == null) return;
            String parent = source.parentOf(currentPath);
            if (parent != null) navigateTo(parent);
        });
```

And the path-field Enter handler (add after `pathField` declaration or in the constructor):

```java
        pathField.addActionListener(e -> {
            FileSource source = currentSource;
            if (source == null) return;
            String path = pathField.getText().trim();
            try {
                if (!source.isDirectory(path)) {
                    flashPathFieldRed();
                    return;
                }
            } catch (java.io.IOException ex) {
                flashPathFieldRed();
                return;
            }
            navigateTo(path);
        });
```

Add the `flashPathFieldRed()` helper:

```java
    private void flashPathFieldRed() {
        java.awt.Color original = pathField.getBackground();
        pathField.setBackground(new java.awt.Color(255, 200, 200));
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            pathField.setBackground(original);
            if (currentPath != null) pathField.setText(currentPath);
        });
    }
```

Note: `Thread.sleep` on the EDT is a bad pattern; use `javax.swing.Timer` instead. Replace with:

```java
    private void flashPathFieldRed() {
        java.awt.Color original = pathField.getBackground();
        pathField.setBackground(new java.awt.Color(255, 200, 200));
        javax.swing.Timer timer = new javax.swing.Timer(300, evt -> {
            pathField.setBackground(original);
            if (currentPath != null) pathField.setText(currentPath);
        });
        timer.setRepeats(false);
        timer.start();
    }
```

- [ ] **Step 6: Implement `doOKAction` for Save mode**

Replace the skeleton's `doOKAction` override with:

```java
    @Override
    protected void doOKAction() {
        if (mode == Mode.SAVE) {
            doSaveAction();
        } else {
            doOpenAction();
        }
    }

    private void doSaveAction() {
        FileSource source = currentSource;
        if (source == null || currentPath == null) return;
        String filename = filenameField.getText().trim();
        if (filename.isEmpty()) return;
        if (filename.contains("/") || filename.contains("\\")) {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                getContentPanel(),
                "File name must not contain path separators: " + filename,
                "Invalid File Name");
            return;
        }
        String destPath = source.resolve(currentPath, filename);
        boolean exists;
        try {
            exists = source.exists(destPath);
        } catch (java.io.IOException e) {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                getContentPanel(), ErrorMessages.translate(e), "Error");
            return;
        }
        if (exists) {
            int choice = com.intellij.openapi.ui.Messages.showYesNoDialog(
                getContentPanel(),
                filename + " already exists on " + source.label() + ". Overwrite?",
                "File Exists",
                com.intellij.openapi.ui.Messages.getQuestionIcon());
            if (choice != com.intellij.openapi.ui.Messages.YES) return;
        }
        result = new FilePickerResult(source, destPath);
        super.doOKAction();
    }

    private void doOpenAction() {
        FileSource source = currentSource;
        if (source == null || currentPath == null) return;
        FileEntry selected = browser.getSelectedEntry();
        if (selected == null || selected.isDirectory()) return;
        result = new FilePickerResult(source, source.resolve(currentPath, selected.name()));
        super.doOKAction();
    }
```

Add the needed import:

```java
import com.termlab.core.filepicker.FileEntry;
```

- [ ] **Step 7: Build**

Run: `bash bazel.cmd build //termlab/core:core`
Expected: `Build completed successfully`.

- [ ] **Step 8: Commit**

```bash
git add core/src/com/termlab/core/filepicker/ui/UnifiedFilePickerDialog.java
git commit -m "feat(core): picker source switching, navigation, and Save flow"
```

---

## Task 14: Wire up the editor plugin's `SaveScratchToRemoteAction` to the new dialog

Replace the current `proceedWithHost`-based flow in `SaveScratchToRemoteAction` with a single call to `UnifiedFilePickerDialog.showSaveDialog(...)`. Remove the now-unused helpers inside the action.

**Files:**
- Modify: `plugins/editor/src/com/termlab/editor/scratch/SaveScratchToRemoteAction.java`

- [ ] **Step 1: Verify the editor plugin depends on core**

Read `plugins/editor/BUILD.bazel`. Confirm `//termlab/core` is in the `editor` jvm_library's `deps`. It should already be there.

- [ ] **Step 2: Replace the full contents of `SaveScratchToRemoteAction.java`**

```java
package com.termlab.editor.scratch;

import com.termlab.core.filepicker.FilePickerResult;
import com.termlab.core.filepicker.ui.UnifiedFilePickerDialog;
import com.termlab.sftp.vfs.SftpUrl;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Save the active scratch file to a local directory or a remote SFTP
 * host via the unified file picker. Bound to Cmd+Alt+S / Ctrl+Alt+S.
 */
public final class SaveScratchToRemoteAction extends AnAction {

    private static final String NOTIFICATION_GROUP = "SFTP";
    private static final String LAST_SOURCE_KEY = "termlab.editor.lastRemoteSourceId";

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(canRun(e));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private static boolean canRun(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return false;
        return activeScratchFile(project) != null;
    }

    private static @Nullable VirtualFile activeScratchFile(@NotNull Project project) {
        FileEditor editor = FileEditorManager.getInstance(project).getSelectedEditor();
        if (editor == null) return null;
        VirtualFile file = editor.getFile();
        if (!(file instanceof LightVirtualFile lvf)) return null;
        if (lvf.getUserData(ScratchMarker.KEY) != Boolean.TRUE) return null;
        return file;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        VirtualFile scratch = activeScratchFile(project);
        if (scratch == null) return;

        FilePickerResult result = UnifiedFilePickerDialog.showSaveDialog(
            project,
            "Save Scratch",
            scratch.getName(),
            lastUsedSourceId());
        if (result == null) return;

        Document doc = FileDocumentManager.getInstance().getDocument(scratch);
        if (doc == null) return;
        byte[] bytes = doc.getText().getBytes(StandardCharsets.UTF_8);

        try {
            result.source().writeFile(result.absolutePath(), bytes);
        } catch (IOException ioe) {
            notifyError(project, "Save failed: " + ioe.getMessage());
            return;
        }

        rememberLastUsedSource(result.source().id());

        VirtualFile saved = resolveSavedVirtualFile(result);
        if (saved != null) {
            FileEditorManager mgr = FileEditorManager.getInstance(project);
            mgr.closeFile(scratch);
            mgr.openFile(saved, true);
        }

        notify(project,
            "Saved to " + result.source().label() + ":" + result.absolutePath(),
            NotificationType.INFORMATION);
    }

    private static @Nullable String lastUsedSourceId() {
        return PropertiesComponent.getInstance().getValue(LAST_SOURCE_KEY);
    }

    private static void rememberLastUsedSource(@NotNull String id) {
        PropertiesComponent.getInstance().setValue(LAST_SOURCE_KEY, id);
    }

    /**
     * Convert a {@link FilePickerResult} into an IntelliJ
     * {@link VirtualFile} so the saved file opens as a proper editor
     * tab. Local files go through {@link LocalFileSystem}; SFTP files
     * go through the SFTP virtual filesystem.
     */
    private static @Nullable VirtualFile resolveSavedVirtualFile(@NotNull FilePickerResult result) {
        String id = result.source().id();
        if ("local".equals(id)) {
            return LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(Paths.get(result.absolutePath()));
        } else if (id.startsWith("sftp:")) {
            try {
                UUID hostId = UUID.fromString(id.substring("sftp:".length()));
                String url = SftpUrl.compose(hostId, result.absolutePath());
                return VirtualFileManager.getInstance().findFileByUrl(url);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private static void notify(@NotNull Project project, @NotNull String message, @NotNull NotificationType type) {
        Notifications.Bus.notify(
            new Notification(NOTIFICATION_GROUP, "Save Scratch", message, type), project);
    }

    private static void notifyError(@NotNull Project project, @NotNull String message) {
        notify(project, message, NotificationType.ERROR);
    }
}
```

- [ ] **Step 3: Build**

Run: `bash bazel.cmd build //termlab/plugins/editor:editor`
Expected: `Build completed successfully`. If it fails, the editor plugin may be missing the core filepicker dep — check `plugins/editor/BUILD.bazel`.

- [ ] **Step 4: Build the whole product**

Run: `bash bazel.cmd build //termlab:termlab_run`
Expected: `Build completed successfully`.

- [ ] **Step 5: Commit**

```bash
git add plugins/editor/src/com/termlab/editor/scratch/SaveScratchToRemoteAction.java
git commit -m "refactor(editor): SaveScratchToRemoteAction delegates to UnifiedFilePickerDialog"
```

---

## Task 15: End-to-end manual verification

Run the full 12-step E2E checklist from the spec against a running TermLab.

**Files:** none (validation only)

- [ ] **Step 1: Build the product**

Run: `bash bazel.cmd build //termlab:termlab_run`
Expected: `Build completed successfully`.

- [ ] **Step 2: Run through the spec's manual E2E checklist**

Execute each of the 12 steps in the "Manual E2E checklist" section of `docs/superpowers/specs/2026-04-15-unified-file-picker-design.md`:

1. Build + launch — no startup errors in `~/Library/Logs/TermLab2026.2/idea.log`
2. Save scratch to local — picker opens with Local pre-selected, navigation works, file lands on disk
3. Save scratch to SFTP with active session — picker opens with the active host pre-selected, remote tree renders, save works
4. Save scratch to SFTP with no active session — dropdown shows all hosts, modal connect on pick, save works
5. Switch source mid-dialog — modal connect, file list updates, save writes to new source
6. Overwrite confirmation — "file already exists" dialog appears, Yes overwrites, No stays open
7. Cancel during connect — dialog returns to previous source
8. Connect failure — error card with readable message, Retry button re-attempts
9. Path bar typing — valid path navigates, non-existent flashes red and reverts
10. Empty directory — "This folder is empty" overlay
11. **SFTP tool window regression** — browsing, connecting, disconnecting works identically to before Task 8
12. Keyboard: Cmd+Up navigates up, Enter on directory navigates in, Enter on file (Save mode) populates filename, Escape cancels

- [ ] **Step 3: Record any failures as follow-up tasks**

If any step fails, create a new task under `docs/superpowers/plans/` describing the failure and proposed fix. Do NOT squash-fix here — each fix should be its own small, reviewable change.

- [ ] **Step 4: Commit the E2E verification**

```bash
git commit --allow-empty -m "chore(core): manual e2e verification passed for unified file picker"
```

---

## Out of Scope (Follow-ups)

These were flagged in the spec and should NOT be done in this plan:

1. `File → Open Remote File…` action using `UnifiedFilePickerDialog.showOpenDialog(...)`.
2. Migrating other existing file-picker call sites in TermLab to the unified picker.
3. New Folder button inside the dialog.
4. Type filter dropdown, hidden-file toggle, multi-select.
5. UI unit tests via `HeavyPlatformTestCase` or similar IntelliJ test fixture.
6. Remote path autocomplete in the path field.
7. Recent-locations sidebar / bookmark list.
