# SFTP Virtual File System & Unified Save Flow — Design

**Status:** Draft
**Date:** 2026-04-14
**Author:** Brainstormed with Claude
**Scope:** Build a real `SftpVirtualFileSystem` so the IntelliJ platform's `FileChooserDialog` / `FileSaverDialog` work transparently for both local and remote files. Wire `Cmd+Shift+S` "Save Scratch to Remote…" through the new VFS. Migrate the existing SFTP-double-click flow off the temp-file/`RemoteFileBinding` machinery and onto the VFS, leaving a single canonical "remote file in editor" code path.

## Goals

1. **Unified file-picker UX**: any place in the editor that asks the user to pick a file (open or save) should use the IntelliJ-native `FileChooser` / `FileSaverDialog`. When an SFTP host is involved, the same dialog browses the remote filesystem natively — no separate custom widget, no parallel chooser.
2. **Save scratch to remote**: a new `Cmd+Shift+S` action lets the user save the current scratch file to an SFTP host, with atomic write semantics where the remote supports them.
3. **Single canonical remote-file-in-editor path**: after this spec lands, every "remote file open in the editor" interaction goes through `SftpVirtualFileSystem`. The temp-file + `RemoteFileBinding` + `RemoteSaveListener` machinery from the original light-editor spec is deleted.

## Non-Goals

- `File → Open Remote File…` action (a separate, smaller spec built on the same VFS).
- Multi-host SFTP tool window (more than one simultaneous host connection in the SFTP tool window).
- External change detection / file watching on remote files.
- Conflict detection on save (we still last-write-wins, same as today).
- Implementing the full set of `VirtualFileSystem` mutation operations (`deleteFile`, `moveFile`, `renameFile`, `copyFile`, `createChildFile`, `createChildDirectory`). The save flow doesn't need them; they inherit `UnsupportedOperationException` defaults from `DeprecatedVirtualFileSystem` and are added on demand.

## Architecture

Three new components plus one migration:

1. **`SftpVirtualFileSystem`** — a `DeprecatedVirtualFileSystem` subclass registered under protocol `sftp`. Single instance, multi-host routing.
2. **`SftpSessionManager`** — application-level service that owns SFTP sessions per host, with reference-counted lifecycle so the SFTP tool window and any number of editor tabs can share one session per host.
3. **`SaveScratchToRemoteAction`** — new `AnAction` for `Cmd+Shift+S` that launches the host-selection flow and the platform's `FileSaverDialog` rooted at the remote path.
4. **Migration**: rewrite the existing `RemoteFileOpener` / `LocalFileOpener` extension-point implementations in the editor plugin to use the VFS. Delete the temp-file machinery wholesale.

The `LocalFileOpener` post-migration is trivial: it asks `LocalFileSystem` for the `VirtualFile` and calls `FileEditorManager.openFile`. No special path needed.

## `SftpVirtualFileSystem`

### Base class choice

Extends **`com.intellij.openapi.vfs.DeprecatedVirtualFileSystem`** (not `NewVirtualFileSystem`). Despite the class name, `DeprecatedVirtualFileSystem` is not actually marked `@Deprecated` — it's the supported base class for non-`ManagingFS`-integrated VFS implementations. It provides:
- An `EventDispatcher<VirtualFileListener>`-backed listener mechanism with `addVirtualFileListener` / `removeVirtualFileListener` already implemented.
- Helper methods like `fireFileCreated`, `fireContentsChanged`, `fireBeforeContentsChange` for raising VFS events from our mutations.
- Default implementations of `deleteFile`/`moveFile`/`renameFile`/`createChildFile`/`createChildDirectory`/`copyFile` that throw `UnsupportedOperationException` — exactly what we want for the unsupported-mutation methods.

`NewVirtualFileSystem` was rejected because it integrates with `ManagingFS`, the platform's persistent VFS database. For SFTP — where files are host-specific, sessions are transient, and we don't want remote file metadata bloating the persistent database across IDE restarts — that integration would be actively harmful. The "data provider" model `NewVirtualFileSystem` uses also forces an architecture where the platform owns the `VirtualFile` instances; we want to own them so we can attach SFTP-specific state and lifecycle.

### Registration

| Field | Value |
|---|---|
| Class | `com.termlab.sftp.vfs.SftpVirtualFileSystem` |
| Source root | `plugins/sftp/src/com/termlab/sftp/vfs/` |
| Plugin manifest entry | `<virtualFileSystem implementationClass="com.termlab.sftp.vfs.SftpVirtualFileSystem" key="sftp" physical="true"/>` in `plugins/sftp/resources/META-INF/plugin.xml` |
| Lookup | `VirtualFileManager.getInstance().getFileSystem("sftp")` |

### Protocol & URL format

