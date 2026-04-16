# TermLab SSH Plugin — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a first-party SSH plugin for TermLab that opens interactive SSH sessions as terminal tabs, reusing the existing vault plugin for credentials and the existing `TermLabTerminalEditor` for rendering. Hosts live in a new `Hosts` tool window in the left sidebar; clicking a host connects; wrong/missing credentials trigger an in-flow credential picker.

**Architecture:** MINA SSHD 2.15 (already available as `//libraries/sshd-osgi`) drives the SSH client side. A new `SshSessionProvider` implements `com.termlab.sdk.TerminalSessionProvider` — the same contract the existing `LocalPtySessionProvider` uses — so sessions slot into `TermLabTerminalEditor` without any editor-side changes. A new `HostsToolWindowFactory` adds the sidebar panel. Saved hosts live in `~/.config/termlab/ssh-hosts.json` (plaintext, same pattern as terminal settings); each entry references a vault credential by UUID rather than embedding secrets.

**Tech Stack:**
- Java 21
- MINA SSHD 2.15 (`//libraries/sshd-osgi`) — client API + crypto
- JediTerm `TtyConnector` (from core) — the glue that renders SSH stdout as a terminal tab
- IntelliJ tool-window EP (`com.intellij.toolWindow`) — the Hosts sidebar
- Gson — `ssh-hosts.json` persistence (same pattern as `TermLabTerminalConfig`)
- TermLab SDK: `TerminalSessionProvider`, `CredentialProvider`, `CommandPaletteContributor`

**Reference spec:** `docs/specs/2026-04-08-termlab-workstation-design.md` — sections 5.1 (SSH Host Manager), 7.5 (SSH Security)

**Reference implementation:** `~/projects/rusty_termlab_2/crates/termlab_remote/src/{ssh.rs,handler.rs,known_hosts.rs,config.rs}` — same data model and connection shape, in Rust. The Java port reuses `ServerEntry` structure (renamed `SshHost`), reuses the known-hosts file location, and reuses the same "resolve credentials → open channel → prompt on failure" flow. It does NOT port proxy_command / proxy_jump or SFTP / tunnels — those are out of scope for v1 (see "Out of scope" below).

---

## File Structure

All paths relative to `termlab_workbench/`. The plugin lives at `plugins/ssh/` as a sibling of `plugins/vault/` → Bazel target `//termlab/plugins/ssh`.

```
plugins/ssh/
├── BUILD.bazel                                          # jvm_library + test lib
├── intellij.termlab.ssh.iml                               # IntelliJ module
├── resources/
│   └── META-INF/
│       └── plugin.xml                                   # Plugin descriptor
└── src/com/termlab/ssh/
    ├── model/
    │   ├── SshHost.java                                 # Record: id, label, host, port, username, credentialId
    │   └── HostStore.java                               # Mutable holder for the hosts list
    ├── persistence/
    │   ├── HostPaths.java                               # ~/.config/termlab/ssh-hosts.json resolution
    │   ├── HostsFile.java                               # Atomic JSON save / load via Gson
    │   └── KnownHostsFile.java                          # ~/.config/termlab/known_hosts read / append
    ├── client/
    │   ├── TermLabSshClient.java                          # Thin wrapper around MINA SshClient — stateful,
    │   │                                                #   owns an SshClient instance, creates ClientSessions
    │   ├── SshConnection.java                           # Open session + channel + its TtyConnector
    │   ├── SshTtyConnector.java                         # Implements JediTerm TtyConnector over a ChannelShell
    │   ├── TermLabServerKeyVerifier.java                  # Host-key check against KnownHostsFile + prompt
    │   ├── SshResolvedCredential.java                   # Short-lived holder of username + password / key
    │   │                                                #   material, zeroed on close()
    │   └── SshConnectException.java                     # Typed failures: AUTH_FAILED, HOST_KEY_REJECTED, etc.
    ├── provider/
    │   ├── SshSessionProvider.java                      # Implements TerminalSessionProvider — entry point
    │   │                                                #   called by TermLabTerminalEditor.initTerminalSession
    │   └── SshSessionContext.java                       # Pairs an SshHost with the resolved credential ID
    ├── credentials/
    │   ├── SshCredentialResolver.java                   # Maps a host's credentialId to a live
    │   │                                                #   CredentialProvider.Credential. Handles
    │   │                                                #   "vault locked" and "wrong password" flows.
    │   └── SshCredentialPicker.java                     # Shows the vault's CredentialProvider
    │                                                    #   .promptForCredential picker + username override
    ├── toolwindow/
    │   ├── HostsToolWindowFactory.java                  # Implements ToolWindowFactory
    │   ├── HostsToolWindow.java                         # Panel content — tree of folders/hosts,
    │   │                                                #   toolbar with Add / Edit / Delete / Connect
    │   ├── HostCellRenderer.java                        # Host row: label + host:port · username
    │   └── HostsTreeModel.java                          # DefaultTreeModel backed by HostStore
    ├── ui/
    │   ├── HostEditDialog.java                          # Add / edit an SshHost — label, host, port,
    │   │                                                #   username, credential picker dropdown
    │   └── HostKeyPromptDialog.java                     # "First time seeing this host. Accept key?"
    │                                                    #   dialog with fingerprint display
    ├── actions/
    │   ├── NewSshSessionAction.java                     # Menu / palette action — opens a host picker
    │   ├── ConnectToHostAction.java                     # Context-click on a host in the tool window
    │   └── OpenHostsToolWindowAction.java               # Focus the Hosts sidebar
    └── palette/
        └── HostsPaletteContributor.java                 # Implements CommandPaletteContributor —
                                                         #   hosts as palette entries

plugins/ssh/test/com/termlab/ssh/
    ├── model/SshHostTest.java                           # Record round-trip
    ├── persistence/HostsFileTest.java                   # Atomic save / load, round-trip
    ├── persistence/KnownHostsFileTest.java              # Append / match / mismatch
    └── client/SshTtyConnectorTest.java                  # Stubbed channel → byte-for-byte round-trip
```

