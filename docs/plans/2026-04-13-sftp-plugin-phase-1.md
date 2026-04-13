# Conch SFTP Plugin — Phase 1 Implementation Plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` or
> `superpowers:executing-plans` to execute this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a read-only dual-pane SFTP browser as a new `com.conch.sftp`
plugin with a bottom-anchored tool window. Local pane browses the user's
last-visited directory (fallback `$HOME`). Remote pane has a host picker
sourced from `HostStore`; picking a host opens an SFTP session via MINA's
`SftpClientFactory` layered on an existing `ConchSshClient` session, and the
remote pane lists that host's working directory. **No transfers yet** — just
browse both sides so the architecture is pinned down before transfer /
collision / queue work starts in Phase 2.

**Architecture:** Custom tool window (not Project View), bottom anchor.
Dual-pane `JBSplitter` with a pair of `JBTable`s. Both tables share a
`FileEntry` interface so rendering code is unified. The local table reads
via `java.nio.file` directly; the remote table reads via a new `ConchSftpClient`
that wraps MINA's `SftpClient`. Both reads happen on the application executor,
never on EDT, with a `CompletableFuture`-driven model-reload API.

**Tech stack:** Java 21 / Swing / JBTable / JBSplitter / MINA SSHD 2.15.0
(`sshd-sftp` module, new) / existing `ConchSshClient` + `SshCredentialResolver`
infrastructure.

**Out of scope (later phases):**

- Transfers (Phase 2)
- Collision prompts, transfer queue (Phase 2 / 4)
- Auto-attach to active SSH terminal sessions via `SshSessionRegistry` (Phase 3)
- Recursive ops, chmod, symlinks, hidden-file toggle (Phase 5)
- Local CRUD (Phase 5 — even though the user wants it, it slots cleanly
  after the read path is stable)
- rsync/scp transfer engine picker + settings panel (Phase 6)

---

## File structure

```
plugins/sftp/
├── BUILD.bazel                     # new: jvm_library target + resourcegroup
├── intellij.conch.sftp.iml         # new: JPS module descriptor
├── resources/
│   └── META-INF/
│       └── plugin.xml              # new: toolWindow + service declarations
└── src/com/conch/sftp/
    ├── client/
    │   ├── ConchSftpClient.java    # new: wraps MINA SftpClient, exposes
    │   │                           #   listDirectory, stat, close. Owned
    │   │                           #   by a single RemoteFilePane instance.
    │   └── SftpListingException.java  # new: typed failure wrapper
    ├── model/
    │   ├── FileEntry.java          # new: shared interface — name, size,
    │   │                           #   mtime (Instant), isDirectory,
    │   │                           #   isSymlink, permissions (String)
    │   ├── LocalFileEntry.java     # new: record implementing FileEntry,
    │   │                           #   built from java.nio.file.Path stat
    │   └── RemoteFileEntry.java    # new: record built from
    │                               #   SftpClient.DirEntry
    ├── persistence/
    │   └── ConchSftpConfig.java    # new: PersistentStateComponent,
    │                               #   last-browsed local path + last host id
    ├── toolwindow/
    │   ├── SftpToolWindow.java     # new: JBSplitter host, wires the two
    │   │                           #   panes, owns their lifetime
    │   ├── SftpToolWindowFactory.java  # new: platform toolWindow EP
    │   │                           #   factory, creates the JPanel content
    │   ├── LocalFilePane.java      # new: local-side JBTable + navigation
    │   │                           #   toolbar (up, refresh, reload)
    │   ├── RemoteFilePane.java     # new: remote-side JBTable + host
    │   │                           #   picker + connect/disconnect buttons
    │   └── FileTableModel.java     # new: shared AbstractTableModel over
    │                               #   List<FileEntry>, 4 columns:
    │                               #   Name, Size, Modified, Permissions
    ├── palette/
    │   └── SftpSearchEverywhereContributor.java  # new: "SFTP" tab in SE,
    │                               #   one action: "Open SFTP tool window"
    └── icons/
        └── (uses AllIcons for everything in Phase 1, no custom assets)
```

---

## Task 1: Plugin scaffolding + Bazel/JPS wiring

**Files:**
- Create: `plugins/sftp/BUILD.bazel`
- Create: `plugins/sftp/intellij.conch.sftp.iml`
- Create: `plugins/sftp/resources/META-INF/plugin.xml` (skeleton)
- Create: `plugins/sftp/resources/META-INF/.gitkeep` (so git tracks the empty dir)
- Create: `plugins/sftp/src/com/conch/sftp/.gitkeep`
- Modify: `BUILD.bazel` — add `//conch/plugins/sftp` to `conch_run` runtime_deps
- Modify: `BUILD.bazel` — add `//conch/plugins/sftp` to `conch-main` jvm_library runtime_deps
- Modify: `build/src/org/jetbrains/intellij/build/conch/ConchProperties.kt` — add `"intellij.conch.sftp"` to `productLayout.bundledPluginModules`
- Modify: `customization/resources/idea/ConchApplicationInfo.xml` — add `<essential-plugin>com.conch.sftp</essential-plugin>`
- Modify: `/Users/dustin/projects/intellij-community/.idea/modules.xml` — register `intellij.conch.sftp.iml`

- [ ] **Step 1.1: Write plugin.xml skeleton**