- `getProtocol()` returns `"sftp"`.
- URL form: `sftp://<hostId>/<absolute-remote-path>` where `<hostId>` is the **stable UUID** from `SshHost.id().toString()`. Example: `sftp://550e8400-e29b-41d4-a716-446655440000//etc/nginx/nginx.conf`. The double slash separates the host identifier from the absolute path that itself begins with `/`.
- Internal helper: `com.termlab.sftp.vfs.SftpUrl` — a record `(String hostId, String remotePath)` with static `parse(String)` and `compose(UUID, String)` methods. Used everywhere a path needs to be split.
- The hostId is opaque to humans and is never shown in the UI. The chooser title carries the host's display label; the path bar shows just the remote absolute path.

### Methods we override

- `getProtocol()` → returns `"sftp"`.
- `findFileByPath(String path)` — parses to `SftpUrl`, looks up the `SshHost` by UUID via `HostStore`, ensures the session is connected via `SftpSessionManager` (acquiring a self-owned reference on first lookup per host), stats the remote path, returns the cached `SftpVirtualFile` instance from the hash-cons map (or instantiates one).
- `refreshAndFindFileByPath(String path)` — clears the file's cached attributes, then `findFileByPath`.
- `refresh(boolean async)` — invalidates all cached attributes and child listings across all `SftpVirtualFile` instances. Async variant submits to `AppExecutorUtil`; sync runs inline.
- `isReadOnly()` → returns `false`.
- `extractPresentableUrl(String path)` — returns `<host-label>:<remote-path>` for display in error messages and dialog titles.

### Methods inherited from `DeprecatedVirtualFileSystem` (no override needed)

`addVirtualFileListener` / `removeVirtualFileListener` are already implemented via the base class's `EventDispatcher`. We use the inherited helpers `fireFileCreated`, `fireContentsChanged`, etc. when our own mutations succeed.

`deleteFile`, `moveFile`, `renameFile`, `copyFile`, `createChildFile`, `createChildDirectory` already throw `UnsupportedOperationException` from `DeprecatedVirtualFileSystem`'s defaults — we don't override them. If a future caller invokes one, the exception surfaces with a clear message and we add the implementation then.

The `FileSaverDialog` save flow does not call any of the unsupported mutations; the platform's `VirtualFileWrapper` for save destinations doesn't require the parent VFS to support `createChildFile` for the destination path because the dialog returns a `java.io.File` wrapper that we resolve back through `findFileByPath` after constructing the path string ourselves.

### Multi-host routing

The single `SftpVirtualFileSystem` instance handles all hosts. Routing happens at parse time: every operation extracts the `hostId` from the path, asks `SftpSessionManager` for the session, and dispatches the SFTP call through that session.

## `SftpVirtualFile`

Extends `com.intellij.openapi.vfs.VirtualFile` directly. Same approach `LightVirtualFile` and the platform's `DummyFileSystem`-backed files use. The `FileChooserDialog` walks `VirtualFile.getChildren()` and only requires that the children are `VirtualFile` instances with working `getName()`/`isDirectory()`/`getChildren()` — no `NewVirtualFile` semantics needed.

### State

- `final SftpVirtualFileSystem fs` — back-reference
- `final String hostId` — UUID
- `final String remotePath` — absolute path on the remote
- `final boolean isDirectory`
- `volatile long length` — cached; `< 0` means uncached
- `volatile long modificationStamp` — cached; bumped on every successful write
- `volatile long timestamp` — cached
- `volatile SftpVirtualFile parent` — lazily computed from `remotePath`
- `volatile VirtualFile[] cachedChildren` — directory listing cache; `null` until first call

Instances are hash-cons'd by `(hostId, remotePath)` via a `ConcurrentHashMap` in `SftpVirtualFileSystem` so two `findFileByPath` calls for the same URL return the same instance — required by VFS identity semantics.

### Read

- `contentsToByteArray()` — opens an SFTP read stream, reads to a `ByteArrayOutputStream`, returns bytes. Wrapped in `ProgressManager.runProcessWithProgressSynchronously` modal progress when called from the EDT and the file is larger than 256 KB. Smaller files read inline.
- `getInputStream()` — returns a streaming `InputStream` from `SftpClient.read(remotePath)` directly. Caller closes.
- `getLength()` — returns cached `length`, statting on demand if uncached.
- `getTimeStamp()` — cached.
- `getModificationStamp()` — cached; bumped on write.

### Write (atomic)

- `getOutputStream(Object requestor, long modStamp, long timeStamp)` — returns an `OutputStream` that buffers all writes to an in-memory `ByteArrayOutputStream`. On `close()`:
  1. Compute a sibling temp path: `<remotePath>.<random8>.tmp`.
  2. Open an SFTP write stream to the temp path.
  3. Write the buffered bytes; close the temp stream.
  4. Call `SftpClient.rename(tempPath, remotePath)`.
  5. On any exception during steps 2–4, attempt `SftpClient.remove(tempPath)` (best-effort, swallow errors), then propagate the original exception as `IOException`.