The plugin is wired into the TermLab product by adding `//termlab/plugins/ssh` to the `runtime_deps` list in `termlab_workbench/BUILD.bazel`'s `termlab_run` target.

---

## Phase 1 — Model and persistence

### Task 1.1: Bazel scaffolding

**Files:**
- Create: `plugins/ssh/BUILD.bazel`
- Create: `plugins/ssh/intellij.termlab.ssh.iml`
- Create: `plugins/ssh/resources/META-INF/plugin.xml` (placeholder, filled in by Phase 4)
- Modify: `BUILD.bazel` (add `//termlab/plugins/ssh` to `termlab_run` runtime_deps)

- [ ] **Step 1: Create BUILD.bazel.**

```python
load("@rules_java//java:defs.bzl", "java_binary")
load("@rules_jvm//:jvm.bzl", "jvm_library", "resourcegroup")

resourcegroup(
    name = "ssh_resources",
    srcs = glob(["resources/**/*"]),
    strip_prefix = "resources",
)

jvm_library(
    name = "ssh",
    module_name = "intellij.termlab.ssh",
    visibility = ["//visibility:public"],
    srcs = glob(["src/**/*.java"], allow_empty = True),
    resources = [":ssh_resources"],
    deps = [
        "//termlab/sdk",
        "//platform/core-api:core",
        "//platform/core-ui",
        "//platform/editor-ui-api:editor-ui",
        "//platform/ide-core",
        "//platform/platform-api:ide",
        "//platform/platform-impl:ide-impl",
        "//platform/projectModel-api:projectModel",
        "//platform/util:util-ui",
        "//libraries/jediterm-core:jediterm-core",
        "//libraries/sshd-osgi",
        "@lib//:jetbrains-annotations",
        "@lib//:gson",
        "@lib//:kotlin-stdlib",
    ],
)

jvm_library(
    name = "ssh_test_lib",
    module_name = "intellij.termlab.ssh.tests",
    visibility = ["//visibility:public"],
    srcs = glob(["test/**/*.java"], allow_empty = True),
    deps = [
        ":ssh",
        "//termlab/sdk",
        "//libraries/junit5",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
        "//libraries/sshd-osgi",
        "@lib//:jetbrains-annotations",
        "@lib//:gson",
        "@lib//:kotlin-stdlib",
    ],
)

# Standalone test runner — same pattern as plugins/vault.
java_binary(
    name = "ssh_test_runner",
    main_class = "com.termlab.ssh.TestRunner",
    runtime_deps = [
        ":ssh_test_lib",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
    ],
)
```

- [ ] **Step 2: Create intellij.termlab.ssh.iml.** Copy the shape from `plugins/vault/intellij.termlab.vault.iml`; module name `intellij.termlab.ssh`; sourceFolder entries for `src` / `test` / `resources`; `<orderEntry type="library" name="sshd-osgi" level="project"/>` so IDE classpath resolves MINA types.