```xml
<idea-plugin>
    <id>com.conch.sftp</id>
    <name>Conch SFTP</name>
    <version>0.1.0</version>
    <vendor>Conch</vendor>
    <description>Dual-pane SFTP browser for Conch Workbench.</description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.conch.core</depends>
    <depends>com.conch.ssh</depends>

    <extensions defaultExtensionNs="com.intellij">
        <applicationService
            serviceImplementation="com.conch.sftp.persistence.ConchSftpConfig"/>

        <toolWindow
            id="Conch SFTP"
            anchor="bottom"
            icon="AllIcons.Nodes.ExtractedFolder"
            factoryClass="com.conch.sftp.toolwindow.SftpToolWindowFactory"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 1.2: Write BUILD.bazel following the tunnels plugin pattern**

Reference: `plugins/tunnels/BUILD.bazel`. Key changes for sftp:
- `module_name = "intellij.conch.sftp"`
- deps: `//conch/sdk`, `//conch/core`, `//conch/plugins/ssh`, plus platform deps
  listed in the tunnels plugin. **Do not** add SFTP library yet — that's
  Task 2.
- `resources = [":sftp_resources"]`, mirroring the tunnels resourcegroup

- [ ] **Step 1.3: Write the iml file**

Copy `plugins/tunnels/intellij.conch.tunnels.iml` as a template. Change
module name to `intellij.conch.sftp`. Dependencies list should match what
Bazel BUILD.bazel declares — module deps on `intellij.conch.sdk`,
`intellij.conch.core`, `intellij.conch.ssh`, `intellij.platform.core`,
`intellij.platform.core.impl`, `intellij.platform.ide`, `intellij.platform.ide.impl`,
`intellij.platform.util.ui`; library deps on `jetbrains-annotations` and
`kotlin-stdlib`.

- [ ] **Step 1.4: Add to modules.xml**

```xml
<module fileurl="file://$PROJECT_DIR$/conch/plugins/sftp/intellij.conch.sftp.iml"
        filepath="$PROJECT_DIR$/conch/plugins/sftp/intellij.conch.sftp.iml" />
```

Insert alphabetically, right after the `conch/plugins/ssh` entry.

- [ ] **Step 1.5: Add to conch_run + conch-main in root BUILD.bazel**

```python
# In conch_run runtime_deps list:
        "//conch/plugins/sftp",

# In conch-main jvm_library runtime_deps list:
        "//conch/plugins/sftp",
```

- [ ] **Step 1.6: Add to bundledPluginModules in ConchProperties.kt**

Inside the `productLayout.bundledPluginModules = persistentListOf(...)`
block, add `"intellij.conch.sftp",` to keep the terminal-first plugins
together.

- [ ] **Step 1.7: Add as essential plugin in ConchApplicationInfo.xml**

Right after `<essential-plugin>com.conch.tunnels</essential-plugin>`, add:

```xml
<essential-plugin>com.conch.sftp</essential-plugin>
```

- [ ] **Step 1.8: Verify Bazel build succeeds**

```
make conch-build
```

Expected: `Target //conch:conch_run up-to-date`, no compile errors. At this
point the plugin is registered, has no code, and does nothing but show a
`Conch SFTP` tool window stripe button at the bottom of the frame. The tool
window itself will be empty until Task 6.

---

## Task 2: Bundle MINA `sshd-sftp` library

**Decision required at execution time:** two viable approaches, pick one.

### Approach A — add `sshd-sftp` as a new intellij-community library module

**Files:**
- Modify: `intellij-community/lib/MODULE.bazel` — add `http_file` entries for
  `sshd-sftp-2.15.0.jar` + `-sources.jar`, mirroring the existing
  `org_apache_sshd-sshd-osgi-2_15_0_http` declarations (line ~247).
- Modify: `intellij-community/lib/BUILD.bazel` — add a `copy_file` + `jvm_import`
  for `apache-sshd-sftp`, mirroring the `apache-sshd-osgi` block at line ~606.
- Modify: `intellij-community/libraries/sshd-osgi/BUILD.bazel` — add the new
  jvm_import to `exports` and `runtime_deps`. Same `libraries/sshd-osgi` module,
  just includes both jars.
- Modify: `intellij-community/.idea/libraries/apache_sshd_osgi.xml` — add
  a second `<root>` entry pointing at the sshd-sftp jar, so JPS and the IDE
  see it too.

**Risk:** The `lib/MODULE.bazel` http_file declarations live inside an
`### auto-generated section 'maven libs' start` block. If a regenerator
ever runs, our addition disappears. Document a prominent comment near the
edit: `# Conch: do not remove — see docs/plans/2026-04-13-sftp-plugin-phase-1.md`.

### Approach B — vendor the jar into the Conch SFTP plugin (recommended)

**Files:**
- Create: `plugins/sftp/libs/sshd-sftp-2.15.0.jar` — downloaded from
  `https://repo1.maven.org/maven2/org/apache/sshd/sshd-sftp/2.15.0/sshd-sftp-2.15.0.jar`.
  SHA-256 verify against Maven Central metadata before committing.
- Modify: `plugins/sftp/BUILD.bazel` — add a `java_import` target for the
  vendored jar and reference it from the plugin's jvm_library deps:

```python
java_import(
    name = "sshd_sftp_lib",
    jars = ["libs/sshd-sftp-2.15.0.jar"],
)

jvm_library(
    name = "sftp",
    ...
    deps = [
        ...,
        ":sshd_sftp_lib",
        "//libraries/sshd-osgi",  # still needed for the core MINA classes
    ],
)
```

