# SFTP Virtual Filesystem & Unified Save Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a real `SftpVirtualFileSystem` extending `DeprecatedVirtualFileSystem` so the IntelliJ `FileChooserDialog` / `FileSaverDialog` work transparently for SFTP hosts. Wire `Cmd+Shift+S` "Save Scratch to Remote…" through the new VFS. Migrate the existing SFTP-double-click flow off the temp-file/`RemoteFileBinding` machinery and onto the VFS, leaving a single canonical "remote file in editor" code path.

**Architecture:** `SftpVirtualFileSystem` is registered under protocol `sftp` and routes operations to multiple hosts via a single `SftpSessionManager` application service that owns reference-counted SFTP sessions. `SftpVirtualFile` extends plain `VirtualFile` (not `NewVirtualFile`) and owns its own per-instance attribute and listing caches. After this lands, every "remote file in editor" goes through the VFS; `RemoteFileBinding`, `RemoteSaveListener`, the temp-file directory, and `SftpSingleFileTransfer` are deleted.

**Tech Stack:** Java 21, Bazel, IntelliJ Platform (`DeprecatedVirtualFileSystem`, `VirtualFile`, `FileSaverDialog`), Apache SSHD SftpClient, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-04-14-sftp-virtual-filesystem-design.md`

---

## Orientation for the Implementing Engineer

Read these reference files before touching code:

- `/Users/dustin/projects/intellij-community/platform/core-api/src/com/intellij/openapi/vfs/VirtualFileSystem.java` — abstract base. Note `findFileByPath`, `refresh`, `refreshAndFindFileByPath`, and the protected mutation methods.
- `/Users/dustin/projects/intellij-community/platform/core-api/src/com/intellij/openapi/vfs/DeprecatedVirtualFileSystem.java` — our base class. Provides `addVirtualFileListener`/`removeVirtualFileListener`, the `EventDispatcher`, `fire*` helpers, and default `UnsupportedOperationException` stubs for the mutation methods. **Despite the name, this class is NOT marked `@Deprecated`** and is the supported base for non-`ManagingFS` VFS implementations.
- `/Users/dustin/projects/intellij-community/platform/core-api/src/com/intellij/openapi/vfs/VirtualFile.java` — abstract base for our `SftpVirtualFile`. The platform expects implementations of `getName`, `getPath`, `getFileSystem`, `getParent`, `isDirectory`, `isWritable`, `isValid`, `getChildren`, `contentsToByteArray`, `getInputStream`, `getOutputStream`, `getLength`, `getTimeStamp`, `getModificationStamp`, `refresh`. Read each method's javadoc for contract details.
- `/Users/dustin/projects/intellij-community/platform/core-impl/src/com/intellij/testFramework/LightVirtualFile.java` (or wherever it lives in your tree) — concrete reference implementation for a non-`NewVirtualFile` `VirtualFile`. Mirror its structure for things like `getModificationStamp` semantics.
- `plugins/sftp/src/com/termlab/sftp/client/SshSftpSession.java` — `SshSftpSession.client()` returns `org.apache.sshd.sftp.client.SftpClient`. The relevant `SftpClient` methods are `read(String) → InputStream`, `write(String) → OutputStream`, `readDir(String) → Iterable<DirEntry>`, `stat(String) → Attributes`, `rename(String, String)`, `remove(String)`, `canonicalPath(String)`.
- `plugins/sftp/src/com/termlab/sftp/client/TermLabSftpConnector.java` — static `open(SshHost, SshResolvedCredential, TermLabSshClient.BastionAuth) → SshSftpSession`. The `SftpSessionManager` will call this.
- `plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java:234-284` — current `connect(SshHost)` method. Lines 234–284 hold the modal-progress connect flow that we'll lift into `SftpSessionManager`. `disconnect()` is at line 327.
- `plugins/ssh/src/com/termlab/ssh/credentials/HostCredentialBundle.java` — `resolveForHost(SshHost)` returns a `HostCredentialBundle` (or null). `bundle.target()` returns `SshResolvedCredential`, `bundle.bastion()` returns `TermLabSshClient.BastionAuth`.
- `plugins/vault/test/com/termlab/vault/TestRunner.java` — copy-paste template for the `SftpTestRunner`.
- `plugins/editor/test/com/termlab/editor/TestRunner.java` — same template, for editor tests.
- `docs/superpowers/specs/2026-04-14-sftp-virtual-filesystem-design.md` — the design document. Re-read sections "SftpVirtualFileSystem" / "SftpVirtualFile" / "SftpSessionManager" / "Migration" before each phase.

**Build commands** (from intellij-community workspace root):

- Build SFTP: `bash bazel.cmd build //termlab/plugins/sftp:sftp`
- Build editor: `bash bazel.cmd build //termlab/plugins/editor:editor`
- Build product: `bash bazel.cmd build //termlab:termlab_run`
- Run SFTP unit tests: `bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner` (the runner target is added in Task 1).
- Run editor unit tests: `bash bazel.cmd run //termlab/plugins/editor:editor_test_runner`
- Run TermLab from source: `bash bazel.cmd run //termlab:termlab_run`

**Commit convention:** lowercase conventional-commit prefixes (`feat(sftp):`, `feat(editor):`, `refactor(sftp):`, `chore(editor):`, etc.). Each task ends with a commit step with the exact message.

**Strict ordering:** tasks must be done in order. Later tasks reference symbols introduced in earlier tasks.

---

## Task 1: Add SFTP plugin test infrastructure

The SFTP plugin currently has no test runner. We need one for the unit tests in Tasks 2 and 3.

**Files:**
- Modify: `plugins/sftp/BUILD.bazel`
- Create: `plugins/sftp/test/com/termlab/sftp/TestRunner.java`

- [ ] **Step 1: Add test targets to `plugins/sftp/BUILD.bazel`**

The current `BUILD.bazel` has only the `sftp_resources` resourcegroup, the `sftp` jvm_library, and `exports_files`. Append the test infrastructure modeled on `plugins/vault/BUILD.bazel`. Add a `load("@rules_java//java:defs.bzl", "java_binary")` at the top, then append after the existing `jvm_library`:

```bazel
jvm_library(
    name = "sftp_test_lib",
    module_name = "intellij.termlab.sftp.tests",
    visibility = ["//visibility:public"],
    srcs = glob(["test/**/*.java"], allow_empty = True),
    deps = [
        ":sftp",
        "//termlab/sdk",
        "//termlab/plugins/ssh",
        "//termlab/plugins/sftp/libs:sshd_sftp",
        "//libraries/sshd-osgi",
        "//libraries/junit5",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
        "@lib//:jetbrains-annotations",
        "@lib//:kotlin-stdlib",
    ],
)

java_binary(
    name = "sftp_test_runner",
    main_class = "com.termlab.sftp.TestRunner",
    runtime_deps = [
        ":sftp_test_lib",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
    ],
)
```

- [ ] **Step 2: Create `plugins/sftp/test/com/termlab/sftp/TestRunner.java`**

Modeled on `plugins/vault/test/com/termlab/vault/TestRunner.java`:

```java
package com.termlab.sftp;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

/**
 * Standalone test runner for the SFTP plugin's unit tests. Runs
 * the full {@code com.termlab.sftp} test tree via the JUnit 5
 * platform launcher.
 *
 * <p>Usage:
 * <pre>
 *   bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner
 * </pre>
 */
public final class TestRunner {

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("com.termlab.sftp"))
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

- [ ] **Step 3: Build the test target**

Run: `bash bazel.cmd build //termlab/plugins/sftp:sftp_test_lib`
Expected: `Build completed successfully`. The library may compile zero source files at this point — that's fine because of `allow_empty = True`.

- [ ] **Step 4: Run the (empty) test runner to confirm it works**

Run: `bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner`
Expected: a summary printout showing `0 tests found` and exit code 0. If the runner errors with `ClassNotFoundException com.termlab.sftp.TestRunner`, the file isn't being picked up — check the path is exactly `plugins/sftp/test/com/termlab/sftp/TestRunner.java`.

- [ ] **Step 5: Commit**

```bash
git add plugins/sftp/BUILD.bazel plugins/sftp/test/com/termlab/sftp/TestRunner.java
git commit -m "feat(sftp): add JUnit 5 test runner target for SFTP plugin"
```

---

## Task 2: `SftpUrl` parsing (TDD)

URL format: `sftp://<hostId>//<absolute-remote-path>`. Note the double slash — `<absolute-remote-path>` itself begins with `/`. A small record + parse/compose helpers.

**Files:**
- Create: `plugins/sftp/src/com/termlab/sftp/vfs/SftpUrl.java`
- Create: `plugins/sftp/test/com/termlab/sftp/vfs/SftpUrlTest.java`

- [ ] **Step 1: Write the failing test**

`plugins/sftp/test/com/termlab/sftp/vfs/SftpUrlTest.java`:

```java
package com.termlab.sftp.vfs;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SftpUrlTest {

    private static final UUID UUID_A = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID UUID_B = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void composeBuildsExpectedUrl() {
        String url = SftpUrl.compose(UUID_A, "/etc/nginx/nginx.conf");
        assertEquals("sftp://550e8400-e29b-41d4-a716-446655440000//etc/nginx/nginx.conf", url);
    }

    @Test
    void composeWithRootPath() {
        String url = SftpUrl.compose(UUID_A, "/");
        assertEquals("sftp://550e8400-e29b-41d4-a716-446655440000//", url);
    }

    @Test
    void parseRoundTrips() {
        String url = "sftp://550e8400-e29b-41d4-a716-446655440000//etc/nginx/nginx.conf";
        SftpUrl parsed = SftpUrl.parse(url);
        assertNotNull(parsed);
        assertEquals(UUID_A, parsed.hostId());
        assertEquals("/etc/nginx/nginx.conf", parsed.remotePath());
        assertEquals(url, SftpUrl.compose(parsed.hostId(), parsed.remotePath()));
    }

    @Test
    void parseWithRootPath() {
        SftpUrl parsed = SftpUrl.parse("sftp://550e8400-e29b-41d4-a716-446655440000//");
        assertNotNull(parsed);
        assertEquals(UUID_A, parsed.hostId());
        assertEquals("/", parsed.remotePath());
    }

    @Test
    void parseWithSpacesInPath() {
        SftpUrl parsed = SftpUrl.parse("sftp://550e8400-e29b-41d4-a716-446655440000//tmp/with space/file.txt");
        assertNotNull(parsed);
        assertEquals("/tmp/with space/file.txt", parsed.remotePath());
    }

    @Test
    void parseRejectsMissingProtocol() {
        assertNull(SftpUrl.parse("550e8400-e29b-41d4-a716-446655440000//etc/foo"));
    }

    @Test
    void parseRejectsWrongProtocol() {
        assertNull(SftpUrl.parse("file:///etc/foo"));
    }

    @Test
    void parseRejectsMissingDoubleSlash() {
        // hostId without the double-slash separator is malformed
        assertNull(SftpUrl.parse("sftp://550e8400-e29b-41d4-a716-446655440000/etc/foo"));
    }

    @Test
    void parseRejectsMissingHostId() {
        assertNull(SftpUrl.parse("sftp:////etc/foo"));
    }

    @Test
    void parseRejectsInvalidUuid() {
        assertNull(SftpUrl.parse("sftp://not-a-uuid//etc/foo"));
    }

    @Test
    void differentHostsCompareUnequal() {
        SftpUrl a = new SftpUrl(UUID_A, "/etc/foo");
        SftpUrl b = new SftpUrl(UUID_B, "/etc/foo");
        assertEquals(false, a.equals(b));
    }
}
```