- [ ] **Step 3: Placeholder plugin.xml.** Matches the Phase 1 vault plugin.xml:

```xml
<idea-plugin>
    <id>com.termlab.ssh</id>
    <name>TermLab SSH</name>
    <vendor>TermLab</vendor>
    <description>SSH host manager and session provider. Opens remote
    shells as terminal tabs using credentials from the TermLab Vault.</description>

    <depends>com.termlab.core</depends>
    <depends>com.termlab.vault</depends>
    <!-- Extensions, actions, tool window registered in Phase 4+. -->
</idea-plugin>
```

- [ ] **Step 4: Wire into the product.** Add `"//termlab/plugins/ssh",` to `termlab_run`'s `runtime_deps` in the top-level `BUILD.bazel` next to `"//termlab/plugins/vault",`.

- [ ] **Step 5: Build check.** Run: `make termlab-build`. Expected: passes with an empty `ssh.jar`.

- [ ] **Step 6: Commit.**

```bash
git add plugins/ssh/ BUILD.bazel
git commit -m "feat(ssh): plugin module scaffolding"
```

### Task 1.2: SshHost model

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/model/SshHost.java`
- Create: `plugins/ssh/test/com/termlab/ssh/model/SshHostTest.java`

- [ ] **Step 1: Record.**

```java
package com.termlab.ssh.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A saved SSH host. Hostnames and ports aren't secrets — they live in
 * plaintext under ~/.config/termlab/ssh-hosts.json, same treatment
 * ~/.ssh/config already gets. The credentialId points at a
 * {@code CredentialProvider.CredentialDescriptor} in the vault; actual
 * auth material is only fetched at connect time.
 *
 * @param id           stable UUID
 * @param label        user-facing name ("prod-db-primary")
 * @param host         hostname or IP
 * @param port         SSH port, default 22
 * @param username     default username for this host; may be overridden
 *                     at connect time when picking a standalone key
 * @param credentialId vault credential id — may be null if the host was
 *                     added without a saved credential and should always
 *                     prompt at connect time
 * @param createdAt    when the host entry was created
 * @param updatedAt    when it was last edited
 */
public record SshHost(
    @NotNull UUID id,
    @NotNull String label,
    @NotNull String host,
    int port,
    @NotNull String username,
    @Nullable UUID credentialId,
    @NotNull Instant createdAt,
    @NotNull Instant updatedAt
) {
    public SshHost withCredentialId(@Nullable UUID newCredentialId) {
        return new SshHost(id, label, host, port, username, newCredentialId, createdAt, Instant.now());
    }

    public SshHost withLabel(@NotNull String newLabel) {
        return new SshHost(id, newLabel, host, port, username, credentialId, createdAt, Instant.now());
    }
}
```

- [ ] **Step 2: Test.** Construct an instance, call `withCredentialId(newId)`, assert id/label/host/port preserved and `updatedAt` bumped.

- [ ] **Step 3: Build + commit.**

### Task 1.3: HostStore — in-memory holder

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/model/HostStore.java`

A tiny mutable holder backing the tool window. Not an IntelliJ application service yet — Phase 4 promotes it.

```java
public final class HostStore {
    public final List<SshHost> hosts = new ArrayList<>();
}
```

That's literally it — keep the surface area minimal so we don't commit to structure before the tool window's needs are clear.

- [ ] **Step 1: Write.** Commit.

### Task 1.4: HostsFile — atomic JSON persistence

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/persistence/HostPaths.java`
- Create: `plugins/ssh/src/com/termlab/ssh/persistence/HostsFile.java`
- Create: `plugins/ssh/test/com/termlab/ssh/persistence/HostsFileTest.java`

Reuse the TermLabTerminalConfig pattern — pretty-printed JSON, atomic temp+rename, Instant typeadapter (same custom adapter from `VaultGson` — copy it into an `SshGson` holder rather than sharing across modules, to keep the ssh plugin standalone).

- [ ] **Step 1: HostPaths.**

```java
public static Path hostsFile() {
    return Paths.get(System.getProperty("user.home"), ".config", "termlab", "ssh-hosts.json");
}
```

- [ ] **Step 2: HostsFile.save / HostsFile.load with @TempDir tests.**

Serialized envelope shape (in case we need versioning later):

```json
{
  "version": 1,
  "hosts": [
    { "id": "…", "label": "…", "host": "…", "port": 22, "username": "…",
      "credentialId": "…", "createdAt": "2026-04-11T…", "updatedAt": "…" }
  ]
}
```

- [ ] **Step 3: Tests:**
  - Empty round-trip
  - Single host round-trip
  - Missing file → load returns empty
  - Gson Instant adapter matches the vault's (ISO-8601)

- [ ] **Step 4: Commit.**

### Task 1.5: KnownHostsFile

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/persistence/KnownHostsFile.java`
- Create: `plugins/ssh/test/com/termlab/ssh/persistence/KnownHostsFileTest.java`