- Modify: `build/src/org/jetbrains/intellij/build/conch/ConchProperties.kt` —
  add a `PluginLayout.plugin("intellij.conch.sftp") { withJar(...) }` entry
  so the vendored jar gets bundled into the plugin's `lib/` directory in the
  shipped installer, same pattern as the `sshd-osgi` / `bouncy-castle-provider`
  bundling we already do for the ssh and vault plugins.
- Modify: `.gitattributes` (create if missing) — mark the jar as binary:
  `plugins/sftp/libs/*.jar binary`

**Risk:** A committed binary. Upsides: fully self-contained, stable across
regeneration cycles, version pinned by filename.

### Recommended: Approach B

Approach B is simpler, lower-risk, fully local to the Conch tree, and doesn't
fight the upstream auto-generator. ~500KB binary is a reasonable price.
**Executor should use Approach B unless Approach A is explicitly requested.**

- [ ] **Step 2.1: Download the jar and verify**

```
curl -L -o /tmp/sshd-sftp-2.15.0.jar \
  https://repo1.maven.org/maven2/org/apache/sshd/sshd-sftp/2.15.0/sshd-sftp-2.15.0.jar
curl -L -o /tmp/sshd-sftp-2.15.0.jar.sha512 \
  https://repo1.maven.org/maven2/org/apache/sshd/sshd-sftp/2.15.0/sshd-sftp-2.15.0.jar.sha512
openssl dgst -sha512 /tmp/sshd-sftp-2.15.0.jar
# Compare visually against /tmp/sshd-sftp-2.15.0.jar.sha512
```

- [ ] **Step 2.2: Move to plugin + declare in BUILD.bazel**

```
mkdir -p plugins/sftp/libs
mv /tmp/sshd-sftp-2.15.0.jar plugins/sftp/libs/
```

Add the `java_import` and extend the plugin's `deps`.

- [ ] **Step 2.3: Verify compile works when a new file imports SftpClient**

Create a throwaway `plugins/sftp/src/com/conch/sftp/client/SftpImportProbe.java`:

```java
package com.conch.sftp.client;

import org.apache.sshd.sftp.client.SftpClient;

final class SftpImportProbe {
    private SftpImportProbe() {}
    // Exists only to force Bazel to resolve SftpClient at compile time.
    static Class<?> probe() { return SftpClient.class; }
}
```

Run `make conch-build`. Expected: clean build. If it fails with `package
org.apache.sshd.sftp.client does not exist`, the vendored jar isn't on the
classpath — re-check `deps` in BUILD.bazel.

- [ ] **Step 2.4: Remove the probe file**

```
rm plugins/sftp/src/com/conch/sftp/client/SftpImportProbe.java
```

- [ ] **Step 2.5: Add plugin layout entry in ConchProperties.kt**

In the `productLayout.pluginLayouts` block, add:

```kotlin
PluginLayout.plugin(mainModuleName = "intellij.conch.sftp", auto = true) { spec ->
  spec.withModule("intellij.conch.sftp")
  // The vendored sshd-sftp jar is already referenced via the Bazel
  // java_import in plugins/sftp/BUILD.bazel, so the installer build's
  // plugin layout will pick it up automatically from the module's
  // runtime classpath. No additional withProjectLibrary call needed.
},
```

Run `make conch-installers-mac` and inspect the produced DMG to confirm
`Conch Workbench 2026.2 EAP.app/Contents/plugins/conch-sftp/lib/` contains
both `conch-sftp.jar` (the plugin code) and `sshd-sftp-2.15.0.jar` (the
vendored library).

---

## Task 3: Extend ConchSshClient with `openSftpSession()`

Goal: expose a way for the SFTP plugin to get an `SftpClient` that's bound
to a fresh SSH session, reusing all of `ConchSshClient`'s existing auth /
verifier / proxy-jump / bastion-auth machinery.

**Files:**
- Modify: `plugins/ssh/src/com/conch/ssh/client/ConchSshClient.java`
- Create: `plugins/ssh/src/com/conch/ssh/client/SshSftpSession.java`

**Rationale:** SFTP is a subsystem over an existing `ClientSession`. We
already have `connectSession()` which returns an authed `ClientSession`
without a shell channel — perfect for this. We don't need to duplicate any
of the connect logic; we just open a MINA `SftpClient` on top of the
returned session.

- [ ] **Step 3.1: Create the SshSftpSession wrapper record**

```java
package com.conch.ssh.client;

import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Owns a MINA {@link SftpClient} and the underlying
 * {@link ClientSession}. Closing the session closes both.
 *
 * <p>Conch's SFTP plugin takes one of these per active remote pane.
 * The pane holds the reference for the lifetime of the user's
 * connection and calls {@link #close()} when the user disconnects
 * or the pane is disposed.
 */
public final class SshSftpSession implements AutoCloseable {

    private final ClientSession session;
    private final SftpClient sftpClient;

    public SshSftpSession(@NotNull ClientSession session, @NotNull SftpClient sftpClient) {
        this.session = session;
        this.sftpClient = sftpClient;
    }

    public @NotNull SftpClient client() {
        return sftpClient;
    }

    public @NotNull ClientSession session() {
        return session;
    }

    @Override
    public void close() {
        try {
            sftpClient.close();
        } catch (IOException ignored) {
        }
        try {
            session.close(true);
        } catch (IOException ignored) {
        }
    }
}
```

- [ ] **Step 3.2: Add openSftpSession method to ConchSshClient**