- [ ] **Step 2: Run test, confirm red**

Run: `bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner`
Expected: compile failure ("cannot find symbol: class SftpUrl"). That's the red state.

- [ ] **Step 3: Implement `plugins/sftp/src/com/termlab/sftp/vfs/SftpUrl.java`**

```java
package com.termlab.sftp.vfs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Parsed form of an SFTP VFS URL. Format:
 *
 * <pre>
 *   sftp://&lt;hostId&gt;//&lt;absolute-remote-path&gt;
 * </pre>
 *
 * The double slash separates the host UUID from the absolute
 * remote path that itself begins with {@code /}. The host UUID is
 * the stable identifier from {@code SshHost.id()}.
 */
public record SftpUrl(@NotNull UUID hostId, @NotNull String remotePath) {

    public static final String PROTOCOL = "sftp";
    private static final String PROTOCOL_PREFIX = PROTOCOL + "://";

    public static @NotNull String compose(@NotNull UUID hostId, @NotNull String remotePath) {
        if (!remotePath.startsWith("/")) {
            throw new IllegalArgumentException("remotePath must be absolute (start with /): " + remotePath);
        }
        return PROTOCOL_PREFIX + hostId + "/" + remotePath;
    }

    public static @Nullable SftpUrl parse(@NotNull String url) {
        if (!url.startsWith(PROTOCOL_PREFIX)) return null;
        String afterProtocol = url.substring(PROTOCOL_PREFIX.length());
        // Find the first '/' which separates hostId from the absolute remote path.
        int firstSlash = afterProtocol.indexOf('/');
        if (firstSlash <= 0) return null; // no hostId or empty hostId
        String hostIdString = afterProtocol.substring(0, firstSlash);
        String remotePath = afterProtocol.substring(firstSlash + 1);
        if (remotePath.isEmpty() || !remotePath.startsWith("/")) return null;
        UUID hostId;
        try {
            hostId = UUID.fromString(hostIdString);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new SftpUrl(hostId, remotePath);
    }
}
```

- [ ] **Step 4: Run tests, confirm green**

Run: `bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner`
Expected: `11 tests successful, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add plugins/sftp/src/com/termlab/sftp/vfs/SftpUrl.java plugins/sftp/test/com/termlab/sftp/vfs/SftpUrlTest.java
git commit -m "feat(sftp): SftpUrl parse/compose for the new VFS"
```

---

## Task 3: `SftpSessionManager` skeleton + reference counting (TDD)

Application service that owns SFTP sessions per host UUID. Reference-counted lifecycle. Connector is injected so we can mock it in tests.