MINA SSHD has its own `KnownHostsServerKeyVerifier`, but we'll write a thin wrapper to:
- Resolve the path (`~/.config/termlab/known_hosts`)
- Expose `match(host, port, key) → {MATCH, MISMATCH, UNKNOWN}`
- Append new entries after user approval
- Keep OpenSSH `known_hosts` line format for cross-compat with external `ssh`

Implementation choice: use MINA's `KnownHostsServerKeyVerifier` under the hood and adapt its result into our three-value enum.

- [ ] **Step 1-4:** Write wrapper + tests (append, match, mismatch, unknown-host). Tests stub a key via `BouncyCastle Ed25519KeyPairGenerator` — we already have BC available through the vault plugin's deps path, but ssh module should declare its own `//libraries/bouncy-castle-provider` dep to avoid coupling.

- [ ] **Step 5: Commit.**

### Phase 1 gate

Run `bazel build //termlab/plugins/ssh:ssh //termlab/plugins/ssh:ssh_test_lib` + `bazel run //termlab/plugins/ssh:ssh_test_runner`. Expected: all green. `make termlab-build` builds the full product with the new plugin attached as a runtime_dep (no functionality yet).

---

## Phase 2 — SSH connection core

The first phase that actually touches MINA. Goal: given an `SshHost` and a resolved credential, open an interactive shell channel and hand back a `TtyConnector` that JediTerm can render.

### Task 2.1: SshResolvedCredential — the short-lived holder

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/client/SshResolvedCredential.java`

A small `AutoCloseable` record-like class that holds the username + one of: password `char[]`, key-file path + passphrase `char[]`. Auto-zeroes sensitive fields on `close()`. Constructed from `CredentialProvider.Credential` by `SshCredentialResolver` (Task 3.1).

- [ ] **Step 1:** Write. Fields: `String username`, `AuthMode mode`, `char[] password`, `Path keyPath`, `char[] keyPassphrase`. Constructor takes them all, some nullable. `close()` zeroes the two char arrays.

- [ ] **Step 2: Commit.**

### Task 2.2: TermLabSshClient — thin MINA wrapper

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/client/TermLabSshClient.java`
- Create: `plugins/ssh/src/com/termlab/ssh/client/SshConnectException.java`

`TermLabSshClient` owns a single `org.apache.sshd.client.SshClient` instance (which internally pools network resources). Public API:

```java
public SshConnection connect(SshHost host, SshResolvedCredential cred, ServerKeyVerifier verifier)
    throws SshConnectException, IOException, InterruptedException;
```

- [ ] **Step 1:** Build the client on first use via `SshClient.setUpDefaultClient()`, `client.start()`. Cache it. (MINA best practice.)

- [ ] **Step 2: connect() steps:**
  1. `ConnectFuture cf = client.connect(cred.username(), host.host(), host.port()).verify(10_000)`
  2. `ClientSession session = cf.getSession()`
  3. `session.setServerKeyVerifier(verifier)`
  4. `switch (cred.mode())`:
     - `PASSWORD`: `session.addPasswordIdentity(new String(cred.password()))`
     - `KEY`: load private key via `SecurityUtils.getKeyPairResourceParser()`, `session.addPublicKeyIdentity(kp)`
     - `KEY_AND_PASSWORD`: add both identities
  5. `session.auth().verify(15_000)` — throws if auth fails
  6. `ChannelShell channel = session.createShellChannel()`
  7. Open channel; return `new SshConnection(session, channel)`

- [ ] **Step 3: Exception mapping.** Catch MINA's `SshException` / `IOException`, map to:
  - `SshConnectException.HOST_UNREACHABLE`
  - `SshConnectException.AUTH_FAILED`
  - `SshConnectException.HOST_KEY_REJECTED`
  - `SshConnectException.CHANNEL_OPEN_FAILED`

- [ ] **Step 4: Commit.**