```java
/**
 * Open an SFTP session to {@code host}, reusing the standard Conch
 * auth / host-key verifier / proxy-jump pipeline. Returns a
 * short-lived {@link SshSftpSession} the caller owns and must
 * {@link SshSftpSession#close()} when done.
 *
 * <p>If the host has a proxy-jump configured, the same
 * {@link BastionAuth}-based per-session identity provider that
 * {@link #connectSession} uses is applied. Pass {@code null} for
 * {@code bastionAuth} if the bastion should auth from default
 * {@code ~/.ssh/id_*} keys (matches {@link #connectSession}'s
 * null-overload behavior).
 */
public @NotNull SshSftpSession openSftpSession(
    @NotNull SshHost host,
    @NotNull SshResolvedCredential credential,
    @Nullable BastionAuth bastionAuth,
    @NotNull ServerKeyVerifier verifier
) throws SshConnectException {
    ClientSession session = connectSession(host, credential, bastionAuth, verifier);
    try {
        SftpClientFactory factory = SftpClientFactory.instance();
        SftpClient client = factory.createSftpClient(session);
        LOG.info("Conch SFTP: session established host=" + host.host() + ":" + host.port()
            + " user=" + credential.username());
        return new SshSftpSession(session, client);
    } catch (IOException e) {
        try { session.close(true); } catch (IOException ignored) {}
        throw new SshConnectException(
            SshConnectException.Kind.CHANNEL_OPEN_FAILED,
            "Could not open SFTP subsystem on " + host.host() + ":" + host.port() + ": " + e.getMessage(),
            e);
    }
}
```

Add imports at the top of ConchSshClient.java:

```java
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
```

- [ ] **Step 3.3: Verify ssh plugin still compiles**

```
cd /Users/dustin/projects/intellij-community && bash bazel.cmd build //conch/plugins/ssh:ssh
```

Expected: clean build. **This step will fail** unless the ssh plugin's
BUILD.bazel gains a dep on the sftp library. Two options:

1. Add the vendored jar's `java_import` target to the ssh plugin's deps too.
2. Move the import dep-declaration into a shared location.

**Recommended:** Don't share. The SFTP plugin **re-exports** `ConchSshClient`
as the thing the SFTP tool window calls. The ssh plugin itself doesn't need
`SftpClient` on its classpath — the factory call can live in the SFTP
plugin instead.

Restructure: instead of adding `openSftpSession()` to `ConchSshClient`,
put the SFTP-specific wrapping in a new helper class inside the SFTP plugin:

```java
// in plugins/sftp/src/com/conch/sftp/client/ConchSftpConnector.java
public final class ConchSftpConnector {
    public static SshSftpSession open(SshHost host, ...) throws SshConnectException {
        ConchSshClient sshClient = ApplicationManager.getApplication().getService(ConchSshClient.class);
        ClientSession session = sshClient.connectSession(host, credential, bastionAuth, verifier);
        SftpClient client = SftpClientFactory.instance().createSftpClient(session);
        return new SshSftpSession(session, client);
    }
}
```

`SshSftpSession` moves into the sftp plugin too (it's only an AutoCloseable
wrapper). The ssh plugin doesn't need to know SFTP exists.

**Revised Task 3:**
- Skip the `ConchSshClient.openSftpSession` addition.
- Create `SshSftpSession` under `plugins/sftp/src/com/conch/sftp/client/` instead.
- Create `ConchSftpConnector` as the bridge class the tool window calls.

---

## Task 4: Shared `FileEntry` model

**Files:**
- Create: `plugins/sftp/src/com/conch/sftp/model/FileEntry.java`
- Create: `plugins/sftp/src/com/conch/sftp/model/LocalFileEntry.java`
- Create: `plugins/sftp/src/com/conch/sftp/model/RemoteFileEntry.java`

- [ ] **Step 4.1: FileEntry interface**

```java
package com.conch.sftp.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Platform-agnostic view of a single directory entry, used by both
 * the local pane (java.nio.file.Path-backed) and the remote pane
 * (MINA SFTP-backed). The table model renders either kind via this
 * interface so there's only one code path for column layout, sort,
 * icon, and row selection.
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

- [ ] **Step 4.2: LocalFileEntry record**

```java
package com.conch.sftp.model;

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

public record LocalFileEntry(
    @NotNull Path path,
    @NotNull String name,
    long size,
    @Nullable Instant modified,
    boolean isDirectory,
    boolean isSymlink,
    @NotNull String permissions
) implements FileEntry {

    public static @NotNull LocalFileEntry of(@NotNull Path path) throws IOException {
        BasicFileAttributes basic = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String perms = "";
        try {
            PosixFileAttributes posix = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            perms = PosixFilePermissions.toString(posix.permissions());
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (Windows). Leave permissions empty.
        }
        return new LocalFileEntry(
            path,
            path.getFileName() == null ? path.toString() : path.getFileName().toString(),
            basic.size(),
            Instant.ofEpochMilli(basic.lastModifiedTime().toMillis()),
            basic.isDirectory(),
            basic.isSymbolicLink(),
            perms
        );
    }
}
```

- [ ] **Step 4.3: RemoteFileEntry record**

```java
package com.conch.sftp.model;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