- `setBinaryContent(byte[] content, long modStamp, long timeStamp, Object requestor)` — same path as a one-shot write.

**Why buffer in memory rather than streaming directly?** Streaming directly to the temp file means a partial write on connection drop leaves an orphaned `.tmp` on the remote AND the destination is never updated. Buffering means we only open the SFTP write stream when we already have the full content in memory — the network operation either succeeds completely or fails completely, with no partial-write window inside the body. Memory cost is bounded by the existing 5 MB size cap on the open path; scratches are typically much smaller.

**Atomic rename fallback:** SFTP `RENAME` is atomic on POSIX hosts. On non-POSIX SFTP servers (some Windows-based daemons, certain embedded servers) the rename may not be atomic. If `rename(tempPath, remotePath)` fails because the destination already exists, the implementation falls back to: delete the destination, then rename. The fallback is logged once per host as a warning. Documented as a known limitation; not a guaranteed atomic operation in that case.

### Directory listing

- `getChildren()` — if `cachedChildren` is non-null, returns it. Otherwise calls `SftpClient.readDir(remotePath)`, builds `SftpVirtualFile` instances for each entry (via the hash-cons map), caches, returns. Cold listing on the EDT is wrapped in modal progress.
- `findChild(String name)` — walks `getChildren()` (or stats directly if not cached). Used by `findFileByPath` for path resolution.
- Cache invalidation: `refresh(boolean)` clears `cachedChildren`. The save flow calls `parent.refresh(false)` after a successful write so the new file appears.

### Identity & validity

- `equals` and `hashCode` based on `(hostId, remotePath)`. Hash-consing means `==` works too; `equals` is for cross-VFS comparisons the platform sometimes does.
- `getUrl()` → `"sftp://" + hostId + remotePath`.
- `getPath()` → `getUrl()` minus the protocol prefix.
- `getName()` → trailing path segment.
- `getParent()` → lazily computed and cached.
- `isValid()` returns true unless explicitly invalidated. We invalidate when:
  - The session is torn down by `SftpSessionManager` after the last owner releases.
  - A refresh discovers the file no longer exists on the remote.

## `SftpSessionManager`

### Purpose

One application-level service that owns SFTP sessions per host UUID. Encapsulates the existing connect logic from `RemoteFilePane.connect(...)` so both the SFTP tool window and the new VFS share one credential resolver, one host-key verifier, one proxy-jump path. Reference-counts session usage so a session is closed only when its last consumer releases it.

### Registration

| Field | Value |
|---|---|
| Class | `com.termlab.sftp.session.SftpSessionManager` |
| Source root | `plugins/sftp/src/com/termlab/sftp/session/` |
| Service level | `Service.Level.APP`, `Disposable` |
| Plugin manifest entry | `<applicationService serviceImplementation="com.termlab.sftp.session.SftpSessionManager"/>` in `plugins/sftp/resources/META-INF/plugin.xml` |

### API

```java
@Service(Service.Level.APP)
public final class SftpSessionManager implements Disposable {

    /** Returns an existing session for the host, or null if none. Non-blocking. */
    @Nullable SshSftpSession peek(@NotNull UUID hostId);

    /**
     * Returns an existing session if connected, or opens a new one.
     * Blocks the calling thread on first connect; safe to call from
     * a background executor or under modal progress. Caller MUST call
     * release(hostId, owner) when done with the session.
     */
    @NotNull SshSftpSession acquire(@NotNull SshHost host, @NotNull Object owner) throws SshConnectException;

    /**
     * Decrements the refcount for (hostId, owner). When the last owner
     * releases, the session is closed and removed from the cache.
     */
    void release(@NotNull UUID hostId, @NotNull Object owner);

    /**
     * Force-disconnect a host, ignoring refcounts. Used by the SFTP tool
     * window's explicit Disconnect button. Editor tabs holding stale
     * references will fail their next operation with an IOException.
     */
    void forceDisconnect(@NotNull UUID hostId);

    /** All currently-connected host UUIDs. */
    @NotNull Set<UUID> connectedHostIds();

    /**
     * Returns the SFTP tool window's active session for the given project,
     * or null if no SFTP tool window is open or no session is connected.
     * Used by SaveScratchToRemoteAction to skip the host picker when the
     * user is already working with a host.
     */
    @Nullable SshSftpSession getActiveSessionForCurrentProject(@NotNull Project project);
}
```

### Internal state

- `Map<UUID, SessionEntry>` where `SessionEntry` holds `(SshSftpSession session, Set<Object> owners)`.
- All access guarded by a single `ReentrantLock`. Network I/O during connect happens with the lock released so other threads can `peek`/`acquire` for different hosts in parallel.