### Task 2.3: SshConnection — the handle

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/client/SshConnection.java`

Owns an open `ClientSession` and `ChannelShell`. Exposes `TtyConnector` (see Task 2.4) and a `close()` that tears down both.

- [ ] **Step 1: Write.** Minimal — constructor takes session + channel, `getTtyConnector()` lazy-creates `SshTtyConnector`, `close()` closes channel then session. Commit.

### Task 2.4: SshTtyConnector — the JediTerm bridge

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/client/SshTtyConnector.java`
- Create: `plugins/ssh/test/com/termlab/ssh/client/SshTtyConnectorTest.java`

Implements `com.jediterm.terminal.TtyConnector` on top of `ChannelShell`. This mirrors the existing `LocalPtySessionProvider.LocalPtyTtyConnector` but reads/writes the MINA channel's IO streams instead of a pty4j `PtyProcess`.

The interesting parts:
- `read(char[], offset, length)` — read from `channel.getInvertedOut()` via an `InputStreamReader` configured for UTF-8. Pure byte passthrough — no OSC parsing here. OSC parsing happens upstream in `OscTrackingTtyConnector` inside `TermLabTerminalEditor`.
- `write(byte[])` / `write(String)` — write to `channel.getInvertedIn()`. Flush on every write.
- `resize(TermSize)` — call `channel.sendWindowChange(cols, rows, 0, 0)`. This is the MINA equivalent of the `pty.setWinSize()` call we make in `LocalPtyTtyConnector`.
- `isConnected()` — `channel.isOpen() && !channel.isClosed()`
- `close()` — close the channel; the session stays alive for connection pooling (the `TermLabSshClient` owns session lifecycle)
- `waitFor()` — `channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L)`, return exit status

- [ ] **Step 1: Implement.** Copy `LocalPtyTtyConnector` as a starting skeleton, swap the internals.

- [ ] **Step 2: Test with a stub channel.** Stub `ChannelShell` is hard — MINA classes are final. Instead, write a test that exercises only the read/write/resize paths against an in-memory pipe (`PipedInputStream` / `PipedOutputStream`) and a fake `ChannelShell` accessed via a helper interface the connector can be constructed with. Refactor: introduce `SshTtyConnector.ChannelIo` interface the real channel implements at boundary.

- [ ] **Step 3: Commit.**

### Phase 2 gate

Run `bazel run //termlab/plugins/ssh:ssh_test_runner`. All green. No IntelliJ interaction yet.

---

## Phase 3 — Credential resolution

### Task 3.1: SshCredentialResolver

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/credentials/SshCredentialResolver.java`

Given an `SshHost`, produce an `SshResolvedCredential` by:
1. Look up the vault's `CredentialProvider` extension
2. If `host.credentialId() != null`, call `provider.getCredential(host.credentialId())`
3. If the returned `Credential` has `username=null` (it's a standalone SSH key), inject `host.username()` into the resolved credential
4. If lookup returns null (credential deleted / vault locked), return empty — the caller (provider) then runs a picker

API:

```java
public @Nullable SshResolvedCredential resolve(@NotNull SshHost host);
public @Nullable SshResolvedCredential pick(@NotNull SshHost host, @NotNull Project project);  // shows picker
```

- [ ] **Step 1: Write `resolve` — no UI.** Commit.

### Task 3.2: SshCredentialPicker

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/credentials/SshCredentialPicker.java`

Delegates to `CredentialProvider.promptForCredential()`, which the vault already implements (Phase 4.7 of the vault plan). If the returned `Credential` has `username=null`, shows a small inline dialog asking "Username for this SSH key:" before returning.

- [ ] **Step 1: Write + commit.**

### Phase 3 gate

Build passes. No runtime test — both paths require the vault service, which needs a live IntelliJ app. Covered in the Phase 5 manual smoke test.

---

## Phase 4 — SshSessionProvider + plugin wiring