public record RemoteFileEntry(
    @NotNull String name,
    long size,
    @Nullable Instant modified,
    boolean isDirectory,
    boolean isSymlink,
    @NotNull String permissions
) implements FileEntry {

    public static @NotNull RemoteFileEntry of(@NotNull SftpClient.DirEntry entry) {
        SftpClient.Attributes attrs = entry.getAttributes();
        boolean isDir = attrs.isDirectory();
        boolean isLink = (attrs.getPermissions() & SftpConstants.S_IFMT) == SftpConstants.S_IFLNK;
        String perms = formatPosixMode(attrs.getPermissions());
        return new RemoteFileEntry(
            entry.getFilename(),
            attrs.getSize(),
            attrs.getModifyTime() == null ? null : Instant.ofEpochMilli(attrs.getModifyTime().toMillis()),
            isDir,
            isLink,
            perms
        );
    }

    private static @NotNull String formatPosixMode(int mode) {
        StringBuilder sb = new StringBuilder(9);
        sb.append((mode & 0400) != 0 ? 'r' : '-');
        sb.append((mode & 0200) != 0 ? 'w' : '-');
        sb.append((mode & 0100) != 0 ? 'x' : '-');
        sb.append((mode & 0040) != 0 ? 'r' : '-');
        sb.append((mode & 0020) != 0 ? 'w' : '-');
        sb.append((mode & 0010) != 0 ? 'x' : '-');
        sb.append((mode & 0004) != 0 ? 'r' : '-');
        sb.append((mode & 0002) != 0 ? 'w' : '-');
        sb.append((mode & 0001) != 0 ? 'x' : '-');
        return sb.toString();
    }
}
```

- [ ] **Step 4.4: Build and verify**

```
make conch-build
```

Expected: clean build.

---

## Task 5: FileTableModel + file-table rendering

**Files:**
- Create: `plugins/sftp/src/com/conch/sftp/toolwindow/FileTableModel.java`

- [ ] **Step 5.1: Write the table model**

```java
package com.conch.sftp.toolwindow;

import com.conch.sftp.model.FileEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.table.AbstractTableModel;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 4-column table model: Name, Size, Modified, Permissions.
 * Entries are sorted directories-first, then alphabetical. A
 * synthetic ".." entry is prepended when the current directory has
 * a parent, so the user can navigate up by double-clicking it.
 */
public final class FileTableModel extends AbstractTableModel {

    public static final int COL_NAME = 0;
    public static final int COL_SIZE = 1;
    public static final int COL_MODIFIED = 2;
    public static final int COL_PERMISSIONS = 3;

    private static final String[] COLUMN_NAMES = {"Name", "Size", "Modified", "Permissions"};
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private List<FileEntry> entries = List.of();
    private boolean hasParent = false;

    public void setEntries(@NotNull List<? extends FileEntry> newEntries, boolean hasParent) {
        List<FileEntry> sorted = new ArrayList<>(newEntries);
        sorted.sort(Comparator
            .comparing((FileEntry e) -> !e.isDirectory())          // dirs first
            .thenComparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER));
        this.entries = sorted;
        this.hasParent = hasParent;
        fireTableDataChanged();
    }

    public @Nullable FileEntry getEntryAt(int row) {
        if (row < 0) return null;
        if (hasParent && row == 0) return null;  // ".." row
        int offset = hasParent ? 1 : 0;
        int index = row - offset;
        if (index < 0 || index >= entries.size()) return null;
        return entries.get(index);
    }

    public boolean isParentRow(int row) {
        return hasParent && row == 0;
    }

    @Override
    public int getRowCount() {
        return entries.size() + (hasParent ? 1 : 0);
    }

    @Override
    public int getColumnCount() {
        return COLUMN_NAMES.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMN_NAMES[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (hasParent && rowIndex == 0) {
            return switch (columnIndex) {
                case COL_NAME -> "..";
                default -> "";
            };
        }
        int offset = hasParent ? 1 : 0;
        FileEntry entry = entries.get(rowIndex - offset);
        return switch (columnIndex) {
            case COL_NAME -> entry.name() + (entry.isDirectory() ? "/" : "");
            case COL_SIZE -> entry.isDirectory() ? "" : formatSize(entry.size());
            case COL_MODIFIED -> entry.modified() == null ? "" : TIME_FORMAT.format(entry.modified());
            case COL_PERMISSIONS -> entry.permissions();
            default -> "";
        };
    }

    private static @NotNull String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
```

---

## Task 6: LocalFilePane

**Files:**
- Create: `plugins/sftp/src/com/conch/sftp/toolwindow/LocalFilePane.java`

- [ ] **Step 6.1: Pane skeleton with table + toolbar + nav**

- BorderLayout:
  - NORTH: `ActionToolbar` with `Up`, `Refresh`, `Go Home`, plus a
    read-only `JTextField` showing the current path
  - CENTER: `JBScrollPane` wrapping a `JBTable` backed by `FileTableModel`
  - Nothing in SOUTH / EAST / WEST
- Double-click handler: if parent row → go up; if directory row → descend;
  if file row → noop (no open-in-editor in Phase 1, no transfers yet)
- Reload trigger: `reload(Path path)` — runs directory listing on
  `AppExecutorUtil.getAppExecutorService()`, updates the model on EDT via
  `SwingUtilities.invokeLater`
- Persist current dir on each successful navigation via
  `ConchSftpConfig.getInstance().setLastLocalPath(...)`
- Initial load: `ConchSftpConfig.getInstance().getLastLocalPath()`,
  fallback `Path.of(System.getProperty("user.home"))` if missing or
  the stored path no longer exists / isn't a directory

Key method:

```java
private void reload(@NotNull Path dir) {
    pathField.setText(dir.toString());
    AppExecutorUtil.getAppExecutorService().submit(() -> {
        List<LocalFileEntry> entries = new ArrayList<>();
        IOException error = null;
        try (Stream<Path> stream = Files.list(dir)) {
            Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                try {
                    entries.add(LocalFileEntry.of(it.next()));
                } catch (IOException e) {
                    // skip unreadable file but keep going
                }
            }
        } catch (IOException e) {
            error = e;
        }
        List<LocalFileEntry> snapshot = entries;
        IOException finalError = error;
        SwingUtilities.invokeLater(() -> {
            if (finalError != null) {
                Messages.showErrorDialog(panel,
                    "Could not list directory:\n" + dir + "\n\n" + finalError.getMessage(),
                    "Local Directory Error");
                return;
            }
            currentDir = dir;
            model.setEntries(snapshot, dir.getParent() != null);
            ConchSftpConfig.getInstance().setLastLocalPath(dir.toString());
        });
    });
}
```

---

## Task 7: RemoteFilePane

**Files:**
- Create: `plugins/sftp/src/com/conch/sftp/toolwindow/RemoteFilePane.java`

The biggest pane by far. Layout:

- NORTH: horizontal panel with
  - Host picker `ComboBox<SshHost>`, populated from `HostStore.getHosts()`
  - Connect button (enabled when no active session)
  - Disconnect button (enabled when active session exists)
  - Up / Refresh toolbar buttons
  - Read-only path field
- CENTER: `JBScrollPane` wrapping a `JBTable` backed by the same
  `FileTableModel` class
- A status label at SOUTH showing connection state ("Not connected",
  "Connecting...", "Connected to host:port", "Disconnected")

State machine:

```
  DISCONNECTED → (user clicks Connect) → CONNECTING →
    (success) → CONNECTED (listing loads)
    (auth/network failure) → DISCONNECTED + error dialog
  CONNECTED → (user clicks Disconnect) → DISCONNECTED (session closed)
  CONNECTED → (user selects different host in picker) → (prompt to disconnect first) → DISCONNECTED
