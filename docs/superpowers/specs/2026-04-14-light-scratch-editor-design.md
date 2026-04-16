# Light Scratch Editor — Design

**Status:** Draft
**Date:** 2026-04-14
**Author:** Brainstormed with Claude
**Scope:** MVP opt-in light editor for scratches and SFTP-triggered file editing

## Goals

Add a minimal, opt-in editor surface to TermLab so users can:

1. Create and save scratch text files.
2. Edit local and remote files by double-clicking them in the SFTP panel.

The feature must not affect users who don't opt in: no extra memory, no extra classes loaded, no visible UI changes. Enabling the feature is a deliberate, one-time decision presented on first launch and revocable via Settings.

## Non-Goals

- Full IDE code editing (completions, inspections, refactoring, go-to-definition, etc.).
- Running scripts or executing code. That is a separate feature that may hang off the same plugin later; it is not in scope here.
- Conflict detection for remote file editing (mtime checks, merge on upload). Out of scope for MVP.
- Auto-reload when a remote file changes under the editor.
- A scratches list / persistent scratches / scratches & consoles surface.
- Plaintext-only mode that skips TextMate.

## User Experience

**First launch (fresh install):**
After the workbench is up, a sticky notification appears in the corner:

> **Light scripting & file editing**
> TermLab can provide an editor-like environment for light scripting and remote file editing. Enable it?
> [Enable] [Not now] [Don't ask again]

- **Enable** → enables the editor plugin and prompts restart.
- **Not now** → notification dismisses; same prompt appears on next launch.
- **Don't ask again** → notification dismisses; marker set; no further prompts.

**After opting in (post-restart):**

- **File → New Scratch File** (also bound to `Cmd+N` / `Ctrl+N`, also in the command palette) opens an empty tab `scratch-1.txt` in the main editor area next to the terminal.
- Typing into it, pressing `Cmd+S` / `Ctrl+S` opens a Save-As dialog. After saving, the tab becomes a normal local-file tab.
- Closing a modified scratch shows the standard Save/Discard/Cancel prompt.
- **Double-clicking a file** in either SFTP pane (local or remote) downloads (if remote) and opens it in the main editor area as a tab. `Cmd+S` writes back — to disk for local files, to the remote for SFTP files.
- Subject to a 5 MB size cap, an extension blocklist, and a binary content check.

**After opting out (Settings):**

- Restart required.
- After restart: no New Scratch action, no File menu entry, no `Cmd+N`, SFTP double-click on files is a no-op (current behavior).

## Architecture

### Plugin Layout

A new bundled plugin parallel to the existing ones:

| Field | Value |
|---|---|
| Plugin ID | `com.termlab.editor` |
| Source root | `plugins/editor/src/com/termlab/editor/` |
| Manifest | `plugins/editor/resources/META-INF/plugin.xml` |
| Bazel target | `//plugins/editor:editor` |
| Depends on | `com.termlab.core`, `com.termlab.sftp`, `org.jetbrains.plugins.textmate` |

**Subpackages inside `com.termlab.editor`:**

- `scratch` — `NewScratchAction`, save-as-on-first-save handling.
- `remote` — `RemoteEditService`, temp file management, upload-on-save hook, cleanup.
- `sftp` — extension-point implementations that bridge SFTP double-click into `RemoteEditService` / local open.

**Changes to existing plugins:**

- `core` — first-launch notification, settings page, `firstLaunchHandled` persistent property. All additions here are tiny and always loaded, because `core` is the only place that can run code when the editor plugin itself is disabled.
- `sftp` — declare two new extension points (`RemoteFileOpener`, `LocalFileOpener`), change double-click handlers in `RemoteFilePane` and `LocalFilePane` to delegate to them, and add a public download API on `TransferCoordinator` that the editor plugin can call.

### Distribution & Lifecycle

**Bundled but disabled by default.** The editor plugin is built and packaged into the TermLab distribution alongside `sftp`, `ssh`, etc. The `termlab_run` Bazel target's packaging step seeds `disabled_plugins.txt` in the bundled config skeleton with both `com.termlab.editor` and `org.jetbrains.plugins.textmate`. On a fresh install, neither plugin is loaded at IDE startup.

**Enable/disable toggles the IntelliJ plugin state.** Both the notification's Enable action and the Settings checkbox call `PluginManagerCore.enablePlugins({com.termlab.editor, org.jetbrains.plugins.textmate})` / `disablePlugins(...)` and then show IntelliJ's standard "Restart required" dialog. Toggling requires a restart in both directions. We use IntelliJ's plugin-enabled state as the single source of truth — we do not shadow it with a separate boolean setting.

**First-launch marker.** A `PropertiesComponent` key `termlab.editor.firstLaunchHandled` (application-level, owned by `core`) controls whether the first-launch notification fires. It is set to `true` on Enable and on Don't-ask-again. It is not set on Not-now, so the prompt reappears next launch.

### Editor Plugin Internals

#### `com.termlab.editor.scratch`

- `NewScratchAction extends AnAction`, registered under `<action id="TermLab.Editor.NewScratch">` in `plugin.xml`.
  - Placed in `FileMenu` at the top.
  - Keyboard shortcut: `meta N` (Mac) / `control N` (Win/Linux).
  - Automatically available in the command palette via action registration.
- On invoke: creates `LightVirtualFile("scratch-N.txt", PlainTextFileType.INSTANCE, "")` where `N` is a per-session counter. Opens via `FileEditorManager.openFile(file, true)`.
- **Save-As on first save.** A `FileDocumentManagerListener.beforeDocumentSaving` checks whether the document's file is a `LightVirtualFile` with our scratch marker. If so:
  1. Show `FileChooserFactory.createSaveFileDialog`.
  2. Write the document text to the chosen path as UTF-8.
  3. Refresh via `LocalFileSystem.refreshAndFindFileByNioFile`.
  4. Close the scratch tab and open the new real file. Caret position and selection are preserved explicitly via `OpenFileDescriptor` so the user isn't yanked to line 1.
  5. Cancel the original save (the `LightVirtualFile` does not get persisted anywhere).
- If the user cancels the Save-As dialog, no write occurs, the scratch tab remains open and modified, and the original `Cmd+S` keystroke has no effect beyond presenting the dialog.
- Standard close-with-unsaved-changes prompt is provided by the platform.

#### `com.termlab.editor.remote`

- `RemoteEditService` — application-level service. Two entry points:
  ```
  void openRemoteFile(HostDescriptor host, RemoteFileEntry entry)
  void openLocalFile(LocalFileEntry entry)
  ```
  Both are fire-and-forget from the caller's perspective. Errors surface as notification balloons, not return values. Internally the remote path runs the full download flow; the local path shares the size-cap and blocklist guards but skips the download and temp-file steps entirely and opens the file directly via `LocalFileSystem`.
- **Pre-download guards** (run on the EDT, reject instantly):
  - Extension blocklist (case-insensitive): `png jpg jpeg gif bmp ico webp svg zip tar gz tgz bz2 xz 7z rar jar war ear class exe dll so dylib pdf doc docx xls xlsx ppt pptx mp3 mp4 mov avi mkv wav flac pyc pyo`.
  - Size cap: `5 * 1024 * 1024` bytes. Checked against the size already present in the directory listing — no bytes transferred on rejection.
  - Rejection → error balloon; no download, no tab opened.
- **Temp path layout:**
  ```
  {PathManager.getSystemPath()}/termlab-sftp-edits/
    {sha1(host.connectionString)[:8]}/
      {sha1(remoteAbsolutePath)[:8]}/
        {basename}
  ```
  The basename preserves the original filename (including extension) so TextMate picks the correct grammar. The hash prefixes avoid filesystem conflicts between hosts and between files with the same name on different paths.
- **Download** reuses the existing SFTP transfer infrastructure via a new `TransferCoordinator.downloadToLocal(host, remotePath, localPath)` API exposed for this purpose. The `editor` plugin does not open its own SSH sessions.
- **Binary sniff** after download: read first 8 KB; if any byte is `0x00`, delete the temp file and show balloon "Binary file detected: {filename}".
- **Open** via `LocalFileSystem.refreshAndFindFileByNioFile` + `FileEditorManager.openFile`. A `RemoteFileBinding` record (`{tempPath, host, remotePath, remoteSize}`) is stored in a `ConcurrentHashMap<String, RemoteFileBinding>` keyed by temp path, owned by `RemoteEditService`.
- **Save-and-upload hook:** another `FileDocumentManagerListener` observes saves. If the saved file has a remote binding, after the local write completes it kicks off an async upload via `TransferCoordinator.uploadFromLocal`.
  - On success → info balloon "Uploaded to {host}:{remotePath}".
  - On failure → error balloon "Upload failed: {message}" with a `Retry upload` action that re-runs the upload using the existing local temp file. The local file is never cleaned up after a failed upload until the user either retries successfully or closes the tab.
- **Concurrent opens of the same file are a feature.** Two double-clicks on the same remote path resolve to the same temp path, so `FileEditorManager.openFile` focuses the existing tab. No special handling needed.

##### Cleanup Policy

1. **On tab close** (`FileEditorManagerListener.fileClosed`): if the closed file has a remote binding, delete its temp file. Then walk up: delete the per-path dir if empty, and the per-host dir if empty.
2. **On app shutdown** (`AppLifecycleListener.appWillBeClosed`): delete the entire `termlab-sftp-edits` root directory.
3. **On editor plugin startup** (post-enable restart or every subsequent launch): sweep `termlab-sftp-edits` and delete anything present — these are orphans from a crash or a forced quit. This runs in a background thread so it doesn't block startup.

#### `com.termlab.editor.sftp`

The bridge between SFTP double-click and the editor:

- **Two new extension points declared in the `sftp` plugin** (`sftp` owns them because it is the consumer):
  - `com.termlab.sftp.remoteFileOpener` with interface `RemoteFileOpener { void open(HostDescriptor host, RemoteFileEntry entry); }`
  - `com.termlab.sftp.localFileOpener` with interface `LocalFileOpener { void open(LocalFileEntry entry); }`
- **`editor` plugin registers implementations** for both extension points via `<extensions defaultExtensionNs="com.termlab.sftp">` in its `plugin.xml`.
- **`sftp` double-click handlers changed** in `RemoteFilePane.onRowActivated` and `LocalFilePane.onRowActivated` (file case only — directory navigation is unchanged):
  ```
  var openers = RemoteFileOpener.EP_NAME.getExtensionList();
  if (openers.isEmpty()) return;  // current no-op behavior preserved
  openers.get(0).open(host, entry);
  ```
  If the editor plugin is disabled, zero extensions are registered and the branch short-circuits exactly as today.

### First-Launch, Settings, and State (in `core`)

**Notification:**

- Registered notification group `com.termlab.editor.firstLaunch` with `displayType="STICKY_BALLOON"` in `core/plugin.xml`.
- Triggered from a `ProjectActivity` (`postStartupActivity`) that runs after the terminal tool window initializes.
- Guard: skip if `termlab.editor.firstLaunchHandled` is `true`.
- Three actions as described above.

**Settings page:**

- New top-level `<applicationConfigurable>`:
  - `id="termlab.workbench.settings"`, `displayName="TermLab"`, no parent.
  - This is a new group sibling to `termlab.terminal.settings`. Rationale: terminal settings are narrowly about the terminal; workbench-level toggles (like the editor) belong in a distinct group so adding more opt-in features later doesn't pollute the terminal tree.
- Subpage `id="termlab.workbench.settings.editor"`, `parentId="termlab.workbench.settings"`, `displayName="Light Editor"`.
- UI: a single checkbox "Enable light editor and remote file editing", a short description, and a status label that reads "Restart required to apply" when the toggle state differs from the actual plugin state.
- `apply()` calls `PluginManagerCore.enablePlugins` / `disablePlugins` for `{com.termlab.editor, org.jetbrains.plugins.textmate}` and prompts for restart.
- `reset()` reads the current plugin state every time the page is opened — no drift.

**Persistent state (application-level `PropertiesComponent`):**

- `termlab.editor.firstLaunchHandled: boolean` — gates the first-launch notification.

Everything else — whether the feature is currently active — is derived from IntelliJ's plugin-enabled state. One source of truth.

### Bazel Packaging Change

The `termlab_run` product target must seed the bundled config skeleton with a `disabled_plugins.txt` containing:

```
com.termlab.editor
org.jetbrains.plugins.textmate
```

This is what makes the feature "off by default on fresh install." The exact shape of this change depends on how the current packaging produces the bundled config directory — needs investigation in the implementation plan. If the current packaging does not expose a hook for writing into the config skeleton, a small Bazel rule will be added.

## File Handling Details

**Encoding:** UTF-8 default. BOM detection and preservation are handled by the IntelliJ platform; no custom logic.

**Scratches:**
- Names: `scratch-N.txt`, counter reset each session, never persisted across launches.
- Type: `LightVirtualFile` + `PlainTextFileType`.
- Never written to disk unless the user explicitly saves via the Save-As flow.
- Multiple scratches can be open simultaneously.

**Remote files:**
- Size cap 5 MB (private constant).
- Extension blocklist hard-coded (not user-configurable in MVP).
- Null-byte sniff on first 8 KB after download.
- Temp root under `{PathManager.getSystemPath()}/termlab-sftp-edits/`.
- Cleanup on tab close, app shutdown, and startup orphan sweep.
- Upload on save is automatic; failure keeps local changes and offers retry.

**Local files** (opened via SFTP local pane double-click):
- Size cap and blocklist apply via `RemoteEditService.openLocalFile`, which shares the guard logic with the remote path.
- No temp file. Open is `LocalFileSystem.refreshAndFindFileByNioFile` + `FileEditorManager.openFile` directly.
- Save is a normal disk write — no upload step, no binding registered.
- The binary sniff is skipped — the user can already see the file in the local pane and chose to double-click it; if IntelliJ's editor can't render it, the platform handles the display.

## Testing

Tests follow existing plugin conventions (Bazel test targets per plugin, mix of unit and light platform tests).

**`plugins/editor` unit tests:**

- `NewScratchActionTest` — opens scratch, verifies `LightVirtualFile` is used, counter increments, Save-As flow converts to a real file with caret preserved.
- `ExtensionBlocklistTest` — table-driven; every blocklist entry plus negative cases; case-insensitive matching.
- `BinarySnifferTest` — null byte at 0, null byte at 8191, no null bytes, empty file, file smaller than 8 KB.
- `TempPathResolverTest` — hash collision isolation, basename preservation including dotfiles and files with multiple dots.
- `RemoteEditServiceTest` — mock SFTP client; covers size-cap rejection, blocklist rejection, binary rejection, successful open, successful save+upload, upload failure + retry.
- `CleanupPolicyTest` — tab close, app shutdown, startup orphan sweep. Files and empty parent dirs deleted; non-empty dirs preserved.

**`plugins/sftp` tests:**

- `RemoteFileOpenerExtensionPointTest` — zero extensions → no-op (current behavior preserved); one extension → called with correct arguments.
- `LocalFileOpenerExtensionPointTest` — same.

**`core` tests:**

- `FirstLaunchNotificationTest` — marker absent shows notification; marker present does not; each action sets the marker correctly (except Not now).
- `EditorSettingsConfigurableTest` — `apply()` calls the correct enable/disable APIs and prompts restart; `reset()` re-reads plugin state.

**Manual verification (not automated in MVP):**

- Fresh install → first launch shows notification → Enable → restart → create scratch → save as → reopen → works.
- Opt in, open a real SFTP file, edit, save, reopen externally → changes present on remote.
- Opt in, open a 10 MB file → clean rejection, no download.
- Opt in, open a `.jar` → blocklist rejection.
- Opt in, open a file whose extension isn't blocked but contents are binary → download, sniff, reject, temp file deleted.
- Opt out via Settings → restart → New Scratch action gone, SFTP double-click on a file is a no-op.

## Error Handling

| Failure | Surface | Recovery |
|---|---|---|
| Download connection drop / auth / missing path | Error balloon via existing SFTP error formatting | No tab opened, no temp file left |
| Upload failure (any cause) | Error balloon with Retry action | Local changes preserved on disk; retry re-runs upload |
| Temp dir unwritable | Error balloon "Cannot create temp file for editing" | No tab opened |
| SFTP session died between open and save | Same as upload failure | User reconnects in SFTP pane and retries |
| User manually edited `disabled_plugins.txt` | None — IntelliJ handles it | Settings page reads real state on every open |
| File above 5 MB | Error balloon "File too large (X MB). Max 5 MB." | No download initiated |
| Blocklisted extension | Error balloon "Binary file; cannot edit." | No download initiated |
| Binary content (null byte in first 8 KB) | Error balloon "Binary file detected: {filename}" | Temp file deleted |

## Known Limitations (documented, not MVP bugs)

1. No conflict detection — if the remote file changes between open and save, we silently overwrite it.
2. No auto-reload — if the remote file changes while our editor tab is open, we don't notice.
3. Enabling and disabling both require a restart.
4. No plaintext-only fallback — enabling the editor always enables TextMate.
5. Enable/disable is all-or-nothing for the editor + TextMate pair. We don't allow one without the other.
6. No Save-All semantics across multiple modified remote files; each saves independently.
7. Size cap and blocklist are hard-coded; no user customization.

## Risks

- **TextMate memory footprint.** Grammars are lazy per-file, but plugin registration has a fixed cost. Should be measured after implementation; prune bundled grammars if ugly.
- **Seeding `disabled_plugins.txt` at packaging time.** Bazel hook shape is unknown. Flagged as an implementation-plan task, not a brainstorm blocker.
- **`LightVirtualFile` → real file swap on Save-As.** IntelliJ has no clean "convert this tab to another file" API. Closing and reopening with preserved caret may produce visible flicker. Fallback: close scratch, open real file as a fresh tab.
- **New `TransferCoordinator.downloadToLocal` / `uploadFromLocal` public API in SFTP.** Small public-surface expansion; easy to keep stable but worth reviewing.

## Follow-ups (Out of Scope for MVP)

- Run scripts locally or remotely (the second half of the original motivation).
- IdeaVim compatibility verification — expected to work by virtue of using native `Editor` instances, but needs end-to-end testing and possibly a terminal opt-out fix. Not in MVP scope.
- Conflict detection for remote edits (mtime check before upload).
- Auto-reload for remote files changed under the editor.
- Configurable size cap and blocklist.
- Plaintext-only mode that skips TextMate entirely.
- Additional language plugins bundled conditionally on the editor opt-in.