**Files:**
- Create: `plugins/sftp/src/com/termlab/sftp/session/SftpConnector.java` — small interface (lets us inject a fake in tests without depending on `TermLabSftpConnector`'s static method).
- Create: `plugins/sftp/src/com/termlab/sftp/session/DefaultSftpConnector.java` — production impl that delegates to `TermLabSftpConnector.open`.
- Create: `plugins/sftp/src/com/termlab/sftp/session/SftpSessionManager.java`
- Create: `plugins/sftp/test/com/termlab/sftp/session/SftpSessionManagerTest.java`

- [ ] **Step 1: Create the connector interface and the default implementation**

`plugins/sftp/src/com/termlab/sftp/session/SftpConnector.java`:

```java
package com.termlab.sftp.session;

import com.termlab.sftp.client.SshSftpSession;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.credentials.HostCredentialBundle;
import com.termlab.ssh.model.SshHost;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Indirection over {@link com.termlab.sftp.client.TermLabSftpConnector#open}
 * so that {@link SftpSessionManager} can be unit-tested with a fake.
 */
public interface SftpConnector {

    /**
     * Opens a new SFTP session to the given host. Resolves credentials via
     * {@link HostCredentialBundle#resolveForHost} unless an injected
     * implementation supplies them differently.
     *
     * @return a freshly-opened session.
     * @throws SshConnectException if the connection fails for any reason.
     */
    @NotNull SshSftpSession open(@NotNull SshHost host) throws SshConnectException;

    /**
     * Resolves credentials for the given host. Production code goes
     * through the vault plugin; tests can return a stub.
     */
    @Nullable HostCredentialBundle resolveCredentials(@NotNull SshHost host);
}
```

`plugins/sftp/src/com/termlab/sftp/session/DefaultSftpConnector.java`:

```java
package com.termlab.sftp.session;

import com.termlab.sftp.client.TermLabSftpConnector;
import com.termlab.sftp.client.SshSftpSession;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.credentials.HostCredentialBundle;
import com.termlab.ssh.model.SshHost;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class DefaultSftpConnector implements SftpConnector {

    @Override
    public @NotNull SshSftpSession open(@NotNull SshHost host) throws SshConnectException {
        HostCredentialBundle bundle = HostCredentialBundle.resolveForHost(host);
        if (bundle == null) {
            throw new SshConnectException(
                SshConnectException.Kind.AUTH_FAILED,
                "Could not resolve credentials for " + host.label(),
                null);
        }
        return TermLabSftpConnector.open(host, bundle.target(), bundle.bastion());
    }

    @Override
    public @Nullable HostCredentialBundle resolveCredentials(@NotNull SshHost host) {
        return HostCredentialBundle.resolveForHost(host);
    }
}
```

- [ ] **Step 2: Write the failing test**

`plugins/sftp/test/com/termlab/sftp/session/SftpSessionManagerTest.java`:

```java
package com.termlab.sftp.session;

import com.termlab.sftp.client.SshSftpSession;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.credentials.HostCredentialBundle;
import com.termlab.ssh.model.SshHost;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SftpSessionManagerTest {

    private static SshHost makeHost(UUID id) {
        // SshHost record signature: (UUID id, String label, String host,
        // int port, String username, ...). Adjust extra fields if your
        // signature differs — all the test needs is a stable id.
        return new SshHost(id, "label-" + id, "host-" + id, 22, "user",
            null, null, null, null);
    }

    private static final class FakeConnector implements SftpConnector {
        final AtomicInteger openCount = new AtomicInteger();

        @Override
        public @NotNull SshSftpSession open(@NotNull SshHost host) {
            openCount.incrementAndGet();
            // SshSftpSession requires a real ClientSession + SftpClient.
            // For unit tests we never actually call .client() or .close()
            // so we can pass nulls if the cast can be suppressed; but the
            // cleanest path is to use a tiny test double subclass.
            return new TestSshSftpSession();
        }

        @Override
        public @Nullable HostCredentialBundle resolveCredentials(@NotNull SshHost host) {
            return null;
        }
    }

    /**
     * Test double for SshSftpSession. The real class is final; we can't
     * subclass it, so we cast nulls into the constructor and rely on the
     * manager not actually calling client() or close() in unit tests.
     * If your SshSftpSession constructor enforces non-null, change this to
     * use Mockito or extract an interface.
     */
    private static final class TestSshSftpSession extends SshSftpSession {
        TestSshSftpSession() {
            super(null, null);
        }
        @Override
        public void close() {
            // no-op for tests
        }
    }

    @Test
    void acquireOpensNewSessionWhenNonePresent() throws SshConnectException {
        FakeConnector connector = new FakeConnector();
        SftpSessionManager manager = new SftpSessionManager(connector);
        UUID hostId = UUID.randomUUID();
        SshHost host = makeHost(hostId);

        SshSftpSession session = manager.acquire(host, this);

        assertNotNull(session);
        assertEquals(1, connector.openCount.get());
        assertSame(session, manager.peek(hostId));
    }

    @Test
    void acquireReturnsSameSessionForSameHost() throws SshConnectException {
        FakeConnector connector = new FakeConnector();
        SftpSessionManager manager = new SftpSessionManager(connector);
        SshHost host = makeHost(UUID.randomUUID());

        Object owner1 = new Object();
        Object owner2 = new Object();
        SshSftpSession a = manager.acquire(host, owner1);
        SshSftpSession b = manager.acquire(host, owner2);

        assertSame(a, b);
        assertEquals(1, connector.openCount.get());
    }

    @Test
    void releaseDoesNotCloseWhileOtherOwnersPresent() throws SshConnectException {
        FakeConnector connector = new FakeConnector();
        SftpSessionManager manager = new SftpSessionManager(connector);
        SshHost host = makeHost(UUID.randomUUID());

        Object owner1 = new Object();
        Object owner2 = new Object();
        manager.acquire(host, owner1);
        manager.acquire(host, owner2);

        manager.release(host.id(), owner1);

        assertNotNull(manager.peek(host.id()));
    }

    @Test
    void releaseLastOwnerClosesSession() throws SshConnectException {
        FakeConnector connector = new FakeConnector();
        SftpSessionManager manager = new SftpSessionManager(connector);
        SshHost host = makeHost(UUID.randomUUID());

        Object owner = new Object();
        manager.acquire(host, owner);
        manager.release(host.id(), owner);

        assertNull(manager.peek(host.id()));
    }

    @Test
    void acquireAfterReleaseReconnects() throws SshConnectException {
        FakeConnector connector = new FakeConnector();
        SftpSessionManager manager = new SftpSessionManager(connector);
        SshHost host = makeHost(UUID.randomUUID());

        Object owner = new Object();
        manager.acquire(host, owner);
        manager.release(host.id(), owner);
        manager.acquire(host, owner);

        assertEquals(2, connector.openCount.get());
    }

    @Test
    void forceDisconnectClosesEvenWithActiveOwners() throws SshConnectException {
        FakeConnector connector = new FakeConnector();
        SftpSessionManager manager = new SftpSessionManager(connector);
        SshHost host = makeHost(UUID.randomUUID());

        manager.acquire(host, new Object());
        manager.acquire(host, new Object());

        manager.forceDisconnect(host.id());

        assertNull(manager.peek(host.id()));
    }

    @Test
    void connectedHostIdsReflectsState() throws SshConnectException {
        FakeConnector connector = new FakeConnector();
        SftpSessionManager manager = new SftpSessionManager(connector);
        SshHost host1 = makeHost(UUID.randomUUID());
        SshHost host2 = makeHost(UUID.randomUUID());

        manager.acquire(host1, this);
        manager.acquire(host2, this);

        assertTrue(manager.connectedHostIds().contains(host1.id()));
        assertTrue(manager.connectedHostIds().contains(host2.id()));
        assertEquals(2, manager.connectedHostIds().size());
    }
}
```

- [ ] **Step 3: Run the test, confirm red**

Run: `bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner`
Expected: compile failure (`SftpSessionManager` undefined).

- [ ] **Step 4: Implement `plugins/sftp/src/com/termlab/sftp/session/SftpSessionManager.java`**

```java
package com.termlab.sftp.session;

import com.termlab.sftp.client.SshSftpSession;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.model.SshHost;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Application service that owns SFTP sessions per host UUID. Reference-counted
 * lifecycle: a session is closed only when its last consumer releases it.
 * Both the SFTP tool window and the SFTP virtual file system register as
 * consumers via {@link #acquire(SshHost, Object)}.
 */
@Service(Service.Level.APP)
public final class SftpSessionManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(SftpSessionManager.class);

    private final SftpConnector connector;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<UUID, SessionEntry> entries = new HashMap<>();

    private static final class SessionEntry {
        final SshSftpSession session;
        final Set<Object> owners = new HashSet<>();

        SessionEntry(SshSftpSession session) {
            this.session = session;
        }
    }

    /** Production constructor used by the IntelliJ service container. */
    @SuppressWarnings("unused")
    public SftpSessionManager() {
        this(new DefaultSftpConnector());
    }

    /** Test-only constructor for injecting a fake connector. */
    public SftpSessionManager(@NotNull SftpConnector connector) {
        this.connector = connector;
    }

    public static @NotNull SftpSessionManager getInstance() {
        return com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(SftpSessionManager.class);
    }

    /** Returns an existing session for the host, or null if none. Non-blocking. */
    public @Nullable SshSftpSession peek(@NotNull UUID hostId) {
        lock.lock();
        try {
            SessionEntry entry = entries.get(hostId);
            return entry == null ? null : entry.session;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an existing session if connected, or opens a new one and
     * registers the owner. Blocks the calling thread on first connect;
     * safe to call from a background executor or under modal progress.
     * Caller MUST eventually call {@link #release(UUID, Object)} with the
     * same owner reference.
     */
    public @NotNull SshSftpSession acquire(@NotNull SshHost host, @NotNull Object owner)
        throws SshConnectException
    {
        // Fast path: already connected.
        lock.lock();
        try {
            SessionEntry entry = entries.get(host.id());
            if (entry != null) {
                entry.owners.add(owner);
                return entry.session;
            }
        } finally {
            lock.unlock();
        }

        // Slow path: open the session OUTSIDE the lock so other threads can
        // acquire/release for different hosts in parallel.
        SshSftpSession session = connector.open(host);

        lock.lock();
        try {
            // Another thread may have raced us and installed an entry.
            SessionEntry existing = entries.get(host.id());
            if (existing != null) {
                // Discard our duplicate session.
                try { session.close(); } catch (Throwable ignored) {}
                existing.owners.add(owner);
                return existing.session;
            }
            SessionEntry entry = new SessionEntry(session);
            entry.owners.add(owner);
            entries.put(host.id(), entry);
            return session;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Decrements the refcount for {@code (hostId, owner)}. When the last
     * owner releases, the session is closed and removed from the cache.
     */
    public void release(@NotNull UUID hostId, @NotNull Object owner) {
        SshSftpSession toClose = null;
        lock.lock();
        try {
            SessionEntry entry = entries.get(hostId);
            if (entry == null) return;
            entry.owners.remove(owner);
            if (entry.owners.isEmpty()) {
                entries.remove(hostId);
                toClose = entry.session;
            }
        } finally {
            lock.unlock();
        }
        if (toClose != null) {
            try { toClose.close(); }
            catch (Throwable t) { LOG.warn("Failed to close SFTP session", t); }
        }
    }

    /**
     * Force-disconnect a host, ignoring refcounts. Editor tabs holding
     * stale references will fail their next operation.
     */
    public void forceDisconnect(@NotNull UUID hostId) {
        SshSftpSession toClose = null;
        lock.lock();
        try {
            SessionEntry entry = entries.remove(hostId);
            if (entry != null) {
                toClose = entry.session;
            }
        } finally {
            lock.unlock();
        }
        if (toClose != null) {
            try { toClose.close(); }
            catch (Throwable t) { LOG.warn("Failed to force-close SFTP session", t); }
        }
    }

    public @NotNull Set<UUID> connectedHostIds() {
        lock.lock();
        try {
            return new HashSet<>(entries.keySet());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void dispose() {
        lock.lock();
        try {
            for (SessionEntry entry : entries.values()) {
                try { entry.session.close(); }
                catch (Throwable t) { LOG.warn("Failed to close SFTP session on dispose", t); }
            }
            entries.clear();
        } finally {
            lock.unlock();
        }
    }
}
```

- [ ] **Step 5: Run the tests, confirm green**

Run: `bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner`
Expected: `18 tests successful` (11 from `SftpUrlTest` + 7 from `SftpSessionManagerTest`).

If `SshSftpSession` rejects null arguments in its constructor (it currently has `@NotNull` on both params), the test will fail with NPE during construction. In that case, replace `TestSshSftpSession extends SshSftpSession` with a tiny throwaway subclass that bypasses the parent constructor — or temporarily relax `SshSftpSession`'s constructor annotations. Document the choice in the test file's javadoc.

- [ ] **Step 6: Register the service in `plugins/sftp/resources/META-INF/plugin.xml`**

Add inside the existing `<extensions defaultExtensionNs="com.intellij">` block (alongside the existing `applicationService` for `TermLabSftpConfig`):

```xml
        <applicationService
            serviceImplementation="com.termlab.sftp.session.SftpSessionManager"/>
```

- [ ] **Step 7: Build the SFTP plugin to verify the registration is well-formed**

Run: `bash bazel.cmd build //termlab/plugins/sftp:sftp`
Expected: `Build completed successfully`.

- [ ] **Step 8: Commit**

```bash
git add plugins/sftp/src/com/termlab/sftp/session/ plugins/sftp/test/com/termlab/sftp/session/ plugins/sftp/resources/META-INF/plugin.xml
git commit -m "feat(sftp): SftpSessionManager with reference-counted sessions"
```

---

## Task 4: Migrate `RemoteFilePane` to use `SftpSessionManager`

Replace the inline `connect(...)` and `disconnect()` flow in `RemoteFilePane` with delegation to the manager. The pane registers itself as the owner.

**Files:**
- Modify: `plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java`

- [ ] **Step 1: Replace the `connect` method body**

Find the existing `private void connect(@NotNull SshHost host) { ... }` (around line 234 — the implementation that calls `HostCredentialBundle.resolveForHost(host)` and then `ProgressManager.getInstance().run(new Task.Modal(...))` containing `TermLabSftpConnector.open(...)`).

Replace its entire body with:

```java
    private void connect(@NotNull SshHost host) {
        statusLabel.setText("Resolving credentials for " + host.label() + "...");
        setUiEnabled(false);

        ProgressManager.getInstance().run(new Task.Modal(
            project,
            "Opening SFTP to " + host.label() + "...",
            /* canBeCancelled = */ true
        ) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    SshSftpSession session = SftpSessionManager.getInstance().acquire(host, RemoteFilePane.this);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        activeSession = session;
                        currentHost = host;
                        TermLabSftpConfig.getInstance().setLastRemoteHostId(host.id().toString());
                        statusLabel.setText("Connected to " + host.host() + ":" + host.port());
                        setUiEnabled(true);
                        updateButtons();
                        fireConnectionStateChanged();
                        navigateRemote(initialRemotePath(session));
                    });
                } catch (SshConnectException e) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showErrorDialog(RemoteFilePane.this,
                            "SFTP connection failed:\n" + e.getMessage(),
                            "SFTP Error");
                        statusLabel.setText("Not connected");
                        setUiEnabled(true);
                        updateButtons();
                    });
                }
            }
        });
    }
```

The differences from the original: no `HostCredentialBundle.resolveForHost(host)` call (the manager handles it via `DefaultSftpConnector`), and `SftpSessionManager.getInstance().acquire(host, RemoteFilePane.this)` replaces the inline `TermLabSftpConnector.open(host, bundle.target(), bundle.bastion())`.

- [ ] **Step 2: Add the import**

Add to `RemoteFilePane.java`'s imports (alphabetical order with other `com.termlab.sftp.*` imports):

```java
import com.termlab.sftp.session.SftpSessionManager;
```

Also delete the now-unused `import com.termlab.ssh.credentials.HostCredentialBundle;` if no other code in the file uses it. The original `connect` was the only user — verify with a grep before deleting.

- [ ] **Step 3: Replace the `disconnect` method body**

Find `private void disconnect()` (around line 327) and replace its body:

```java
    private void disconnect() {
        SshSftpSession session = activeSession;
        if (session == null || currentHost == null) return;
        java.util.UUID hostId = currentHost.id();
        SftpSessionManager.getInstance().release(hostId, this);
        activeSession = null;
        currentHost = null;
        currentRemotePath = null;
        pathField.setText("");
        model.setEntries(List.of());
        statusLabel.setText("Disconnected");
        updateButtons();
        fireConnectionStateChanged();
    }
```

Differences from the original: instead of `session.close()`, we call `SftpSessionManager.getInstance().release(hostId, this)`. The manager closes the session if (and only if) the pane is the last owner — which matches the original behavior in this phase, since no editor tab is yet a consumer.

- [ ] **Step 4: Build**

Run: `bash bazel.cmd build //termlab/plugins/sftp:sftp`
Expected: `Build completed successfully`.

- [ ] **Step 5: Manual smoke test**

Run: `bash bazel.cmd run //termlab:termlab_run`
Open the SFTP tool window, connect to a host, browse a directory, disconnect. Should behave identically to before this task. Close TermLab.

- [ ] **Step 6: Commit**

```bash
git add plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java
git commit -m "refactor(sftp): route RemoteFilePane connect/disconnect through SftpSessionManager"
```

---

## Task 5: Add `SftpSessionManager.getActiveSessionForCurrentProject`

The `SaveScratchToRemoteAction` needs an accessor that returns the SFTP tool window's currently-connected session for a given project. This requires reaching into the tool window state — a small layering inversion documented as acceptable for MVP.

**Files:**
- Modify: `plugins/sftp/src/com/termlab/sftp/session/SftpSessionManager.java`

- [ ] **Step 1: Add a small data record for the result**

We want to return both the session AND the host so callers don't have to do a second lookup. Add this nested record inside `SftpSessionManager`:

```java
    public record ActiveSession(
        @NotNull com.termlab.ssh.model.SshHost host,
        @NotNull com.termlab.sftp.client.SshSftpSession session,
        @NotNull String currentRemotePath
    ) {}
```

- [ ] **Step 2: Add the accessor method**

Append to `SftpSessionManager`:

```java
    /**
     * Returns the SFTP tool window's currently-connected session for the
     * given project, or null if no SFTP tool window is open or no session
     * is connected. Used by SaveScratchToRemoteAction to skip the host
     * picker when the user is already working with a host.
     *
     * <p>NOTE: this method reaches into the SFTP tool window's UI state.
     * It works because there is exactly one SFTP tool window per project.
     * If a future feature lets users have multiple SFTP panes open
     * simultaneously, this needs revisiting.
     */
    public @Nullable ActiveSession getActiveSessionForCurrentProject(
        @NotNull com.intellij.openapi.project.Project project
    ) {
        com.intellij.openapi.wm.ToolWindow tw =
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("SFTP");
        if (tw == null) return null;
        var contents = tw.getContentManager().getContents();
        for (var content : contents) {
            var component = content.getComponent();
            if (component instanceof com.termlab.sftp.toolwindow.SftpToolWindow toolWindow) {
                com.termlab.sftp.toolwindow.RemoteFilePane pane = toolWindow.remotePane();
                com.termlab.sftp.client.SshSftpSession session = pane.activeSession();
                com.termlab.ssh.model.SshHost host = pane.currentHost();
                String path = pane.currentRemotePath();
                if (session != null && host != null && path != null) {
                    return new ActiveSession(host, session, path);
                }
            }
        }
        return null;
    }
```

- [ ] **Step 3: Verify the accessors exist**

Confirm these accessors are present and public on `RemoteFilePane`:
- `public @Nullable SshSftpSession activeSession()` — exists at the bottom of `RemoteFilePane.java`
- `public @Nullable String currentRemotePath()` — exists
- `public @Nullable SshHost currentHost()` — **may need to be added**. Grep `RemoteFilePane.java` for `currentHost()`. If it doesn't exist as a public method, add:

```java
    /** Currently-connected host, or {@code null} when disconnected. */
    public @org.jetbrains.annotations.Nullable com.termlab.ssh.model.SshHost currentHost() {
        return currentHost;
    }
```

Place it near the existing `activeSession()` and `currentRemotePath()` accessors at the bottom of the class.

Also verify `SftpToolWindow` (the tool window component class — typically `plugins/sftp/src/com/termlab/sftp/toolwindow/SftpToolWindow.java`) exposes a `remotePane()` accessor that returns the `RemoteFilePane` instance. If it doesn't, add it:

```java
    public @org.jetbrains.annotations.NotNull RemoteFilePane remotePane() {
        return this.remoteFilePane; // adjust field name to match the actual field
    }
```

The exact field name in `SftpToolWindow` may differ — read the class first and use the right name.

- [ ] **Step 4: Build**

Run: `bash bazel.cmd build //termlab/plugins/sftp:sftp`
Expected: `Build completed successfully`.

If the build fails because `SftpToolWindow` is not the actual class held in the tool window content (it might be a JPanel that has the dual panes embedded), open `plugins/sftp/src/com/termlab/sftp/toolwindow/SftpToolWindowFactory.java` and follow what it actually puts into the tool window — adjust the `instanceof` check accordingly.

- [ ] **Step 5: Commit**

```bash
git add plugins/sftp/src/com/termlab/sftp/session/SftpSessionManager.java plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java plugins/sftp/src/com/termlab/sftp/toolwindow/SftpToolWindow.java
git commit -m "feat(sftp): SftpSessionManager.getActiveSessionForCurrentProject accessor"
```

---

## Task 6: `SftpVirtualFileSystem` skeleton

Just enough to register and pass `findFileByPath` calls through to the (yet-to-be-built) `SftpVirtualFile`. We'll fill in the file class in Task 7.

**Files:**
- Create: `plugins/sftp/src/com/termlab/sftp/vfs/SftpVirtualFileSystem.java`
- Modify: `plugins/sftp/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `SftpVirtualFileSystem.java`**

```java
package com.termlab.sftp.vfs;

import com.termlab.sftp.client.SshSftpSession;
import com.termlab.sftp.session.SftpSessionManager;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.SshHost;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Virtual file system for SFTP-hosted files. Single instance, registered
 * under protocol {@code sftp}, multi-host routing via {@link SftpUrl}.
 *
 * <p>Extends {@link DeprecatedVirtualFileSystem} (the supported base for
 * non-{@code ManagingFS}-integrated VFS implementations — the class name is
 * misleading; the class is not actually marked deprecated).
 */
public final class SftpVirtualFileSystem extends DeprecatedVirtualFileSystem {

    private static final Logger LOG = Logger.getInstance(SftpVirtualFileSystem.class);

    /** Hash-cons cache: same (hostId, remotePath) → same SftpVirtualFile instance. */
    private final Map<String, SftpVirtualFile> instances = new ConcurrentHashMap<>();

    public static @NotNull SftpVirtualFileSystem getInstance() {
        return (SftpVirtualFileSystem) VirtualFileManager.getInstance().getFileSystem(SftpUrl.PROTOCOL);
    }

    public SftpVirtualFileSystem() {
        startEventPropagation();
    }

    @Override
    public @NotNull String getProtocol() {
        return SftpUrl.PROTOCOL;
    }

    @Override
    public @Nullable VirtualFile findFileByPath(@NotNull String path) {
        // path here is the URL-without-protocol form: "<hostId>//<absolute-remote-path>"
        // VirtualFileManager.findFileByUrl strips the "sftp://" prefix before calling us.
        SftpUrl parsed = SftpUrl.parse(SftpUrl.PROTOCOL + "://" + path);
        if (parsed == null) return null;

        String key = parsed.hostId() + parsed.remotePath();
        SftpVirtualFile cached = instances.get(key);
        if (cached != null && cached.isValid()) return cached;

        SshHost host = lookupHost(parsed.hostId());
        if (host == null) return null;

        SshSftpSession session;
        try {
            session = SftpSessionManager.getInstance().acquire(host, this);
        } catch (SshConnectException e) {
            LOG.warn("findFileByPath could not acquire session for " + host.label(), e);
            return null;
        }

        // We hold a session reference for the lifetime of the VFS instance.
        // The reference is never released; sessions are torn down by:
        // (a) explicit forceDisconnect from the SFTP tool window
        // (b) IDE shutdown via SftpSessionManager.dispose()

        SftpVirtualFile vf = new SftpVirtualFile(this, parsed.hostId(), parsed.remotePath(), session, /*isDirectory=*/false);
        // Remote-stat to determine isDirectory and existence.
        if (!vf.statAndUpdate()) {
            return null;
        }
        instances.putIfAbsent(key, vf);
        return instances.get(key);
    }

    @Override
    public @Nullable VirtualFile refreshAndFindFileByPath(@NotNull String path) {
        // Invalidate any cached entry for this path, then re-resolve.
        SftpUrl parsed = SftpUrl.parse(SftpUrl.PROTOCOL + "://" + path);
        if (parsed != null) {
            String key = parsed.hostId() + parsed.remotePath();
            SftpVirtualFile cached = instances.remove(key);
            if (cached != null) cached.invalidate();
        }
        return findFileByPath(path);
    }

    @Override
    public void refresh(boolean asynchronous) {
        Runnable work = () -> {
            for (SftpVirtualFile vf : instances.values()) {
                vf.clearChildrenCache();
                vf.statAndUpdate();
            }
        };
        if (asynchronous) {
            com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(work);
        } else {
            work.run();
        }
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public @NotNull String extractPresentableUrl(@NotNull String path) {
        SftpUrl parsed = SftpUrl.parse(SftpUrl.PROTOCOL + "://" + path);
        if (parsed == null) return path;
        SshHost host = lookupHost(parsed.hostId());
        String label = host != null ? host.label() : parsed.hostId().toString();
        return label + ":" + parsed.remotePath();
    }

    /**
     * Internal: invoked by SftpVirtualFile when it discovers a file no
     * longer exists during refresh.
     */
    void evict(@NotNull SftpVirtualFile vf) {
        instances.remove(vf.hostId() + vf.remotePath());
    }

    /**
     * Internal: invoked by SftpVirtualFile to install newly-discovered
     * children in the hash-cons cache.
     */
    @NotNull SftpVirtualFile interned(@NotNull SftpVirtualFile fresh) {
        String key = fresh.hostId() + fresh.remotePath();
        SftpVirtualFile existing = instances.putIfAbsent(key, fresh);
        return existing != null ? existing : fresh;
    }

    private static @Nullable SshHost lookupHost(@NotNull UUID hostId) {
        HostStore store = ApplicationManager.getApplication().getService(HostStore.class);
        if (store == null) return null;
        for (SshHost host : store.getHosts()) {
            if (host.id().equals(hostId)) return host;
        }
        return null;
    }
}
```

Note: this references `SftpVirtualFile` which doesn't exist yet — the build will fail until Task 7. That's expected.

- [ ] **Step 2: Register the VFS in `plugins/sftp/resources/META-INF/plugin.xml`**

Add inside the existing `<extensions defaultExtensionNs="com.intellij">` block:

```xml
        <virtualFileSystem
            implementationClass="com.termlab.sftp.vfs.SftpVirtualFileSystem"
            key="sftp"
            physical="true"/>
```

- [ ] **Step 3: Don't build yet**

The build will fail until Task 7 lands `SftpVirtualFile`. Skip the build verification at this step. Commit the partial state — Task 7 will make it green.

- [ ] **Step 4: Commit (intermediate, build is broken)**

```bash
git add plugins/sftp/src/com/termlab/sftp/vfs/SftpVirtualFileSystem.java plugins/sftp/resources/META-INF/plugin.xml
git commit -m "feat(sftp): SftpVirtualFileSystem skeleton (build broken, fixed in next task)"
```

---

## Task 7: `SftpVirtualFile` class with read, write, list

The bulk of the VFS implementation. This is a long file — the code is verbatim below.

**Files:**
- Create: `plugins/sftp/src/com/termlab/sftp/vfs/SftpVirtualFile.java`

- [ ] **Step 1: Create `SftpVirtualFile.java`**

```java
package com.termlab.sftp.vfs;

import com.termlab.sftp.client.SshSftpSession;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileSystem;
import org.apache.sshd.sftp.client.SftpClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Single VFS file backed by a remote path on an SFTP host. Owns its own
 * per-instance attribute and listing caches; the platform's
 * {@code ManagingFS} is not involved.
 */
public final class SftpVirtualFile extends VirtualFile {

    private static final Logger LOG = Logger.getInstance(SftpVirtualFile.class);

    private final SftpVirtualFileSystem fs;
    private final UUID hostId;
    private final String remotePath;
    private final SshSftpSession session;

    private volatile boolean isDirectory;
    private volatile long length = -1;
    private volatile long timestamp = 0;
    private volatile long modificationStamp = 0;
    private volatile boolean valid = true;
    private volatile SftpVirtualFile parent;
    private volatile VirtualFile[] cachedChildren;

    SftpVirtualFile(
        @NotNull SftpVirtualFileSystem fs,
        @NotNull UUID hostId,
        @NotNull String remotePath,
        @NotNull SshSftpSession session,
        boolean isDirectoryHint
    ) {
        this.fs = fs;
        this.hostId = hostId;
        this.remotePath = remotePath;
        this.session = session;
        this.isDirectory = isDirectoryHint;
    }

    @NotNull UUID hostId() { return hostId; }
    @NotNull String remotePath() { return remotePath; }

    /**
     * Stat the remote file and update cached attributes. Returns false if
     * the file does not exist (and evicts the instance from the VFS cache).
     */
    boolean statAndUpdate() {
        try {
            SftpClient.Attributes attrs = session.client().stat(remotePath);
            if (attrs == null) {
                fs.evict(this);
                valid = false;
                return false;
            }
            this.isDirectory = attrs.isDirectory();
            this.length = attrs.getSize();
            if (attrs.getModifyTime() != null) {
                this.timestamp = attrs.getModifyTime().toMillis();
            }
            this.modificationStamp++;
            return true;
        } catch (IOException e) {
            LOG.warn("Stat failed for " + remotePath + ": " + e.getMessage());
            fs.evict(this);
            valid = false;
            return false;
        }
    }

    void invalidate() {
        valid = false;
        cachedChildren = null;
    }

    void clearChildrenCache() {
        cachedChildren = null;
    }

    // ------------ Identity / metadata --------------------------------------

    @Override
    public @NotNull String getName() {
        int slash = remotePath.lastIndexOf('/');
        if (slash < 0 || slash == remotePath.length() - 1) {
            return remotePath;
        }
        return remotePath.substring(slash + 1);
    }

    @Override
    public @NotNull String getPath() {
        return hostId + "/" + remotePath;
    }

    @Override
    public @NotNull String getUrl() {
        return SftpUrl.compose(hostId, remotePath);
    }

    @Override
    public @NotNull VirtualFileSystem getFileSystem() {
        return fs;
    }

    @Override
    public boolean isWritable() {
        return true;
    }

    @Override
    public boolean isDirectory() {
        return isDirectory;
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    @Override
    public @Nullable VirtualFile getParent() {
        if (parent != null) return parent;
        if ("/".equals(remotePath)) return null;
        int slash = remotePath.lastIndexOf('/');
        String parentPath = (slash <= 0) ? "/" : remotePath.substring(0, slash);
        SftpVirtualFile freshParent = new SftpVirtualFile(fs, hostId, parentPath, session, /*isDirectoryHint=*/true);
        // Don't stat — assume the parent exists if this file does.
        SftpVirtualFile interned = fs.interned(freshParent);
        this.parent = interned;
        return interned;
    }

    @Override
    public long getTimeStamp() {
        return timestamp;
    }

    @Override
    public long getModificationStamp() {
        return modificationStamp;
    }

    @Override
    public long getLength() {
        if (length < 0) {
            statAndUpdate();
        }
        return Math.max(0, length);
    }

    @Override
    public @NotNull FileType getFileType() {
        // Let FileTypeRegistry detect by filename — TextMate's
        // FileTypeIdentifiableByVirtualFile.isMyFileType claims files by
        // extension at this level.
        return FileTypeRegistry.getInstance().getFileTypeByFile(this);
    }

    // ------------ Children -------------------------------------------------

    @Override
    public VirtualFile @NotNull [] getChildren() {
        if (!isDirectory) return VirtualFile.EMPTY_ARRAY;
        VirtualFile[] cached = cachedChildren;
        if (cached != null) return cached;

        List<VirtualFile> result = new ArrayList<>();
        try {
            for (SftpClient.DirEntry entry : session.client().readDir(remotePath)) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) continue;
                String childPath = remotePath.equals("/") ? "/" + name : remotePath + "/" + name;
                boolean dir = entry.getAttributes().isDirectory();
                SftpVirtualFile fresh = new SftpVirtualFile(fs, hostId, childPath, session, dir);
                long size = entry.getAttributes().getSize();
                fresh.length = size;
                if (entry.getAttributes().getModifyTime() != null) {
                    fresh.timestamp = entry.getAttributes().getModifyTime().toMillis();
                }
                SftpVirtualFile interned = fs.interned(fresh);
                result.add(interned);
            }
        } catch (IOException e) {
            LOG.warn("readDir failed for " + remotePath + ": " + e.getMessage());
            return VirtualFile.EMPTY_ARRAY;
        }
        VirtualFile[] arr = result.toArray(VirtualFile.EMPTY_ARRAY);
        cachedChildren = arr;
        return arr;
    }

    @Override
    public @Nullable VirtualFile findChild(@NotNull String name) {
        for (VirtualFile child : getChildren()) {
            if (child.getName().equals(name)) return child;
        }
        return null;
    }

    // ------------ Read -----------------------------------------------------

    @Override
    public byte @NotNull [] contentsToByteArray() throws IOException {
        try (InputStream in = session.client().read(remotePath);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    @Override
    public @NotNull InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(contentsToByteArray());
    }

    // ------------ Write (atomic .tmp + rename) -----------------------------

    @Override
    public @NotNull OutputStream getOutputStream(
        Object requestor, long newModificationStamp, long newTimeStamp
    ) throws IOException {
        return new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                super.close();
                writeAtomically(this.toByteArray());
                modificationStamp = newModificationStamp >= 0
                    ? newModificationStamp
                    : SftpVirtualFile.this.modificationStamp + 1;
                if (newTimeStamp >= 0) {
                    timestamp = newTimeStamp;
                }
                length = this.toByteArray().length;
                // Notify VFS listeners that contents changed.
                fs.fireContentsChangedExternally(SftpVirtualFile.this);
            }
        };
    }

    @Override
    public void setBinaryContent(byte @NotNull [] content) throws IOException {
        writeAtomically(content);
        length = content.length;
        modificationStamp++;
    }

    @Override
    public void setBinaryContent(byte @NotNull [] content, long newModificationStamp, long newTimeStamp) throws IOException {
        writeAtomically(content);
        length = content.length;
        modificationStamp = newModificationStamp >= 0 ? newModificationStamp : modificationStamp + 1;
        if (newTimeStamp >= 0) timestamp = newTimeStamp;
    }

    @Override
    public void setBinaryContent(
        byte @NotNull [] content, long newModificationStamp, long newTimeStamp, Object requestor
    ) throws IOException {
        setBinaryContent(content, newModificationStamp, newTimeStamp);
    }

    private void writeAtomically(byte @NotNull [] content) throws IOException {
        String tmpPath = remotePath + "." + Long.toHexString(ThreadLocalRandom.current().nextLong()) + ".tmp";
        try {
            try (OutputStream out = session.client().write(tmpPath)) {
                out.write(content);
            }
            try {
                session.client().rename(tmpPath, remotePath);
            } catch (IOException renameErr) {
                // Fallback: target may already exist on a non-POSIX server.
                LOG.warn("Atomic rename failed for " + remotePath
                    + " (" + renameErr.getMessage() + "), falling back to delete+rename");
                try { session.client().remove(remotePath); } catch (IOException ignored) {}
                session.client().rename(tmpPath, remotePath);
            }
        } catch (IOException e) {
            try { session.client().remove(tmpPath); } catch (IOException ignored) {}
            throw e;
        }
    }

    // ------------ Refresh --------------------------------------------------

    @Override
    public void refresh(boolean asynchronous, boolean recursive, @Nullable Runnable postRunnable) {
        Runnable work = () -> {
            cachedChildren = null;
            statAndUpdate();
            if (postRunnable != null) postRunnable.run();
        };
        if (asynchronous) {
            com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(work);
        } else {
            work.run();
        }
    }

    // ------------ equals / hashCode ----------------------------------------

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SftpVirtualFile other)) return false;
        return hostId.equals(other.hostId) && remotePath.equals(other.remotePath);
    }

    @Override
    public int hashCode() {
        return hostId.hashCode() * 31 + remotePath.hashCode();
    }

    @Override
    public String toString() {
        return "SftpVirtualFile{" + hostId + "//" + remotePath + "}";
    }
}
```

- [ ] **Step 2: Add a content-changed event helper to `SftpVirtualFileSystem`**

`SftpVirtualFile.getOutputStream` calls `fs.fireContentsChangedExternally(this)`. Add this method to `SftpVirtualFileSystem`:

```java
    void fireContentsChangedExternally(@NotNull SftpVirtualFile file) {
        // Use the inherited helper from DeprecatedVirtualFileSystem.
        // Note: we pass 0 for the old timestamp because we don't track
        // pre-change values; consumers shouldn't rely on it.
        // The EventDispatcher requires write access; wrap in a write action.
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .runWriteAction(() -> fireContentsChanged(/*requestor=*/null, file, 0));
    }