```

- [ ] **Step 7.1: Write ConchSftpConnector bridge class**

```java
// plugins/sftp/src/com/conch/sftp/client/ConchSftpConnector.java
package com.conch.sftp.client;

import com.conch.ssh.client.ConchServerKeyVerifier;
import com.conch.ssh.client.ConchSshClient;
import com.conch.ssh.client.SshConnectException;
import com.conch.ssh.client.SshResolvedCredential;
import com.conch.ssh.model.SshHost;
import com.intellij.openapi.application.ApplicationManager;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

public final class ConchSftpConnector {
    private ConchSftpConnector() {}

    public static @NotNull SshSftpSession open(
        @NotNull SshHost host,
        @NotNull SshResolvedCredential credential,
        @Nullable ConchSshClient.BastionAuth bastionAuth
    ) throws SshConnectException {
        ConchSshClient client = ApplicationManager.getApplication().getService(ConchSshClient.class);
        if (client == null) {
            client = new ConchSshClient();  // dev fallback
        }
        ClientSession session = client.connectSession(
            host, credential, bastionAuth, new ConchServerKeyVerifier());
        try {
            SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session);
            return new SshSftpSession(session, sftpClient);
        } catch (IOException e) {
            try { session.close(true); } catch (IOException ignored) {}
            throw new SshConnectException(
                SshConnectException.Kind.CHANNEL_OPEN_FAILED,
                "Could not open SFTP subsystem on " + host.host() + ":" + host.port()
                    + " — " + e.getMessage(),
                e);
        }
    }
}
```

- [ ] **Step 7.2: Reuse the AuthSource credential-fetch pattern**

Credential resolution (including bastion for proxy-jump hosts) should follow
`SshSessionProvider.createSession()` exactly — vault / keyfile / prompt
password dispatch + upstream bastion resolution. Rather than duplicating that
whole code path, **extract a reusable helper** from `SshSessionProvider` into
a new package-accessible class inside the ssh plugin:

```java
// plugins/ssh/src/com/conch/ssh/credentials/HostCredentialBundle.java
public record HostCredentialBundle(
    @NotNull SshResolvedCredential target,
    @Nullable ConchSshClient.BastionAuth bastion
) implements AutoCloseable {
    @Override public void close() {
        target.close();
        if (bastion != null) bastion.credential().close();
    }

    public static @Nullable HostCredentialBundle resolveForHost(@NotNull SshHost host) {
        // Run on EDT. Returns null if user cancelled any prompt.
        // Reuses AuthSource + resolveBastionForTarget from SshSessionProvider.
    }
}
```

The implementation body is a straight cut-paste from
`SshSessionProvider.authSourceFor()` + `resolveBastionForTarget()`. After
extraction, update `SshSessionProvider` to use the helper. This cleanup is
scope-creep for Phase 1 but unblocks Task 7 cleanly.

- [ ] **Step 7.3: RemoteFilePane implementation**

```java
private void connect(@NotNull SshHost host) {
    statusLabel.setText("Connecting to " + host.label() + "...");
    setEnabledUi(false);

    HostCredentialBundle bundle = HostCredentialBundle.resolveForHost(host);
    if (bundle == null) {
        statusLabel.setText("Not connected");
        setEnabledUi(true);
        return;
    }

    ProgressManager.getInstance().run(new Task.Modal(
        null,
        "Opening SFTP to " + host.label() + "...",
        true
    ) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
            indicator.setIndeterminate(true);
            try {
                SshSftpSession session = ConchSftpConnector.open(
                    host, bundle.target(), bundle.bastion());
                SwingUtilities.invokeLater(() -> {
                    activeSession = session;
                    currentHost = host;
                    statusLabel.setText("Connected to " + host.host() + ":" + host.port());
                    setEnabledUi(true);
                    navigateRemote("/home/" + bundle.target().username());  // or via pwd
                });
            } catch (SshConnectException e) {
                SwingUtilities.invokeLater(() -> {
                    Messages.showErrorDialog(panel,
                        "SFTP connection failed:\n" + e.getMessage(),
                        "SFTP Error");
                    statusLabel.setText("Not connected");
                    setEnabledUi(true);
                });
            } finally {
                // Target credential was consumed by connectSession. Bastion
                // credential was closed inside ConchSshClient.connectSession's
                // finally block. The bundle's own close is now a no-op.
            }
        }
    });
}