### Reference counting

- `acquire` adds the owner to the `owners` set; if the entry doesn't exist, opens a new session via `TermLabSftpConnector.open(host, target, bastion)` (releasing the lock for the I/O, then re-acquiring to install the entry).
- `release` removes the owner; if the set becomes empty, closes the session and removes the entry.
- `forceDisconnect` closes the session immediately and clears the owners set; future `acquire` calls reconnect.
- Owner identity matters: each consumer passes a stable `Object` reference so release matches acquire. Owner conventions:
  - `RemoteFilePane` instance — 1 ref while connected
  - `SftpVirtualFileSystem` instance — adds a ref the first time `findFileByPath` for a host is called and releases on `Disposer.dispose(...)`
  - Each VFS-backed editor tab — adds a ref on file open, releases on file close via a `FileEditorManagerListener`

### Migration of the existing connect logic

`RemoteFilePane.connect(...)` (today, in `plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java`) becomes a thin wrapper: `manager.acquire(host, this)`, store result in the pane's `activeSession` field, register `RemoteFilePane.this` as an owner. `disconnect()` becomes `manager.release(hostId, this)`. The credential resolution, modal progress, and error-balloon handling moves into the manager so both the tool window and the new save action share one path.

### Active-host accessor

`getActiveSessionForCurrentProject(Project)` looks up the project's `SftpToolWindow` instance via `ToolWindowManager`, asks its `RemoteFilePane.activeSession()` and `currentHost()`, returns the session if both are non-null. This is what `SaveScratchToRemoteAction` uses to decide whether to skip the host picker. The slight coupling to the tool window is acceptable for MVP because there's exactly one SFTP tool window per project; documented with an inline comment.

### Disposal

`Disposable.dispose()` closes all sessions and clears the map. Wired so the platform tears down on app shutdown.

## `SaveScratchToRemoteAction`

### Registration

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

`Cmd+Shift+S` / `Ctrl+Shift+S` is the stock IntelliJ "Save All" shortcut, which `TermLabToolbarStripper` already removes from the TermLab keymap. The slot is free. To verify during implementation, grep `core/src/com/termlab/core/TermLabToolbarStripper.java` for the `SaveAll` action ID; if it's not stripped, fall back to `Cmd+Alt+S`.

### `update()`

Enables the action only when:
1. There's a project, AND
2. The currently-selected editor is on a `LightVirtualFile` with `ScratchMarker.KEY == TRUE`.

When disabled, the menu entry stays visible but greyed out so users discover it. `getActionUpdateThread()` returns `BGT` since the checks are cheap and don't touch the EDT.