```

- [ ] **Step 3: Build**

Run: `bash bazel.cmd build //termlab/plugins/sftp:sftp`
Expected: `Build completed successfully`. The previous task's intermediate broken state should now be fixed.

If the build fails because `VirtualFile.getOutputStream` has a different abstract signature in your platform version, read `/Users/dustin/projects/intellij-community/platform/core-api/src/com/intellij/openapi/vfs/VirtualFile.java` and adapt the override to match.

- [ ] **Step 4: Commit**

```bash
git add plugins/sftp/src/com/termlab/sftp/vfs/SftpVirtualFile.java plugins/sftp/src/com/termlab/sftp/vfs/SftpVirtualFileSystem.java
git commit -m "feat(sftp): SftpVirtualFile with atomic write semantics"
```

---

## Task 8: Consolidate `OpenGuards` and add `BinarySniffer.isBinaryByContent(VirtualFile)`

Move the size cap + extension blocklist guards from the old `RemoteEditService` into a shared utility. Add a VFS-aware binary sniff that the migrated openers will use.

**Files:**
- Create: `plugins/editor/src/com/termlab/editor/guard/OpenGuards.java`
- Modify: `plugins/editor/src/com/termlab/editor/guard/BinarySniffer.java`
- Create: `plugins/editor/test/com/termlab/editor/guard/BinarySnifferVfsTest.java`