### Task 4.1: SshSessionProvider

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/provider/SshSessionProvider.java`
- Create: `plugins/ssh/src/com/termlab/ssh/provider/SshSessionContext.java`

Implements `com.termlab.sdk.TerminalSessionProvider`. `createSession()` is the entry point called by `TermLabTerminalEditor` once the user has clicked a host.

- [ ] **Step 1:** `getId()` = `"com.termlab.ssh"`, `getDisplayName()` = `"SSH"`, `canQuickOpen()` = `false` (needs host selection), icon = `AllIcons.Webreferences.Server` or similar.

- [ ] **Step 2: `createSession(SessionContext)` flow:**
  1. Cast `context` to `SshSessionContext` (the host → connector path always uses this); if it's the raw `SessionContext`, run the host picker first
  2. Resolve credential via `SshCredentialResolver.resolve(host)`
  3. If null, call `pick(host, project)` — this shows the vault picker
  4. Attempt connect via `TermLabSshClient.connect(host, cred, verifier)`
  5. On `AUTH_FAILED`, re-run the picker once (gives the user a chance to correct)
  6. On success, return `connection.getTtyConnector()`
  7. Ensure `cred.close()` runs after connect so plaintext material gets zeroed

- [ ] **Step 3: Commit.**

### Task 4.2: HostStore promoted to an application service

**Files:**
- Modify: `plugins/ssh/src/com/termlab/ssh/model/HostStore.java`
- Modify: `plugins/ssh/resources/META-INF/plugin.xml`

- [ ] **Step 1:** Add a no-arg constructor that calls `HostsFile.load(HostPaths.hostsFile())` and stores the result in a mutable list. Expose:
  ```java
  public List<SshHost> getHosts();
  public void addHost(SshHost host);
  public void removeHost(UUID id);
  public void updateHost(SshHost host);
  public void save();          // calls HostsFile.save(path, list)
  ```
  Register as `<applicationService>` in plugin.xml.

- [ ] **Step 2: Commit.**

### Task 4.3: Real plugin.xml

**Files:**
- Modify: `plugins/ssh/resources/META-INF/plugin.xml`

Declares:
- `<depends>com.termlab.core</depends>` (the TerminalSessionProvider extension point)
- `<depends>com.termlab.vault</depends>` (ordering — ssh registers its credential-consumer after the vault registers its provider)
- `<applicationService>` for `HostStore`
- `<toolWindow>` entry for the Hosts sidebar (Phase 5)
- Actions (Phase 5)

Phase 4 gate: product builds clean with the new plugin, and launching TermLab doesn't regress anything. The provider is reachable but not yet triggered from any UI.

---

## Phase 5 — Hosts tool window + UI

The most Swing-heavy phase. Models after `VaultDialog` layout conventions.

### Task 5.1: HostsTreeModel + renderer

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/toolwindow/HostsTreeModel.java`
- Create: `plugins/ssh/src/com/termlab/ssh/toolwindow/HostCellRenderer.java`

v1 is flat (no folders). Build a `DefaultTreeModel` backed by `HostStore.getHosts()`. Two-line cell renderer: label on top, `host:port · username` beneath (mirror `VaultKeyCellRenderer`).

- [ ] **Step 1-3:** Write, simple, commit.

### Task 5.2: HostsToolWindow

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/toolwindow/HostsToolWindow.java`
- Create: `plugins/ssh/src/com/termlab/ssh/toolwindow/HostsToolWindowFactory.java`

The tool window layout:

```
┌──────────────────────┐
│ Hosts          [+][⟳]│   ← toolbar: Add / Refresh
├──────────────────────┤
│ 🖥 prod-db-primary   │
│    db.example.com:22 │
│    · dbadmin         │
│                      │
│ 🖥 prod-db-replica   │
│ ...                  │
└──────────────────────┘
```

- Double-click a host → `ConnectToHostAction` (opens a new editor tab)
- Right-click a host → context menu: Connect, Edit, Duplicate, Delete
- `+` toolbar button → `HostEditDialog` in "add" mode
- `⟳` toolbar button → reload from disk (in case the user edited the JSON manually)

- [ ] **Step 1: HostsToolWindowFactory.** Implements `ToolWindowFactory`, sets anchor to LEFT, adds one content tab with `HostsToolWindow`.

- [ ] **Step 2: HostsToolWindow.** `JPanel` containing toolbar + `JBScrollPane(Tree)`. Hooks for Add / Edit / Delete / Connect. Registers a `VaultStateListener`? — no, hosts are stored outside the vault so vault state doesn't affect them.

- [ ] **Step 3: plugin.xml registration.**

```xml
<extensions defaultExtensionNs="com.intellij">
  <toolWindow id="Hosts"
              anchor="left"
              icon="AllIcons.Webreferences.Server"
              factoryClass="com.termlab.ssh.toolwindow.HostsToolWindowFactory"/>
</extensions>
```

- [ ] **Step 4: Commit.**

### Task 5.3: HostEditDialog

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/ui/HostEditDialog.java`