Specifically disabled cases:
- Selected file is not a `LightVirtualFile` → disabled.
- Selected file is a `LightVirtualFile` but lacks the scratch marker → disabled.
- Selected file is already an `SftpVirtualFile` (i.e., it's already a remote file) → disabled. Use plain `Cmd+S` for those — it writes through the VFS to the existing remote path.
- No project → disabled.

### `actionPerformed` flow

```
1. Get the active scratch file (current FileEditor's file, must be LightVirtualFile + scratch marker).
2. Resolve the target host:
   a. If SftpSessionManager.getActiveSessionForCurrentProject(project) returns a session,
      use its host directly.
   b. Otherwise, show the host picker (see below). User cancels → abort.
   c. Acquire the session via SftpSessionManager.acquire(host, this).
3. Determine starting directory for the dialog:
   - If we used the active session, start at remotePane.currentRemotePath().
   - Otherwise, stat the user's remote home: SftpClient.canonicalPath(".") on the
     freshly-acquired session.
4. Compose starting URL: SftpUrl.compose(host.id(), startingDir).
5. Resolve the starting VirtualFile via VirtualFileManager.findFileByUrl(url).
6. Show the platform's FileSaverDialog with:
   - Title: "Save scratch to " + host.label()
   - FileSaverDescriptor configured for single-file save
   - rootFile = startingVirtualFile (so the dialog opens at the remote dir)
   - default filename = the scratch's current name (e.g., "scratch-1.java")
7. User picks a path + filename → dialog returns a VirtualFileWrapper.
8. Write: get the scratch's document text, encode UTF-8, call setBinaryContent(...) on
   the destination SftpVirtualFile. Atomic .tmp+rename happens inside the file's
   getOutputStream/setBinaryContent.
9. After successful write:
   - Acquire a tab-scoped session reference: SftpSessionManager.acquire(host, newRemoteFile).
     This is the reference that SftpEditorTabListener will release when the tab closes.
   - Release the action's session reference: SftpSessionManager.release(host.id(), this).
     The session does NOT hit refcount 0 between these two calls because we acquire
     before releasing — the manager's owners set transitions {this} → {this, vf} → {vf}.
   - Close the scratch tab (FileEditorManager.closeFile(scratchFile)).
   - Open the new remote file: FileEditorManager.openFile(newRemoteFile, true). The
     direct openFile call from this action does NOT pass through EditorRemoteFileOpener,
     so the action is responsible for the tab-scoped acquire above.
   - Show notification: "Saved to <host.label>:<remotePath>".
10. On any error path BEFORE step 9 (write failure, etc.): release the action's
    session reference (SftpSessionManager.release(host.id(), this)), notification
    with the failure reason, scratch tab stays open so the user can retry.
```

### Host picker

A small `JBPopupFactory.createPopupChooserBuilder(List<SshHost>)`-based popup, modelled on the scratch picker:

- Title: `"Connect to host"`
- Entries: all `SshHost` from `HostStore.getInstance().getHosts()`
- Display: `host.label() + "  (" + host.username() + "@" + host.host() + ":" + host.port() + ")"` — same format the existing SFTP host combo box uses
- Speed search via `setNamerForFiltering(SshHost::label)`
- Visible row count: 8
- On chosen: kicks off the connect on `AppExecutorUtil.getAppExecutorService()` under modal `ProgressManager.runProcessWithProgressSynchronously` titled `"Connecting to <host>…"`. Cancellable. On failure, error notification and abort.

## Migration

### Files deleted (post-migration)

In `plugins/editor/src/com/termlab/editor/remote/`:
- `RemoteFileBinding.java`
- `RemoteFileBindingRegistry.java`
- `RemoteEditService.java`
- `RemoteSaveListener.java`
- `RemoteEditorCleanup.java`
- `RemoteEditorShutdownListener.java`
- `RemoteEditorStartupSweep.java`
- `RemoteEditorProjectListener.java`
- `TempPathResolver.java`

The `remote/` package shrinks to one or two files (or is removed entirely if nothing remains).

In `plugins/sftp/src/com/termlab/sftp/transfer/`:
- `SftpSingleFileTransfer.java` — its job is now `SftpClient.read()` and `SftpClient.write()` called directly inside `SftpVirtualFile`.

In `plugins/editor/resources/META-INF/plugin.xml`:
- `RemoteSaveListener` `<listener>` registration
- `RemoteEditorShutdownListener` `<listener>` registration
- `RemoteEditorStartupSweep` `<postStartupActivity>` registration
- `RemoteEditorProjectListener` `<listener>` in `<projectListeners>`
- `RemoteFileBindingRegistry` and `RemoteEditService` `<applicationService>` registrations

The new `<applicationListeners>` and `<projectListeners>` blocks may end up empty after this — collapse them out if so.

### One-time temp directory cleanup

The `termlab-sftp-edits/` directory under `PathManager.getSystemPath()` becomes orphaned. The new editor plugin startup adds a one-shot cleanup: if the directory exists, recursively delete it. After a few launches we can remove that cleanup too, but for now it ensures users don't end up with stale junk after upgrading. The cleanup itself reuses the same logic as the deleted `RemoteEditorCleanup.purgeRoot` — copy the relevant 10 lines into a small helper before deleting the original class.

### Files rewritten

`plugins/editor/src/com/termlab/editor/sftp/EditorRemoteFileOpener.java` and `EditorLocalFileOpener.java` keep their interfaces but their implementations change:

```java
// EditorRemoteFileOpener
public void open(@NotNull Project project, @NotNull SshHost host,
                 @NotNull SshSftpSession session,
                 @NotNull String absoluteRemotePath,
                 @NotNull RemoteFileEntry entry) {
    if (!OpenGuards.allow(project, entry.name(), entry.size())) return;

    String url = SftpUrl.compose(host.id(), absoluteRemotePath).toString();
    VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(url);
    if (vf == null) {
        notifyError(project, "Could not open " + entry.name() + " on " + host.label());
        return;
    }

    // Binary sniff: read first 8 KB through the VFS, check for null bytes.
    if (BinarySniffer.isBinaryByContent(vf)) {
        notifyError(project, "Binary file detected: " + entry.name());
        return;
    }

    // Register the editor tab as a session owner; the SftpEditorTabListener
    // will release on file close.
    SftpSessionManager.getInstance().acquire(host, vf);

    FileEditorManager.getInstance(project).openFile(vf, true);
}

// EditorLocalFileOpener
public void open(@NotNull Project project, @NotNull LocalFileEntry entry) {
    if (!OpenGuards.allow(project, entry.name(), entry.size())) return;
    VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry.path());
    if (vf == null) {
        notifyError(project, "Could not open " + entry.path());
        return;
    }
    FileEditorManager.getInstance(project).openFile(vf, true);
}
```

The local opener no longer needs anything special — the file is just a `VirtualFile` from `LocalFileSystem`.

### Shared `OpenGuards` utility

The size-cap (5 MB) and extension-blocklist guards consolidate into `com.termlab.editor.guard.OpenGuards`:

```java
public final class OpenGuards {
    private OpenGuards() {}

    public static boolean allow(Project project, String filename, long sizeBytes) {
        if (ExtensionBlocklist.isBlocked(filename)) {
            notify(project, "Cannot edit " + filename + ": binary file type.");
            return false;
        }
        if (sizeBytes > 5L * 1024 * 1024) {
            notify(project, "File too large (" + formatMb(sizeBytes) + " MB). Max 5 MB.");
            return false;
        }
        return true;
    }
}
```

`ExtensionBlocklist` and `BinarySniffer` keep their existing locations and are unchanged.

### `BinarySniffer` for VFS files

`BinarySniffer.isBinary(Path)` exists today. Add a new overload `isBinaryByContent(VirtualFile)` that reads the first 8 KB via `vf.getInputStream()` and applies the same null-byte heuristic. Used by `EditorRemoteFileOpener` after `findFileByUrl` returns and before `openFile`. Keeps the binary detection as a post-load decision; the VFS itself doesn't know about binary files.

### `SftpEditorTabListener`

A new project-scoped `FileEditorManagerListener` in the **SFTP plugin** (not the editor plugin — keeps session lifecycle ownership in one place):

```java
// plugins/sftp/src/com/termlab/sftp/session/SftpEditorTabListener.java
public final class SftpEditorTabListener implements FileEditorManagerListener {
    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        if (!(file instanceof SftpVirtualFile sftp)) return;
        SftpSessionManager.getInstance().release(
            UUID.fromString(sftp.getHostId()), file);
    }
}
```

Registered in `plugins/sftp/resources/META-INF/plugin.xml` under `<projectListeners>`. The owner key is the `VirtualFile` itself, matching the `acquire(host, vf)` in `EditorRemoteFileOpener`.

### Net effect

- ~10 deleted classes
- ~5 new classes (`SftpVirtualFileSystem`, `SftpVirtualFile`, `SftpSessionManager`, `SaveScratchToRemoteAction`, `SftpEditorTabListener`)
- ~2 rewritten implementations (the openers)
- 1 new utility (`OpenGuards`)
- Net code change: probably a moderate reduction (~200–400 lines, depending on how compact the VFS implementation lands).
- Behavioral changes for the user: SFTP-double-click path is unchanged in behavior (still opens, edits, saves; just routed through the VFS internally). `Cmd+Shift+S` is new.

## Threading

| Operation | Thread | Mechanism |
|---|---|---|
| `findFileByPath` (cold, first stat) | Calling thread | Modal `ProgressManager.runProcessWithProgressSynchronously` when EDT-bound |
| `findFileByPath` (warm) | Calling thread | Cached lookup; instant |
| `getChildren` (cold) | Calling thread | Modal progress when EDT-bound |
| `getChildren` (warm) | Calling thread | Cached array; instant |
| `contentsToByteArray` (small file <256 KB) | Calling thread | Inline |
| `contentsToByteArray` (large) | Calling thread | Modal progress when EDT-bound |
| `getOutputStream.close()` (atomic write) | Calling thread | Always wrapped in modal progress when EDT-bound |
| `SftpSessionManager.acquire` (cold) | Calling thread | Modal progress at every EDT-bound call site |
| Scratch save action's connect | EDT → background | `runProcessWithProgressSynchronously` titled "Connecting to <host>…" |

**Why modal progress instead of fully async?** The IntelliJ `FileChooserDialog` is a synchronous EDT-bound UI. Its `getChildren()`, `findChild()`, and similar calls expect a synchronous answer. Returning a "loading…" placeholder is meaningfully more complex and not well-documented for plain `VirtualFile`. Modal progress with a small, instant-cancellable spinner is the pragmatic answer for MVP — it behaves identically to how IntelliJ handles slow local operations like network drive listings. Revisit if it feels janky in practice.

## Caching

- **Stat cache** (`length`, `timestamp`, `modificationStamp` on each `SftpVirtualFile`): persists for the lifetime of the file instance. Refreshed on explicit `refresh()` or after a successful write.
- **Directory listing cache** (`cachedChildren`): persists for the lifetime of the directory instance. Cleared on `refresh()`.
- **Instance cache** (the hash-cons map in `SftpVirtualFileSystem`): file instances are kept by `(hostId, remotePath)` for the lifetime of the session. When the session is released by its last owner and the manager closes it, the instance map entries for that host are cleared and remaining `SftpVirtualFile`s become invalid.
- **No TTL.** Caches don't expire on a timer. External changes on the remote are not detected.
- **Refresh triggers:**
  - Explicit `refresh()` from the FileChooser
  - After a successful write, the parent directory's `cachedChildren` is cleared
  - Reconnect after `forceDisconnect`

## Error Handling

| Error | Surface | Recovery |
|---|---|---|
| Connection refused / auth fail during `acquire` | Modal "Connection failed: <reason>" balloon, action aborts | User retries action |
| Connection drop mid-write | `IOException` from `getOutputStream.close()`; error notification "Save failed: <reason>"; scratch tab stays open with content intact; `.tmp` file may be orphaned (best-effort cleanup attempted) | User retries `Cmd+Shift+S` |
| Connection drop mid-read | `IOException` from `getInputStream().read()`; editor shows file-load-error placeholder; user can close the tab | Reconnect via SFTP tool window, reopen file |
| Permission denied on remote write | `IOException` with EACCES in message; error notification | User picks a different path or fixes permissions |
| Remote rename fails after temp write | Error notification; orphaned `.tmp` cleaned up if possible | User retries or picks a different path |
| `findFileByPath` returns null (path doesn't exist) | Action-specific error notification | N/A |
| Host UUID in URL doesn't resolve to a known host | Error notification "Host no longer exists"; the editor tab fails to open | User reconfigures host or removes the file reference |
| Force-disconnect while tabs are open | Tab content stays visible; next save fails with "Session disconnected"; on retry the manager will reconnect via `acquire` since the action triggers a new acquire flow | User reconnects via SFTP tool window or via the action's pre-flight |
| Atomic move not supported by remote (rare, non-POSIX) | Falls back to delete-then-rename. Logged as a one-time warning per host. Doesn't fail the save. | None needed |

**No silent failures.** Every error path either propagates an `IOException` that the platform recognizes OR shows a notification with a descriptive message. Errors are logged at `WARN` level via `Logger.getInstance(SftpVirtualFileSystem.class).warn(...)` for diagnostics, including the host label and remote path.

**Notification group.** Reuse the existing `"SFTP"` notification group registered in `plugins/sftp/resources/META-INF/plugin.xml`. Keeps SFTP operations under one notification banner.

## Testing

Same approach the original light-editor spec took: pure utilities get unit tests via the existing Bazel `*_test_runner` pattern; platform-bound code gets manual E2E verification.

### Unit tests (new)

- **`SftpUrlTest`** (in the SFTP plugin's test tree, mirroring `editor_test_runner`'s setup as `sftp_test_runner`):
  - Round-trip parse: `"sftp://uuid//etc/foo.conf"` → `SftpUrl(uuid, "/etc/foo.conf")` → back to original
  - Edge cases: empty path, root path `/`, path with `..` (rejected), trailing slash, paths with spaces, paths with non-ASCII characters
  - Invalid inputs: missing protocol, missing hostId, missing path separator
- **`SftpSessionManagerTest`**:
  - Acquire returns the same session for two consecutive calls with the same host
  - Acquire after release reconnects (mocked via a fake connector injected through a constructor parameter)
  - Multiple owners: release one, session stays alive; release last, session is closed
  - `forceDisconnect` closes session even with active owners; subsequent `acquire` reconnects
  - Connector is mocked; no real SSH connections in unit tests
- **`OpenGuardsTest`**: passthroughs to the existing `ExtensionBlocklistTest` and `BinarySnifferTest` — confirming the consolidated guard helper applies blocklist + size cap correctly.

### Existing tests retained / removed

Retained: `ExtensionBlocklistTest`, `BinarySnifferTest`. Both stay where they are; `BinarySniffer` gains the `isBinaryByContent(VirtualFile)` overload and gets a few new tests covering it (small file under 8 KB, file with null at byte 0, file with null only after byte 8192).

Removed: `TempPathResolverTest` along with `TempPathResolver`.

### Manual E2E checklist

1. **Cmd+Shift+S basic flow with active session.** Open SFTP tool window, connect, browse to `/tmp`. `Cmd+N` → new Java scratch. Type some content. `Cmd+Shift+S` → FileSaverDialog appears titled `"Save scratch to <host>"`, rooted at `/tmp`. Pick a filename, save. Notification appears. The new file shows up in the SFTP tool window after refresh. Scratch tab is replaced by a tab whose title is the new filename. Verify on the remote via shell: `cat /tmp/<filename>` matches what was typed.
2. **Cmd+Shift+S with no active session.** Disconnect from SFTP tool window. `Cmd+N` → scratch. `Cmd+Shift+S` → host picker popup appears listing saved hosts. Pick one. Modal "Connecting…" progress. Then the FileSaverDialog opens. Save. The newly-connected session is held by the editor tab; SFTP tool window remains showing "Disconnected".
3. **Subsequent Cmd+S on the saved-to file.** After test 1, type more characters in the now-VFS-backed tab and `Cmd+S`. Should write through the VFS atomically. Verify on the remote.
4. **Open existing remote file via SFTP double-click (regression).** SFTP tool window → connect → double-click an existing text file. Should still open in editor (now via the VFS, not temp files). Edit, `Cmd+S`, verify upload.
5. **Open local file via SFTP local pane (regression).** Double-click a local text file in the SFTP local pane. Opens in editor. Edit, `Cmd+S`, verify written to disk.
6. **Refused file types.** Try double-clicking a `.zip` from the SFTP remote pane. Refused with notification.
7. **Refused file size.** Try opening a >5MB file from the remote pane. Refused.
8. **Connection drop.** Connect, open a remote file, kill the remote connection (e.g., `iptables` or `kill sshd` on the host). `Cmd+S` should fail with a clear notification, scratch content remains in the buffer.
9. **Disconnect from tool window with editor tab open.** Open a remote file via SFTP double-click, then click Disconnect in the SFTP tool window. The editor tab should remain visible. Next `Cmd+S` triggers a reconnect (via `acquire`) — verify it works or surfaces a clear error.
10. **Force quit.** Open a remote file, edit, force-quit TermLab (`kill -9`). Restart. There should be no orphaned files anywhere — no `termlab-sftp-edits` directory (it was deleted by the migration cleanup).
11. **`Cmd+Shift+S` disabled when not on a scratch.** Click into the terminal tab; `Cmd+Shift+S` should be a no-op. Click into a remote file tab opened from SFTP; `Cmd+Shift+S` should also be disabled.

## Known Limitations

1. **No external change detection** — if someone else edits the same remote file while you have it open, you won't see their changes. Same as today.
2. **No conflict detection on save** — last-write-wins. We don't compare mtime before writing.
3. **No async listing** — directory listings during chooser navigation block the EDT under modal progress. May feel sluggish on slow networks.
4. **`SftpVirtualFile.deleteFile`/`moveFile`/`renameFile`/`createChildFile`/`createChildDirectory`/`copyFile` throw `UnsupportedOperationException`**. The save flow doesn't need them. Future callers will surface the exception; we add the implementation then.
5. **Reference counting is owner-keyed by `Object` identity** — passing a different `Object` reference to `release` than was passed to `acquire` is a bug. Helper methods on the manager could enforce this with typed token classes, but for MVP the discipline is enforced by code review.
6. **One canonical "active host" per project** — `getActiveSessionForCurrentProject` returns the SFTP tool window's currently-connected session. If a future feature lets users have multiple SFTP panes open simultaneously, this needs revisiting.
7. **Atomic write fallback on non-POSIX SFTP servers** (Windows-based, certain embedded SFTP daemons) — falls back to non-atomic write (delete + rename). Logged once per host. Documented; not a guaranteed atomic operation in that case.

## Risks

- **VFS contract complexity.** `VirtualFile` has a lot of methods and the platform's expectations for non-`NewVirtualFile` subclasses aren't fully documented. We may discover that some IntelliJ subsystem (the FileChooser, the editor's file modification tracker, the recent files manager) calls a method we haven't implemented and crashes. Mitigation: implement conservatively by reading what `LightVirtualFile` does for each abstract method, add `Logger.warn` stubs in unimplemented methods rather than throwing immediately, tighten throws once we've validated the happy path.
- **Session-tab refcount leaks.** If the `SftpEditorTabListener` doesn't fire for some reason (project closed without normal lifecycle), the session refcount stays > 0 and the session never closes. Mitigation: a project-close `Disposable` that walks all open `SftpVirtualFile`-backed editors in the project and releases their refs explicitly.
- **`getActiveSessionForCurrentProject` reaches into the SFTP tool window UI from a service.** Mild layering inversion. Acceptable for MVP because there's exactly one SFTP tool window per project. Worth a comment in the manager noting the limitation.
- **Migration window.** The migration deletes a lot of working code. If the new VFS implementation has bugs, the old "good" code is gone. Mitigation: all the deleted code is in git history; a single-revert is feasible if the new flow regresses badly. Strong incentive to ship the migration commits in small, reviewable chunks (one for the VFS, one for the session manager, one for the action, one for the migration) rather than one giant change.

## Follow-ups (Out of Scope for This Spec)

1. **`File → Open Remote File…` action** — uses the same VFS, opens the platform `FileChooserDialog`, lets the user pick any remote file via the same UX. Smaller spec, smaller change. Worth doing right after this lands.
2. **Multi-pane SFTP tool window** — multiple simultaneous host connections in one window.
3. **External change detection / file-watching** — SFTP polling or inotify-style notifications.
4. **Implementing the unsupported VFS operations** (delete/move/rename/createChild) for a richer remote file management experience.
5. **Migrating other file-picker call sites** in TermLab to use `SftpVirtualFileSystem` as a root option, so any save-as in the editor can choose local or remote.