- [ ] **Step 1: Create `OpenGuards.java`**

```java
package com.termlab.editor.guard;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Pre-open guards shared by the local and remote file openers. Refuses
 * blocked extensions and files larger than 5 MB before any IO happens.
 */
public final class OpenGuards {

    public static final long SIZE_CAP_BYTES = 5L * 1024 * 1024;
    private static final String NOTIFICATION_GROUP = "SFTP";

    private OpenGuards() {}

    public static boolean allow(@NotNull Project project, @NotNull String filename, long sizeBytes) {
        if (ExtensionBlocklist.isBlocked(filename)) {
            notify(project, "Cannot edit " + filename + ": binary file type.");
            return false;
        }
        if (sizeBytes > SIZE_CAP_BYTES) {
            notify(project, "File too large (" + formatMb(sizeBytes) + " MB). Maximum editable size is 5 MB.");
            return false;
        }
        return true;
    }

    private static String formatMb(long bytes) {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }

    private static void notify(@NotNull Project project, @NotNull String message) {
        Notifications.Bus.notify(
            new Notification(NOTIFICATION_GROUP, "TermLab Editor", message, NotificationType.ERROR),
            project);
    }
}
```

- [ ] **Step 2: Add the `isBinaryByContent` overload to `BinarySniffer.java`**

Find the existing `BinarySniffer.java` and add a new method:

```java
    public static boolean isBinaryByContent(@NotNull com.intellij.openapi.vfs.VirtualFile file) {
        try (java.io.InputStream in = file.getInputStream()) {
            byte[] buf = in.readNBytes(8 * 1024);
            for (byte b : buf) {
                if (b == 0) return true;
            }
            return false;
        } catch (java.io.IOException e) {
            // Treat read failures as binary so we err on the side of refusing
            return true;
        }
    }
```

Use fully-qualified names (or add imports) — match the existing file's import style.

- [ ] **Step 3: Write a test for `isBinaryByContent`**

`plugins/editor/test/com/termlab/editor/guard/BinarySnifferVfsTest.java`:

```java
package com.termlab.editor.guard;

import com.intellij.testFramework.LightVirtualFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinarySnifferVfsTest {

    @Test
    void plainTextLightVfileIsNotBinary() {
        LightVirtualFile file = new LightVirtualFile("test.txt", "hello world\nlorem ipsum\n");
        assertFalse(BinarySniffer.isBinaryByContent(file));
    }

    @Test
    void emptyLightVfileIsNotBinary() {
        LightVirtualFile file = new LightVirtualFile("empty", "");
        assertFalse(BinarySniffer.isBinaryByContent(file));
    }

    @Test
    void lightVfileWithNullByteIsBinary() {
        LightVirtualFile file = new LightVirtualFile("bin", new String(new char[]{'a','b','\0','c'}));
        assertTrue(BinarySniffer.isBinaryByContent(file));
    }
}
```