private void navigateRemote(@NotNull String path) {
    if (activeSession == null) return;
    AppExecutorUtil.getAppExecutorService().submit(() -> {
        List<RemoteFileEntry> entries = new ArrayList<>();
        IOException error = null;
        try {
            for (SftpClient.DirEntry dirEntry : activeSession.client().readDir(path)) {
                String name = dirEntry.getFilename();
                if (".".equals(name) || "..".equals(name)) continue;
                entries.add(RemoteFileEntry.of(dirEntry));
            }
        } catch (IOException e) {
            error = e;
        }
        List<RemoteFileEntry> snapshot = entries;
        IOException finalError = error;
        SwingUtilities.invokeLater(() -> {
            if (finalError != null) {
                Messages.showErrorDialog(panel,
                    "Could not list directory:\n" + path + "\n\n" + finalError.getMessage(),
                    "SFTP Directory Error");
                return;
            }
            currentRemotePath = path;
            pathField.setText(path);
            boolean hasParent = !"/".equals(path);
            model.setEntries(snapshot, hasParent);
        });
    });
}

private void disconnect() {
    if (activeSession == null) return;
    activeSession.close();
    activeSession = null;
    currentHost = null;
    currentRemotePath = null;
    model.setEntries(List.of(), false);
    statusLabel.setText("Disconnected");
}
```

- [ ] **Step 7.4: Initial remote path detection**

When a remote session opens, we need to know where to start browsing. Two
options:
1. Use `SftpClient.canonicalPath(".")` — portable, asks SFTP for the session's
   default directory (usually `$HOME`).
2. Hardcode `/home/<username>` — fragile (not every distro uses `/home`).

Use option 1. MINA exposes this via `SftpClient.getSession().normalize()` or
the `canonicalize` method. Test against a Linux box + a macOS target.

---

## Task 8: Persistence (ConchSftpConfig)

**Files:**
- Create: `plugins/sftp/src/com/conch/sftp/persistence/ConchSftpConfig.java`

Standard IntelliJ `PersistentStateComponent` pattern, matching
`ConchTerminalConfig` in `core/src/com/conch/core/settings/`.

```java
package com.conch.sftp.persistence;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(
    name = "ConchSftpConfig",
    storages = @Storage("conch-sftp.xml")
)
public final class ConchSftpConfig implements PersistentStateComponent<ConchSftpConfig.State> {

    public static final class State {
        public @Nullable String lastLocalPath;
        public @Nullable String lastRemoteHostId;  // SshHost.id().toString()
    }

    private State state = new State();

    public static @NotNull ConchSftpConfig getInstance() {
        return ApplicationManager.getApplication().getService(ConchSftpConfig.class);
    }

    public @Nullable String getLastLocalPath() { return state.lastLocalPath; }
    public void setLastLocalPath(@Nullable String path) { state.lastLocalPath = path; }

    public @Nullable String getLastRemoteHostId() { return state.lastRemoteHostId; }
    public void setLastRemoteHostId(@Nullable String hostId) { state.lastRemoteHostId = hostId; }

    @Override
    public @NotNull State getState() { return state; }