Modal `DialogWrapper` with:
- Label (text field, required)
- Host (text field, required)
- Port (spinner, default 22, range 1-65535)
- Username (text field, required)
- Credential dropdown — populated from `CredentialProvider.listCredentials()`; filter to kinds that make sense for SSH (`ACCOUNT_PASSWORD`, `ACCOUNT_KEY`, `ACCOUNT_KEY_AND_PASSWORD`, `SSH_KEY`). A `<no credential>` option prompts at connect time.

- [ ] **Step 1-3:** Form layout, validation, wire to `HostStore`. Commit per sub-step.

### Task 5.4: HostKeyPromptDialog

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/ui/HostKeyPromptDialog.java`
- Create: `plugins/ssh/src/com/termlab/ssh/client/TermLabServerKeyVerifier.java`

First-connect flow: show the host's SHA256 fingerprint and either `Accept & Save`, `Accept Once`, or `Reject`.

- [ ] **Step 1:** Verifier queries `KnownHostsFile.match(host, port, key)`:
  - `MATCH` → return true
  - `MISMATCH` → show a scary warning dialog, return false (user has to fix manually)
  - `UNKNOWN` → show `HostKeyPromptDialog`, append to known_hosts if accepted-and-save

- [ ] **Step 2: Wire TermLabServerKeyVerifier into TermLabSshClient.connect().**

- [ ] **Step 3: Commit.**

### Task 5.5: Actions

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/actions/ConnectToHostAction.java`
- Create: `plugins/ssh/src/com/termlab/ssh/actions/NewSshSessionAction.java`

`ConnectToHostAction` is the do-the-real-work path invoked by:
- Double-click on a host in the tool window
- Context menu → Connect
- Palette entry (Phase 6)

It:
1. Creates a `TermLabTerminalVirtualFile` pointing at the `SshSessionProvider` with an `SshSessionContext(host)`
2. Opens it in the editor area via `FileEditorManager.openFile`

`TermLabTerminalEditor.initTerminalSession()` then does the rest (resolving the credential and connecting) without any changes to the editor.

`NewSshSessionAction` is a no-host-preselected version — shows a host picker first, then routes to `ConnectToHostAction`.

- [ ] **Step 1-3: Write both, register, commit.**

### Task 5.6: plugin.xml final wiring

All extensions registered; actions get shortcuts:
- `NewSshSessionAction` → `meta K` (macOS) / `ctrl K` (Linux, Windows).
  Opens the host picker from anywhere — no pre-selected host required.