- [ ] **Step 4: Run tests**

Run: `bash bazel.cmd run //termlab/plugins/editor:editor_test_runner`
Expected: all existing tests still pass plus the 3 new ones.

- [ ] **Step 5: Commit**

```bash
git add plugins/editor/src/com/termlab/editor/guard/OpenGuards.java plugins/editor/src/com/termlab/editor/guard/BinarySniffer.java plugins/editor/test/com/termlab/editor/guard/BinarySnifferVfsTest.java
git commit -m "feat(editor): OpenGuards utility and BinarySniffer.isBinaryByContent(VirtualFile)"
```

---

## Task 9: Migrate `EditorRemoteFileOpener` to use the VFS

Rewrite the implementation to resolve the file via `VirtualFileManager.findFileByUrl` and open it directly. The session reference is held by the editor tab via the listener added in Task 11.

**Files:**
- Modify: `plugins/editor/src/com/termlab/editor/sftp/EditorRemoteFileOpener.java`
- Modify: `plugins/editor/BUILD.bazel`

- [ ] **Step 1: Add `//termlab/plugins/sftp` to editor deps if not already present**

Read `plugins/editor/BUILD.bazel`. Confirm `//termlab/plugins/sftp` is already in the `editor` jvm_library's `deps` list. (It should be — the editor plugin already depends on the SFTP plugin for the extension point types.) If missing, add it.

- [ ] **Step 2: Rewrite `EditorRemoteFileOpener.java`**

Replace the full file contents with:

```java
package com.termlab.editor.sftp;

import com.termlab.editor.guard.BinarySniffer;
import com.termlab.editor.guard.OpenGuards;
import com.termlab.sftp.client.SshSftpSession;
import com.termlab.sftp.model.RemoteFileEntry;
import com.termlab.sftp.spi.RemoteFileOpener;
import com.termlab.sftp.vfs.SftpUrl;
import com.termlab.ssh.model.SshHost;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;

public final class EditorRemoteFileOpener implements RemoteFileOpener {

    private static final String NOTIFICATION_GROUP = "SFTP";

    @Override
    public void open(
        @NotNull Project project,
        @NotNull SshHost host,
        @NotNull SshSftpSession session,
        @NotNull String absoluteRemotePath,
        @NotNull RemoteFileEntry entry
    ) {
        if (!OpenGuards.allow(project, entry.name(), entry.size())) return;

        String url = SftpUrl.compose(host.id(), absoluteRemotePath);
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(url);
        if (vf == null) {
            notifyError(project, "Could not open " + entry.name() + " on " + host.label());
            return;
        }

        if (BinarySniffer.isBinaryByContent(vf)) {
            notifyError(project, "Binary file detected: " + entry.name());
            return;
        }

        FileEditorManager.getInstance(project).openFile(vf, true);
    }

    private static void notifyError(@NotNull Project project, @NotNull String message) {
        Notifications.Bus.notify(
            new Notification(NOTIFICATION_GROUP, "SFTP", message, NotificationType.ERROR),
            project);
    }
}
```

Note: this no longer calls `RemoteEditService` — `RemoteEditService` is being deleted in Task 13. The build will not break here because we still haven't deleted `RemoteEditService` yet.

- [ ] **Step 3: Build**

Run: `bash bazel.cmd build //termlab/plugins/editor:editor`
Expected: `Build completed successfully`.

- [ ] **Step 4: Commit**

```bash
git add plugins/editor/src/com/termlab/editor/sftp/EditorRemoteFileOpener.java
git commit -m "refactor(editor): EditorRemoteFileOpener resolves via SftpVirtualFileSystem"
```

---

## Task 10: Migrate `EditorLocalFileOpener` to a direct-open path

Drop the trip through `RemoteEditService` for local files. Just resolve via `LocalFileSystem` and open directly.

**Files:**
- Modify: `plugins/editor/src/com/termlab/editor/sftp/EditorLocalFileOpener.java`

- [ ] **Step 1: Rewrite `EditorLocalFileOpener.java`**

Replace the full file contents with:

```java
package com.termlab.editor.sftp;

import com.termlab.editor.guard.OpenGuards;
import com.termlab.sftp.model.LocalFileEntry;
import com.termlab.sftp.spi.LocalFileOpener;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public final class EditorLocalFileOpener implements LocalFileOpener {

    private static final String NOTIFICATION_GROUP = "SFTP";

    @Override
    public void open(@NotNull Project project, @NotNull LocalFileEntry entry) {
        if (!OpenGuards.allow(project, entry.name(), entry.size())) return;
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry.path());
        if (vf == null) {
            Notifications.Bus.notify(
                new Notification(NOTIFICATION_GROUP, "SFTP",
                    "Could not open " + entry.path(), NotificationType.ERROR),
                project);
            return;
        }
        FileEditorManager.getInstance(project).openFile(vf, true);
    }
}
```

- [ ] **Step 2: Build**

Run: `bash bazel.cmd build //termlab/plugins/editor:editor`
Expected: `Build completed successfully`.

- [ ] **Step 3: Commit**

```bash
git add plugins/editor/src/com/termlab/editor/sftp/EditorLocalFileOpener.java
git commit -m "refactor(editor): EditorLocalFileOpener opens directly via LocalFileSystem"
```

---

## Task 11: Add `SftpEditorTabListener` for session reference release

Project-scoped `FileEditorManagerListener` in the SFTP plugin that releases the session reference when an `SftpVirtualFile`-backed tab closes. The corresponding `acquire` happens implicitly inside `SftpVirtualFileSystem.findFileByPath` (which calls `SftpSessionManager.acquire(host, this)` with the VFS instance as owner). For a per-tab refcount, we instead acquire ON FILE OPEN with the file as the owner. We adjust the openers to handle this.

Re-read the spec section "SftpEditorTabListener" — the cleanest model is: `SftpVirtualFileSystem.findFileByPath` does NOT hold a long-lived reference (it releases as soon as the call finishes), and instead each editor that opens an `SftpVirtualFile` acquires its own reference. The listener releases on tab close.

**Files:**
- Modify: `plugins/sftp/src/com/termlab/sftp/vfs/SftpVirtualFileSystem.java` — change `findFileByPath` to use a try-with-resources / scoped acquire pattern. Actually: simpler — acquire and immediately release inside `findFileByPath`, leaving the session held only by other consumers (the SFTP tool window pane, or the editor tab via the listener below).
- Create: `plugins/sftp/src/com/termlab/sftp/session/SftpEditorTabListener.java`
- Modify: `plugins/sftp/resources/META-INF/plugin.xml`
- Modify: `plugins/editor/src/com/termlab/editor/sftp/EditorRemoteFileOpener.java` — explicitly acquire before opening.

- [ ] **Step 1: Adjust `SftpVirtualFileSystem.findFileByPath` to scope its session reference**

In `findFileByPath`, change the acquire-and-keep pattern to acquire-and-release-within-the-call:

Find:

```java
        SshSftpSession session;
        try {
            session = SftpSessionManager.getInstance().acquire(host, this);
        } catch (SshConnectException e) {
            LOG.warn("findFileByPath could not acquire session for " + host.label(), e);
            return null;
        }

        // We hold a session reference for the lifetime of the VFS instance.
        // The reference is never released; sessions are torn down by:
        // (a) explicit forceDisconnect from the SFTP tool window
        // (b) IDE shutdown via SftpSessionManager.dispose()
```

Replace with:

```java
        SshSftpSession session;
        try {
            session = SftpSessionManager.getInstance().acquire(host, this);
        } catch (SshConnectException e) {
            LOG.warn("findFileByPath could not acquire session for " + host.label(), e);
            return null;
        }
        // Release immediately — findFileByPath only needs the session for
        // the duration of the stat call. Other consumers (the SFTP tool
        // window pane, or editor tabs via SftpEditorTabListener) hold
        // longer-lived references that keep the session alive.
        try {
```

And wrap the rest of the method body up to the return in the try, then add a `finally` that releases:

```java
        try {
            SftpVirtualFile vf = new SftpVirtualFile(this, parsed.hostId(), parsed.remotePath(), session, /*isDirectory=*/false);
            if (!vf.statAndUpdate()) {
                return null;
            }
            instances.putIfAbsent(key, vf);
            return instances.get(key);
        } finally {
            SftpSessionManager.getInstance().release(parsed.hostId(), this);
        }
```