    @Override
    public void loadState(@NotNull State state) { this.state = state; }
}
```

Registered in `plugin.xml` via the `<applicationService>` declaration already
added in Task 1.

---

## Task 9: SftpToolWindow + Factory

**Files:**
- Create: `plugins/sftp/src/com/conch/sftp/toolwindow/SftpToolWindow.java`
- Create: `plugins/sftp/src/com/conch/sftp/toolwindow/SftpToolWindowFactory.java`

- [ ] **Step 9.1: Factory**

```java
package com.conch.sftp.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class SftpToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        SftpToolWindow panel = new SftpToolWindow(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setCloseable(false);
        toolWindow.getContentManager().addContent(content);
    }
}
```

- [ ] **Step 9.2: SftpToolWindow wrapper JPanel**

```java
package com.conch.sftp.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.ui.JBSplitter;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public final class SftpToolWindow extends JPanel {

    public SftpToolWindow(@NotNull Project project) {
        super(new BorderLayout());

        LocalFilePane local = new LocalFilePane(project);
        RemoteFilePane remote = new RemoteFilePane(project);

        JBSplitter splitter = new JBSplitter(false, 0.5f);  // horizontal split, 50/50
        splitter.setFirstComponent(local);
        splitter.setSecondComponent(remote);

        add(splitter, BorderLayout.CENTER);
    }
}
```

---

## Task 10: Search Everywhere tab

**Files:**
- Create: `plugins/sftp/src/com/conch/sftp/palette/SftpSearchEverywhereContributor.java`
- Modify: `core/src/com/conch/core/palette/ConchTabsCustomizationStrategy.java` —
  add `"ConchSftp"` to the `ALLOWED_TAB_IDS` set.
- Modify: `plugins/sftp/resources/META-INF/plugin.xml` — register
  `<searchEverywhereContributor implementation="com.conch.sftp.palette.SftpSearchEverywhereContributor$Factory"/>`

Phase 1 scope for the SE tab: a single row per *connection action*:

- "Open SFTP tool window" (always)
- One row per saved host: "Connect SFTP to <host.label()>"

Selecting a row activates the tool window via
`ToolWindowManager.getInstance(project).getToolWindow("Conch SFTP").show()`,
and for the per-host rows, pre-selects that host in the remote pane's
picker and fires `connect()`.

Reference implementation: `plugins/tunnels/src/com/conch/tunnels/palette/TunnelsSearchEverywhereContributor.java`.
Copy and adapt.

---

## Task 11: End-to-end verification

- [ ] **Step 11.1: make conch-build** — clean compile, no new errors
- [ ] **Step 11.2: make conch** — launch the dev Conch, Conch SFTP tool
  window button appears at the bottom of the frame
- [ ] **Step 11.3: Click Conch SFTP** — tool window opens. Local pane shows
  current directory (last browsed or `$HOME`). Remote pane shows "Not
  connected" with host picker populated from HostStore.
- [ ] **Step 11.4: Navigate local** — double-click a subdirectory,
  contents update. Double-click ".." to go up. Close the tool window, reopen
  it — local pane restores the last-visited directory.
- [ ] **Step 11.5: Connect remote** — pick a host from the dropdown, click
  Connect. Credential prompt dialog runs if needed. SFTP session opens,
  remote pane populates with the user's home directory, status label reads
  "Connected to host:port".
- [ ] **Step 11.6: Navigate remote** — double-click a subdirectory, update.
  Double-click "..", go up. All operations use the application executor;
  EDT never blocks.
- [ ] **Step 11.7: Disconnect** — click Disconnect, session closes, pane
  clears, status reads "Disconnected".
- [ ] **Step 11.8: Reconnect a different host** — switch picker, hit
  Connect. Old session closes cleanly, new one opens.
- [ ] **Step 11.9: Search Everywhere integration** — press Cmd+Shift+P, type
  "sftp", the tab appears with "Open SFTP tool window" + per-host entries.
- [ ] **Step 11.10: make conch-installers** — all 8 archives build cleanly,
  inspect the Mac DMG to confirm `conch-sftp` plugin directory exists with
  `conch-sftp.jar` + `sshd-sftp-2.15.0.jar` in its `lib/`.

---

## Phase 1 exit criteria

- You can open the SFTP tool window, see both local and remote panes
- Local pane navigates your filesystem, persists state across restarts
- Remote pane connects to any saved SshHost using the existing credential
  dispatch (vault / keyfile / prompt) and browses its directory tree
- Proxy-jump hosts work end-to-end (bastion credential resolution + session
  establishment) because we reuse `ConchSshClient.connectSession`
- Nothing is transferred yet. That's Phase 2.

If all 11 verification steps pass, commit and move to Phase 2.

---

## Appendix: Phase 2+ preview

Rough shape for the next phases, recorded here so Phase 1 decisions
(interface shapes, model layout, pane ownership) don't paint us into
a corner:

- **Phase 2:** Toolbar buttons `Copy →` / `← Copy` on the splitter divider.
  Each button kicks off a `Task.Backgroundable` that reads source via the
  appropriate backend (`Files.newInputStream` / `SftpClient.read`) and writes
  to the other side. Collision: MINA's `SftpClient.write(..., CREATE_NEW)`
  throws on conflict — catch, show FileZilla-style prompt with Overwrite /
  Overwrite Always / Rename / Skip / Skip Always, remember choice for the
  batch.
- **Phase 3:** New `SshSessionRegistry` service in the ssh plugin; every
  terminal tab opened via `ConchSshClient.connect()` registers its
  `ClientSession` there. The SFTP tool window subscribes; when the active
  terminal tab points at a host, the SFTP remote pane auto-connects to
  that host via `openSftpSession()` (reusing auth state is trickier — may
  need a parallel connectSession since the terminal's session has a shell
  channel open and spawning SFTP on the same session is fine but concurrent
  SFTP + interactive shell may interact badly; decide at Phase 3 design time).
- **Phase 4:** `TransferManager` application service with a
  `ConcurrentLinkedDeque<TransferJob>` + thread pool with configurable
  parallelism (default 3). Transfer log panel at the bottom of
  `SftpToolWindow`.
- **Phase 5:** Right-click context menus in both panes for rename / delete /
  new folder / chmod. Recursive operations via a visitor that walks
  `SftpClient.readDir` recursively.
- **Phase 6:** Settings panel with transfer engine picker. rsync
  implementation shells out via `ProcessBuilder` invoking
  `rsync -e "ssh -J bastion@host" source target`. scp similar.

---

## Execution hint

This plan has 11 tasks with ~35 steps. Recommended execution model:
`superpowers:subagent-driven-development` with fresh implementer + reviewer
per task. Tasks 1–2 (scaffolding + library) are pre-req for everything else;
tasks 4–5 (model + table) are pre-req for tasks 6–7 (panes); task 9 (tool
window wrapper) is pre-req for task 10 (SE tab) and task 11 (verification).
A natural task dependency DAG:

```
1 (scaffold) ─┬─> 2 (lib) ──> 3 (connector) ─────┐
              └─> 4 (model) ──> 5 (table) ─┬──> 6 (local pane) ─┐
                                           └──> 7 (remote pane) ┴─> 9 (tool window) ─> 10 (SE) ─> 11 (verify)
                           8 (persistence) ──────────────────┘
```