- `ConnectToHostAction` → no shortcut (it's context-driven)

```xml
<action id="TermLab.NewSshSession"
        class="com.termlab.ssh.actions.NewSshSessionAction"
        text="New SSH Session…"
        description="Open the host picker and start a new SSH session">
    <keyboard-shortcut keymap="$default" first-keystroke="meta K"/>
    <keyboard-shortcut keymap="$default" first-keystroke="ctrl K"/>
</action>
```

### Phase 5 gate (manual smoke test)

`make termlab`. Expected behavior:

1. Left sidebar now has a "Hosts" tool window icon
2. Click → empty tree + Add toolbar button
3. Add a host with a credential that maps to a real reachable server (e.g. `localhost:22` using a key from the vault)
4. Double-click → connects → opens a new editor tab showing the remote shell
5. Type commands, resize the window, disconnect via tab close

If any of 1-5 fails, fix and commit before moving on.

---

## Phase 6 — Command palette integration

### Task 6.1: HostsPaletteContributor

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/palette/HostsPaletteContributor.java`

Implements `com.termlab.sdk.CommandPaletteContributor`. Tab name `"Hosts"`, weight `50` (between Vault 60 and Terminals 100).

Search: every `SshHost` in the store matching the query by label / host / username. Selecting an entry dispatches `ConnectToHostAction` on the host.

- [ ] **Step 1-2:** Write, register in plugin.xml under `<extensions defaultExtensionNs="com.termlab.core">`, commit.

### Phase 6 gate

Cmd+Shift+P → Hosts tab → type → pick a host → session opens in the editor.

---

## Out of scope for v1

Tracked here so they don't get accidentally absorbed:

- **SFTP browser** — its own plan, depends on this plugin existing
- **Port forwarding / tunnels** — same, its own plan
- **Jump hosts / `ProxyCommand` / `ProxyJump`** — relevant but adds significant connection-graph complexity; defer
- **Host folders** — the tool window tree is flat in v1. Folders can be added later without a schema break (just add `List<SshFolder> folders` to the top-level JSON)
- **Agent forwarding** / **X11 forwarding** — MINA supports both, but neither is part of the minimum
- **`~/.ssh/config` import** — punted; users can recreate hosts in the UI. An importer can be added later without touching the schema (it'd write into the same `ssh-hosts.json`)
- **Connection reconnection on network drop** — if the channel dies, the editor tab closes, user reconnects manually. Auto-reconnect is v2.
- **Multiple concurrent sessions to the same host** — works (MINA handles it), but the tool window currently wouldn't show per-session state
- **Connection-level logging / diagnostics** — just rely on MINA's slf4j output for now

---

## Risks and gotchas

**1. MINA client lifecycle.** `SshClient.start()` spawns internal IO threads. Make sure `TermLabSshClient` is either a long-lived application service (so the client pool persists) or is created-and-destroyed in strict pairs. The plan assumes long-lived via `<applicationService>`.

**2. Passphrase-protected keys.** MINA's `SecurityUtils.getKeyPairResourceParser()` can decrypt passphrase-protected keys if you pass a `FilePasswordProvider`. The plan routes `cred.keyPassphrase()` through that provider. Test case: generate an Ed25519 key via the vault (which currently doesn't set a passphrase), then separately test a manually-imported passphrase-protected key before declaring Phase 2 done.

**3. MINA's default ciphers may reject old servers.** MINA 2.15 disables legacy ciphers by default. If the user hits `"No matching algorithms"` against an older sshd, the fix is to call `BuiltinCiphers.aes128ctr.makeAvailable()` (or similar) on the `SshClient`. Add a comment in `TermLabSshClient` noting the spot where re-enables would go — don't actually re-enable anything in v1.

**4. `TermLabTerminalEditor.initTerminalSession()` is synchronous** and calls `provider.createSession(ctx)` on the EDT. An SSH connect takes seconds and must NOT block the EDT. Two options:
   - (a) Make `SshSessionProvider.createSession()` run its own `Task.Modal` (same pattern as `CreateVaultDialog`) and block the EDT only visually with a spinner
   - (b) Make the editor creation itself async

   Plan: option (a). The spinner shows "Connecting to host…" and the session opens when it finishes. If the user cancels, we return null from `createSession` and the editor closes the tab.

**5. Host-key mismatch is the only hard reject.** On UNKNOWN we prompt and add. On MATCH we proceed. On MISMATCH we SHOULD show a scary dialog and abort — never prompt to "accept anyway" because that's a MITM attack signal. The dialog says "Host key mismatch. Someone may be intercepting your connection. Remove the entry from `~/.config/termlab/known_hosts` manually if the key legitimately changed."

**6. Credential char[] lifecycle.** `CredentialProvider.Credential.destroy()` zeroes the char arrays. But the resolver copies them into `SshResolvedCredential` which has its own `close()`. The chain is:
   1. `CredentialProvider.getCredential(id)` returns a Credential
   2. Resolver copies relevant fields into a fresh `SshResolvedCredential`
   3. Resolver calls `credential.destroy()` on the original
   4. Plugin uses the resolved credential, then calls `close()` on it
   5. MINA has already consumed the string/key material by then

   Important: MINA's `session.addPasswordIdentity(String)` takes a `String`, not `char[]`. That's an unavoidable boundary — the String lives in MINA's heap until GC. Noted here as "same class of v1 weakness" as the vault's internal String-backed passwords; fixed in the post-v1 hardening pass alongside the vault's custom Gson TypeAdapter.

---

## Definition of done

A user can:

1. Launch TermLab on a fresh machine with an existing vault
2. Open the Hosts tool window from the left sidebar
3. Add an SSH host (label, hostname, port, username, credential-from-vault)
4. Double-click the host → a new editor tab opens showing the connecting-then-connected remote shell
5. Type commands, resize, copy/paste
6. Close the tab to disconnect
7. Open it again — reuses the saved credential without reprompting
8. Add a host with no credential → connecting prompts via the vault picker
9. Change the password of the underlying vault account → next connect fails with AUTH_FAILED → picker re-prompts → new credential works
10. Connect to an unknown host → sees the host-key prompt with SHA256 fingerprint → accept-and-save → next connect skips the prompt
11. Cmd+Shift+P → Hosts tab → type → pick a host → session opens
12. Cmd+K → host picker opens → pick a host → session opens (same code path as step 11, different entry point)

When all twelve steps work end-to-end on a clean macOS install, this plan is done. The next plans (SFTP, tunnels) build on this one.