This means the session is acquired, used for the stat, then released. If no other consumer holds a reference at this point, the session is closed. For our use case the session WILL have another holder by the time we call this (typically the SFTP tool window pane, or the action's pre-flight acquire).

**Important caveat**: this means `findFileByPath` is only safe to call when there's already an active consumer. If the user calls `VirtualFileManager.findFileByUrl("sftp://...")` cold with no other consumer, the session is opened, used, and immediately torn down — which works for the stat but leaves no session for subsequent operations. Document this as a known limitation: callers should hold their own session reference before calling `findFileByPath`.

- [ ] **Step 2: Create `SftpEditorTabListener.java`**

```java
package com.termlab.sftp.session;

import com.termlab.sftp.vfs.SftpVirtualFile;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Project-scoped listener that releases the SFTP session reference
 * when an SftpVirtualFile-backed editor tab closes. The matching
 * acquire happens in EditorRemoteFileOpener (and SaveScratchToRemoteAction)
 * with the VirtualFile as the owner key.
 */
public final class SftpEditorTabListener implements FileEditorManagerListener {

    @Override
    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        // Acquire is done by the caller that opened the file (the opener
        // or the save action). We don't acquire here because we don't have
        // the SshHost — only the VirtualFile.
    }

    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        if (!(file instanceof SftpVirtualFile sftp)) return;
        SftpSessionManager.getInstance().release(sftp.hostId(), file);
    }
}
```

- [ ] **Step 3: Register the listener in `plugins/sftp/resources/META-INF/plugin.xml`**

Add a `<projectListeners>` block (or extend the existing one) with:

```xml
    <projectListeners>
        <listener class="com.termlab.sftp.session.SftpEditorTabListener"
                  topic="com.intellij.openapi.fileEditor.FileEditorManagerListener"/>
    </projectListeners>
```

If the SFTP plugin already has a `<projectListeners>` block, just add the new `<listener>` line inside it.

- [ ] **Step 4: Update `EditorRemoteFileOpener.open` to acquire before opening**

Add an explicit `SftpSessionManager.acquire(host, vf)` call before `FileEditorManager.openFile`:

Find the lines in `EditorRemoteFileOpener.open`:

```java
        if (BinarySniffer.isBinaryByContent(vf)) {
            notifyError(project, "Binary file detected: " + entry.name());
            return;
        }

        FileEditorManager.getInstance(project).openFile(vf, true);
```

Replace with:

```java
        if (BinarySniffer.isBinaryByContent(vf)) {
            notifyError(project, "Binary file detected: " + entry.name());
            return;
        }

        // Acquire a tab-scoped session reference; SftpEditorTabListener
        // releases it when the editor tab closes.
        try {
            com.termlab.sftp.session.SftpSessionManager.getInstance().acquire(host, vf);
        } catch (com.termlab.ssh.client.SshConnectException e) {
            notifyError(project, "Session lost for " + host.label() + ": " + e.getMessage());
            return;
        }

        FileEditorManager.getInstance(project).openFile(vf, true);
```

- [ ] **Step 5: Build**

Run: `bash bazel.cmd build //termlab/plugins/sftp:sftp //termlab/plugins/editor:editor`
Expected: both targets build successfully.

- [ ] **Step 6: Commit**

```bash
git add plugins/sftp/src/com/termlab/sftp/vfs/SftpVirtualFileSystem.java plugins/sftp/src/com/termlab/sftp/session/SftpEditorTabListener.java plugins/sftp/resources/META-INF/plugin.xml plugins/editor/src/com/termlab/editor/sftp/EditorRemoteFileOpener.java
git commit -m "feat(sftp): tab-scoped session refcount via SftpEditorTabListener"
```

---

## Task 12: `SaveScratchToRemoteAction`

The new `Cmd+Shift+S` action. Host picker, modal connect progress, FileSaverDialog rooted at the remote, atomic write, scratch-to-real-tab transition.

**Files:**
- Create: `plugins/editor/src/com/termlab/editor/scratch/SaveScratchToRemoteAction.java`
- Modify: `plugins/editor/resources/META-INF/plugin.xml`

- [ ] **Step 1: Verify `Cmd+Shift+S` is unbound in TermLab**

Read `core/src/com/termlab/core/TermLabToolbarStripper.java`. Confirm `SaveAll` is in its strip list — grep for `SaveAll`. If present, `Cmd+Shift+S` is free to use. If not, fall back to `Cmd+Alt+S` in step 4.

- [ ] **Step 2: Create `SaveScratchToRemoteAction.java`**

```java
package com.termlab.editor.scratch;

import com.termlab.sftp.client.SshSftpSession;
import com.termlab.sftp.session.SftpSessionManager;
import com.termlab.sftp.vfs.SftpUrl;
import com.termlab.sftp.vfs.SftpVirtualFile;
import com.termlab.sftp.vfs.SftpVirtualFileSystem;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.SshHost;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.FileSaverDialog;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Save the active scratch file to a connected SFTP host. Cmd+Shift+S /
 * Ctrl+Shift+S. Disabled unless the active editor is on a marked
 * scratch LightVirtualFile.
 */
public final class SaveScratchToRemoteAction extends AnAction {

    private static final String NOTIFICATION_GROUP = "SFTP";

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
        VirtualFile file = activeScratchFile(project);
        return file != null;
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

        // Pick host: active session → use it; else show picker.
        SftpSessionManager.ActiveSession active =
            SftpSessionManager.getInstance().getActiveSessionForCurrentProject(project);
        if (active != null) {
            proceedWithHost(project, scratch, active.host(), active.session(), active.currentRemotePath());
            return;
        }

        showHostPickerThenConnect(project, scratch);
    }

    private static void showHostPickerThenConnect(@NotNull Project project, @NotNull VirtualFile scratch) {
        HostStore store = ApplicationManager.getApplication().getService(HostStore.class);
        if (store == null) {
            notify(project, "No host store available", NotificationType.ERROR);
            return;
        }
        List<SshHost> hosts = store.getHosts();
        if (hosts.isEmpty()) {
            notify(project, "No SFTP hosts configured. Add one in the SFTP tool window first.",
                NotificationType.WARNING);
            return;
        }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(hosts)
            .setTitle("Connect to host")
            .setVisibleRowCount(8)
            .setNamerForFiltering(SshHost::label)
            .setItemChosenCallback(host -> connectThenProceed(project, scratch, host))
            .createPopup()
            .showCenteredInCurrentWindow(project);
    }

    private static void connectThenProceed(
        @NotNull Project project, @NotNull VirtualFile scratch, @NotNull SshHost host
    ) {
        ProgressManager.getInstance().run(new Task.Modal(
            project, "Connecting to " + host.label() + "...", true
        ) {
            private SshSftpSession session;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    session = SftpSessionManager.getInstance().acquire(host, this);
                } catch (SshConnectException e) {
                    ApplicationManager.getApplication().invokeLater(() ->
                        notify(project, "Connection failed: " + e.getMessage(), NotificationType.ERROR));
                }
            }

            @Override
            public void onSuccess() {
                if (session == null) return;
                String startDir;
                try {
                    String canonical = session.client().canonicalPath(".");
                    startDir = (canonical != null && !canonical.isBlank()) ? canonical : "/";
                } catch (Exception e) {
                    startDir = "/";
                }
                try {
                    proceedWithHost(project, scratch, host, session, startDir);
                } finally {
                    SftpSessionManager.getInstance().release(host.id(), this);
                }
            }
        });
    }

    private static void proceedWithHost(
        @NotNull Project project,
        @NotNull VirtualFile scratch,
        @NotNull SshHost host,
        @NotNull SshSftpSession session,
        @NotNull String startingRemoteDir
    ) {
        // Acquire an action-scoped reference for the duration of this method.
        SftpSessionManager mgr = SftpSessionManager.getInstance();
        Object actionOwner = new Object();
        try {
            mgr.acquire(host, actionOwner);
        } catch (SshConnectException e) {
            notify(project, "Session lost: " + e.getMessage(), NotificationType.ERROR);
            return;
        }

        try {
            String startUrl = SftpUrl.compose(host.id(), startingRemoteDir);
            VirtualFile root = VirtualFileManager.getInstance().findFileByUrl(startUrl);
            if (root == null) {
                notify(project, "Could not list " + host.label() + ":" + startingRemoteDir,
                    NotificationType.ERROR);
                return;
            }

            FileSaverDescriptor descriptor =
                new FileSaverDescriptor("Save scratch to " + host.label(), "Save the scratch buffer to the remote host");
            FileSaverDialog dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project);
            VirtualFileWrapper wrapper = dialog.save(root, scratch.getName());
            if (wrapper == null) return; // user cancelled

            // Compose the destination path. The wrapper returns a java.io.File
            // whose absolute path is the remote path we want.
            String destPath = wrapper.getFile().getAbsolutePath();
            // Normalize Windows-style separators to forward slashes.
            destPath = destPath.replace('\\', '/');

            String destUrl = SftpUrl.compose(host.id(), destPath);

            // Write the buffer atomically via a fresh SftpVirtualFile.
            // Since the destination probably doesn't exist yet, we don't go
            // through findFileByUrl (which would stat-fail). We manually
            // construct the file and write.
            byte[] content = FileDocumentManager.getInstance().getDocument(scratch).getText()
                .getBytes(StandardCharsets.UTF_8);
            SftpVirtualFile destFile = new SftpVirtualFile(
                SftpVirtualFileSystem.getInstance(), host.id(), destPath, session, /*isDirectory=*/false);
            try {
                destFile.setBinaryContent(content);
            } catch (java.io.IOException e) {
                notify(project, "Save failed: " + e.getMessage(), NotificationType.ERROR);
                return;
            }

            // Re-resolve via findFileByUrl so the canonical interned instance
            // is the one we keep. This also stats the file.
            VirtualFile saved = VirtualFileManager.getInstance().findFileByUrl(destUrl);
            if (saved == null) {
                notify(project, "Saved, but could not reopen the file. Refresh the SFTP pane.",
                    NotificationType.WARNING);
                return;
            }

            // Transition session ownership: acquire for the new tab BEFORE
            // releasing the action's reference, so the refcount never hits 0.
            try {
                mgr.acquire(host, saved);
            } catch (SshConnectException ce) {
                notify(project, "Session lost after save: " + ce.getMessage(), NotificationType.ERROR);
                return;
            }
            mgr.release(host.id(), actionOwner);
            actionOwner = null; // ownership transferred

            // Close the scratch tab, open the new file.
            FileEditorManager mgrFiles = FileEditorManager.getInstance(project);
            mgrFiles.closeFile(scratch);
            mgrFiles.openFile(saved, true);

            notify(project, "Saved to " + host.label() + ":" + destPath, NotificationType.INFORMATION);
        } finally {
            if (actionOwner != null) {
                mgr.release(host.id(), actionOwner);
            }
        }
    }

    private static void notify(@NotNull Project project, @NotNull String message, @NotNull NotificationType type) {
        Notifications.Bus.notify(
            new Notification(NOTIFICATION_GROUP, "Save Scratch to Remote", message, type),
            project);
    }
}
```

- [ ] **Step 3: Register the action in `plugins/editor/resources/META-INF/plugin.xml`**

Inside the existing `<actions>` block (after the `TermLab.Editor.NewScratch` action), add:

```xml
        <action id="TermLab.Editor.SaveScratchToRemote"
                class="com.termlab.editor.scratch.SaveScratchToRemoteAction"
                text="Save Scratch to Remote…"
                description="Save the current scratch file to a connected SFTP host">
            <add-to-group group-id="FileMenu" anchor="after" relative-to-action="TermLab.Editor.NewScratch"/>
            <keyboard-shortcut keymap="$default" first-keystroke="control shift S"/>
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="meta shift S" replace-all="true"/>
            <keyboard-shortcut keymap="Mac OS X" first-keystroke="meta shift S" replace-all="true"/>
        </action>
```

- [ ] **Step 4: Build**

Run: `bash bazel.cmd build //termlab/plugins/editor:editor`
Expected: `Build completed successfully`. If `FileSaverDialog.save(VirtualFile, String)` does not exist in your platform version, check the actual signature in the platform source — older versions take a `(File, String)` pair.

- [ ] **Step 5: Commit**

```bash
git add plugins/editor/src/com/termlab/editor/scratch/SaveScratchToRemoteAction.java plugins/editor/resources/META-INF/plugin.xml
git commit -m "feat(editor): SaveScratchToRemoteAction bound to Cmd/Ctrl+Shift+S"
```

---

## Task 13: Delete the old temp-file machinery

Delete the now-unused `RemoteFileBinding`, `RemoteFileBindingRegistry`, `RemoteEditService`, `RemoteSaveListener`, `RemoteEditorCleanup`, `RemoteEditorShutdownListener`, `RemoteEditorStartupSweep`, `RemoteEditorProjectListener`, `TempPathResolver` (and its test), and `SftpSingleFileTransfer`. Remove their plugin.xml registrations.

**Files:**
- Delete: `plugins/editor/src/com/termlab/editor/remote/RemoteFileBinding.java`
- Delete: `plugins/editor/src/com/termlab/editor/remote/RemoteFileBindingRegistry.java`
- Delete: `plugins/editor/src/com/termlab/editor/remote/RemoteEditService.java`
- Delete: `plugins/editor/src/com/termlab/editor/remote/RemoteSaveListener.java`
- Delete: `plugins/editor/src/com/termlab/editor/remote/RemoteEditorCleanup.java`
- Delete: `plugins/editor/src/com/termlab/editor/remote/RemoteEditorShutdownListener.java`
- Delete: `plugins/editor/src/com/termlab/editor/remote/RemoteEditorStartupSweep.java`
- Delete: `plugins/editor/src/com/termlab/editor/remote/RemoteEditorProjectListener.java`
- Delete: `plugins/editor/src/com/termlab/editor/remote/TempPathResolver.java`
- Delete: `plugins/editor/test/com/termlab/editor/remote/TempPathResolverTest.java`
- Delete: `plugins/sftp/src/com/termlab/sftp/transfer/SftpSingleFileTransfer.java`
- Modify: `plugins/editor/resources/META-INF/plugin.xml`
- Create: `plugins/editor/src/com/termlab/editor/sftp/LegacyTempDirCleanup.java` (one-shot orphan dir cleanup)

- [ ] **Step 1: Delete the old files**

```bash
rm plugins/editor/src/com/termlab/editor/remote/RemoteFileBinding.java
rm plugins/editor/src/com/termlab/editor/remote/RemoteFileBindingRegistry.java
rm plugins/editor/src/com/termlab/editor/remote/RemoteEditService.java
rm plugins/editor/src/com/termlab/editor/remote/RemoteSaveListener.java
rm plugins/editor/src/com/termlab/editor/remote/RemoteEditorCleanup.java
rm plugins/editor/src/com/termlab/editor/remote/RemoteEditorShutdownListener.java
rm plugins/editor/src/com/termlab/editor/remote/RemoteEditorStartupSweep.java
rm plugins/editor/src/com/termlab/editor/remote/RemoteEditorProjectListener.java
rm plugins/editor/src/com/termlab/editor/remote/TempPathResolver.java
rm plugins/editor/test/com/termlab/editor/remote/TempPathResolverTest.java
rm plugins/sftp/src/com/termlab/sftp/transfer/SftpSingleFileTransfer.java
```

- [ ] **Step 2: Create the legacy temp-dir cleanup**

`plugins/editor/src/com/termlab/editor/sftp/LegacyTempDirCleanup.java`:

```java
package com.termlab.editor.sftp;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.util.concurrency.AppExecutorUtil;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * One-shot cleanup of the legacy {@code termlab-sftp-edits/} directory
 * left behind by the old temp-file SFTP edit flow. The directory is no
 * longer used after migration to {@link com.termlab.sftp.vfs.SftpVirtualFileSystem}.
 *
 * <p>Remove this class after a few releases when no users have stale dirs.
 */
public final class LegacyTempDirCleanup implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(LegacyTempDirCleanup.class);
    private static volatile boolean swept = false;

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (swept) return Unit.INSTANCE;
        swept = true;
        AppExecutorUtil.getAppExecutorService().submit(() -> {
            Path root = Paths.get(PathManager.getSystemPath(), "termlab-sftp-edits");
            if (!Files.exists(root)) return;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (IOException e) { LOG.warn("Failed to delete legacy temp file: " + p, e); }
                });
                LOG.info("Cleaned up legacy termlab-sftp-edits directory");
            } catch (IOException e) {
                LOG.warn("Failed to clean up legacy termlab-sftp-edits", e);
            }
        });
        return Unit.INSTANCE;
    }
}
```

- [ ] **Step 3: Rewrite `plugins/editor/resources/META-INF/plugin.xml`**

The current file has many registrations for the deleted classes. Read the file first to see its exact current state, then replace it with this new clean version (preserving the `<id>`, `<name>`, `<version>`, `<vendor>`, `<description>`, `<depends>` block, and any dependencies on TextMate/etc. that already exist):

```xml
<idea-plugin>
    <id>com.termlab.editor</id>
    <name>TermLab Light Editor</name>
    <version>0.1.0</version>
    <vendor>TermLab</vendor>
    <description>
        Light editor for scratches and SFTP-triggered file editing.
        Bundled and enabled by default. Disable via Settings → Plugins
        if you don't need it.
    </description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.termlab.core</depends>
    <depends>com.termlab.sftp</depends>
    <depends>org.jetbrains.plugins.textmate</depends>

    <extensions defaultExtensionNs="com.intellij">
        <postStartupActivity implementation="com.termlab.editor.debug.TextMateBuiltinBundleEnabler"/>
        <postStartupActivity implementation="com.termlab.editor.sftp.LegacyTempDirCleanup"/>
    </extensions>

    <extensions defaultExtensionNs="com.termlab.sftp">
        <localFileOpener implementation="com.termlab.editor.sftp.EditorLocalFileOpener"/>
        <remoteFileOpener implementation="com.termlab.editor.sftp.EditorRemoteFileOpener"/>
    </extensions>

    <actions>
        <action id="TermLab.Editor.NewScratch"
                class="com.termlab.editor.scratch.NewScratchAction"
                text="New Scratch File"
                description="Create a new scratch text file with a chosen file type">
            <add-to-group group-id="FileMenu" anchor="first"/>
            <keyboard-shortcut keymap="$default" first-keystroke="control N"/>
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="meta N" replace-all="true"/>
            <keyboard-shortcut keymap="Mac OS X" first-keystroke="meta N" replace-all="true"/>
        </action>
        <action id="TermLab.Editor.SaveScratchToRemote"
                class="com.termlab.editor.scratch.SaveScratchToRemoteAction"
                text="Save Scratch to Remote…"
                description="Save the current scratch file to a connected SFTP host">
            <add-to-group group-id="FileMenu" anchor="after" relative-to-action="TermLab.Editor.NewScratch"/>
            <keyboard-shortcut keymap="$default" first-keystroke="control shift S"/>
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="meta shift S" replace-all="true"/>
            <keyboard-shortcut keymap="Mac OS X" first-keystroke="meta shift S" replace-all="true"/>
        </action>
    </actions>
</idea-plugin>
```

Compare to your actual current plugin.xml before applying — it may have additional entries we want to preserve (e.g., the existing `ScratchSaveListener` registration). Read first, then apply changes carefully.

- [ ] **Step 4: Keep `ScratchSaveListener.java`**

The existing `ScratchSaveListener` intercepts `Cmd+S` on `LightVirtualFile` scratches and shows a Save-As dialog for the LOCAL save case. It is unrelated to the new `Cmd+Shift+S` remote save flow and should stay in place. If your `plugin.xml` has an `<applicationListeners>` block referencing `ScratchSaveListener`, leave it in the new clean `plugin.xml` from Step 3.

To preserve it, edit the `plugin.xml` from Step 3 above and add the listener block before `</idea-plugin>`:

```xml
    <applicationListeners>
        <listener class="com.termlab.editor.scratch.ScratchSaveListener"
                  topic="com.intellij.openapi.fileEditor.FileDocumentManagerListener"/>
    </applicationListeners>
```

- [ ] **Step 5: Build editor and SFTP**

Run: `bash bazel.cmd build //termlab/plugins/editor:editor //termlab/plugins/sftp:sftp`
Expected: both build successfully. If there are dangling references to deleted classes, the compiler will report them — fix and retry.

- [ ] **Step 6: Run all unit tests**

Run: `bash bazel.cmd run //termlab/plugins/editor:editor_test_runner`
Run: `bash bazel.cmd run //termlab/plugins/sftp:sftp_test_runner`
Expected: all tests still pass. The deleted `TempPathResolverTest` is gone; the remaining editor tests should still run.

- [ ] **Step 7: Commit**

```bash
git add -A plugins/editor/ plugins/sftp/
git commit -m "refactor(editor): delete temp-file SFTP machinery, route everything through VFS"
```

---

## Task 14: End-to-end manual verification

The platform-bound parts of this feature can't be unit-tested without a heavy fixture. Walk through this checklist with a running TermLab and record any deviations as follow-up tasks.

**Files:** none (validation only)

- [ ] **Step 1: Build the product**

Run: `bash bazel.cmd build //termlab:termlab_run`
Expected: `Build completed successfully`.

- [ ] **Step 2: Launch TermLab**

Run: `bash bazel.cmd run //termlab:termlab_run`

- [ ] **Step 3: Verify SFTP tool window still works**

- Open the SFTP tool window (default: bottom).
- Pick a saved host, click Connect. Should connect normally.
- Browse to a directory. Should list correctly.
- Disconnect. Should disconnect normally.

- [ ] **Step 4: Verify SFTP-double-click open via the new VFS**

- Connect to a host.
- Browse to a directory containing a small text file.
- Double-click the file.
- Expected: the file opens as an editor tab. The tab title is the filename. Syntax highlighting works (TextMate).
- Edit a few characters. Hit `Cmd+S`.
- Expected: the save succeeds with no notification (writes through the VFS atomically).
- Verify on the remote via shell: `cat <path>` matches your edits.

- [ ] **Step 5: Verify scratch save to remote with active session**

- With the SFTP tool window still connected and a directory selected:
- `Cmd+N` → pick Java → empty `scratch-1.java` opens.
- Type some content.
- `Cmd+Shift+S` → FileSaverDialog appears titled "Save scratch to <host>", rooted at the current SFTP directory.
- Pick a filename, save.
- Expected: notification "Saved to <host>:<path>". Scratch tab is replaced with a new tab whose title is the new filename. Verify via shell.
- Type more content in the new tab. `Cmd+S`.
- Expected: writes through the VFS to the remote.

- [ ] **Step 6: Verify scratch save to remote with no active session**

- Disconnect the SFTP tool window.
- `Cmd+N` → pick Python → scratch.
- `Cmd+Shift+S` → host picker popup appears.
- Pick a host. Modal "Connecting…" progress.
- FileSaverDialog appears.
- Pick filename, save. Verify on remote.

- [ ] **Step 7: Verify guard rejections**

- Try double-clicking a `.zip` from the SFTP remote pane → notification "Cannot edit … binary file type" → no tab opens.
- Try opening a >5MB file → notification "File too large" → no tab opens.

- [ ] **Step 8: Verify legacy temp dir cleanup**

- Quit TermLab.
- Manually create a stale `~/Library/Caches/JetBrains/TermLab.../termlab-sftp-edits/` directory with a dummy file inside. (Path: `PathManager.getSystemPath()/termlab-sftp-edits`.)
- Re-launch TermLab.
- After a few seconds, check the directory: it should be empty/gone.

- [ ] **Step 9: Verify Cmd+Shift+S is disabled when not on a scratch**

- Click into the terminal tab. `Cmd+Shift+S` should be a no-op (action disabled).
- Open a remote file via SFTP double-click. Click into that tab. `Cmd+Shift+S` should be disabled.
- Click into a scratch tab. `Cmd+Shift+S` should be enabled.

- [ ] **Step 10: Commit the manual verification**

```bash
git commit --allow-empty -m "chore(editor): manual e2e verification passed for SFTP VFS migration"
```

If any step in 3–9 fails, fix the underlying bug, rebuild, and re-run from the failing step before committing.

---

## Out of Scope (Follow-ups)

These were flagged in the spec and should NOT be done in this plan:

1. `File → Open Remote File…` action that uses the new VFS for arbitrary remote opens.
2. Multi-pane SFTP tool window with multiple simultaneous host connections.
3. External change detection / file watching on remote files.
4. Implementing the full set of mutation operations (`deleteFile`, `moveFile`, `renameFile`, `copyFile`, `createChildFile`, `createChildDirectory`).
5. Removing the `LegacyTempDirCleanup` startup activity after a few releases when nobody has stale directories.
6. Migrating other file-picker call sites in TermLab to use `SftpVirtualFileSystem` as a root option.
