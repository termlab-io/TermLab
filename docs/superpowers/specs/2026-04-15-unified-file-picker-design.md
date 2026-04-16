# Unified File Picker Dialog — Design

**Status:** Draft
**Date:** 2026-04-15
**Author:** Brainstormed with Claude
**Scope:** Build a custom file picker dialog owned by `core/` that handles both local and SFTP (and future) file sources through a pluggable `FileSource` interface. Replace the broken `FileChooser.chooseFile` call in `SaveScratchToRemoteAction` with this dialog. Ship save and open variants together so the architecture supports both from day one, even though the immediate consumer is only `SaveScratchToRemoteAction`. Extract the existing `RemoteFilePane` / `LocalFilePane` table-setup code into a reusable widget that both the dialog and the existing panes share.

## Goals

1. **Fix `Save Scratch to Remote`**: the current `FileChooser.chooseFile` call hits IntelliJ platform code that assumes local-filesystem semantics — it won't render the SFTP virtual filesystem tree and on macOS jumps to the native `NSOpenPanel` entirely. This spec delivers a working remote directory browser for the save flow.
2. **Unified UX between local and remote**: one dialog widget, one look, one keyboard model. A user saving to local or SFTP sees the same controls in the same places.
3. **Designed for reuse**: eventually every file-picker call site in TermLab should use this dialog. The architecture must not hard-code SFTP assumptions; adding a new source type later is registering one interface implementation, not rewriting the dialog.
4. **Shared with the SFTP tool window**: the core table widget used by the dialog is the same widget the existing SFTP tool window panes use. One `FileBrowserTable` implementation, one set of cell renderers, one place to fix bugs.

## Non-Goals

- Replacing every existing file-picker call site in TermLab (follow-up spec per call site).
- A `File → Open Remote File…` action (follow-up; this spec only defines the dialog and wires it into Save Scratch to Remote).
- Directory creation from within the dialog (follow-up).
- Type-filter dropdown, hidden-file toggle, multi-select (follow-ups).
- Replacing `ScratchSaveListener` or `SaveCurrentFileAction` or any local-save flow. Local Cmd+S continues going through `FileDocumentManager.saveDocument`.
- Unit tests for the dialog widget itself — UI Swing tests require a heavy IntelliJ fixture that TermLab doesn't currently have. Deferred to follow-up; manual E2E covers the dialog.

## Architecture

Three modules touch this feature:

### `core/` (new code)

- `com.termlab.core.filepicker.FileEntry` — interface lifted from `plugins/sftp/src/com/termlab/sftp/model/FileEntry.java`. Kept small: `name()`, `size()`, `modified()`, `isDirectory()`, `isSymlink()`, `permissions()`.
- `com.termlab.core.filepicker.FileSource` — the pluggable source interface. See the interface section below.
- `com.termlab.core.filepicker.FileSourceProvider` — extension-point interface that returns a list of `FileSource` instances. Registered via `com.termlab.core.fileSourceProvider`.
- `com.termlab.core.filepicker.LocalFileSource` / `com.termlab.core.filepicker.LocalFileSourceProvider` — built-in always-registered source backed by `java.nio.file.Files`.
- `com.termlab.core.filepicker.FilePickerResult` — record `(FileSource source, String absolutePath)`.
- `com.termlab.core.filepicker.ui.FileBrowserTable` — the extracted table widget (`JBTable` + `FileTableModel` + cell renderers). Consumed by the dialog AND by the refactored `RemoteFilePane` / `LocalFilePane`.
- `com.termlab.core.filepicker.ui.UnifiedFilePickerDialog` — the dialog, extending `DialogWrapper`. Two public static entry points: `showSaveDialog(...)` and `showOpenDialog(...)`.
- `com.termlab.core.filepicker.ui.ErrorMessages` — tiny static helper that maps common IOException messages to friendly sentences.

### `plugins/sftp/` (changes)

- New: `com.termlab.sftp.filepicker.SftpFileSource` — one instance per configured `SshHost`. Implements `FileSource`. `open(...)` acquires via `SftpSessionManager`, `close(...)` releases, `list(...)` calls `session.client().readDir(...)`, `writeFile(...)` delegates to a shared atomic-write helper.
- New: `com.termlab.sftp.filepicker.SftpFileSourceProvider` — registers via `com.termlab.core.fileSourceProvider`. `listSources()` calls `HostStore.getHosts().stream().map(SftpFileSource::new).toList()`.
- New: `com.termlab.sftp.vfs.AtomicSftpWrite` (or integrated into an existing file) — the `.tmp`+rename helper extracted from `SftpVirtualFile.writeAtomically` so both the VFS and `SftpFileSource` call one copy.
- `FileEntry` interface moves to core. `LocalFileEntry` and `RemoteFileEntry` records update their imports to point at `com.termlab.core.filepicker.FileEntry`. No behavioral change.
- `RemoteFilePane` and `LocalFilePane` refactored to embed the new `FileBrowserTable` from core. Internal `JBTable` construction code is removed; everything else (connect UI, transfer buttons, DnD, context menus) stays. Behavior-preserving.
- `FileTableModel` stays in the SFTP plugin, but a few `instanceof` checks are replaced with `FileEntry` interface calls so the model works cleanly with any `FileEntry` implementation.

### `plugins/editor/` (changes)

- `SaveScratchToRemoteAction` — stops using `FileChooser.chooseFile`. Calls `UnifiedFilePickerDialog.showSaveDialog(...)`. The action shrinks from ~200 lines to ~50.
- No other editor-plugin changes.

### Dependency direction

```
core/           ← declares FileSource, owns the dialog, owns the table widget, owns LocalFileSource
   ↑
plugins/sftp/   ← registers SftpFileSourceProvider, refactors panes to use FileBrowserTable
plugins/editor/ ← calls UnifiedFilePickerDialog.showSaveDialog(...)
```

Core has no SFTP dependency. The SFTP plugin depends on core. The editor plugin depends on both.

## The `FileSource` interface

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
     * rely on this ordering. Callers that haven't opened the source
     * yet should not call this method.
     */
    @NotNull String initialPath();

    /**
     * Ensure the source is ready for listing operations. For local
     * this is a no-op; for SFTP this acquires the session via
     * SftpSessionManager. Called on a background thread by the
     * dialog under modal progress. Throws if the source cannot be
     * brought online (connection refused, auth failure, etc.).
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
     * Join a directory path and a child name into a new absolute
     * path. Sources may use different separators (though `/` is
     * the convention for both local and SFTP).
     */
    @NotNull String resolve(@NotNull String directoryPath, @NotNull String childName);

    /**
     * Write bytes to {@code absolutePath}, creating or overwriting.
     * Called by the dialog's caller (e.g. SaveScratchToRemoteAction)
     * after the dialog returns a {@link FilePickerResult}.
     * Implementations handle atomic writes internally: SFTP uses
     * .tmp+rename, local uses Files.write with CREATE+TRUNCATE.
     */
    void writeFile(@NotNull String absolutePath, byte @NotNull [] content) throws IOException;

    /** Read bytes at {@code absolutePath}. Used by the Open flow. */
    @NotNull InputStream readFile(@NotNull String absolutePath) throws IOException;
}
```

## The `FileSourceProvider` extension point

```java
package com.termlab.core.filepicker;

import com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface FileSourceProvider {

    ExtensionPointName<FileSourceProvider> EP_NAME =
        ExtensionPointName.create("com.termlab.core.fileSourceProvider");

    /**
     * Called by the picker when the source dropdown is being built.
     * Providers may return a dynamic list (e.g., one source per
     * configured SFTP host). The list is flat-mapped across all
     * providers into a single dropdown.
     */
    @NotNull List<FileSource> listSources();
}
```

Registered in `core/resources/META-INF/plugin.xml`:

```xml
<extensionPoint name="fileSourceProvider"
                interface="com.termlab.core.filepicker.FileSourceProvider"
                dynamic="true"/>
```

Core ships a built-in `LocalFileSourceProvider` that returns a singleton `LocalFileSource`. The SFTP plugin ships `SftpFileSourceProvider` that returns one `SftpFileSource` per configured host.

## The `LocalFileSource`

Trivial implementation using `java.nio.file.Files`:

- `id() → "local"`
- `label() → "Local"`
- `icon() → AllIcons.Nodes.HomeFolder`
- `initialPath() → System.getProperty("user.home")`
- `open` / `close` — no-ops
- `list(path)` — `Files.list(Paths.get(path))` → map each to `LocalFileEntry.of(p)` (the existing record, now backed by the core `FileEntry` interface)
- `isDirectory(path)` — `Files.isDirectory(Paths.get(path))`
- `exists(path)` — `Files.exists(Paths.get(path))`
- `parentOf(path)` — `Paths.get(path).getParent()?.toString()`
- `resolve(dir, child)` — `Paths.get(dir).resolve(child).toString()`
- `writeFile(path, content)` — `Files.write(Paths.get(path), content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)`
- `readFile(path)` — `Files.newInputStream(Paths.get(path))`

## The `SftpFileSource`

One instance per `SshHost`. Constructor takes `SshHost` (and optionally a `SessionFactory` functional interface for testability). Wraps the existing `SftpSessionManager`.

- `id() → "sftp:" + host.id()`
- `label() → host.label()` (or `host.label() + " (" + host.host() + ")"` if we want more info in the dropdown)
- `icon() → AllIcons.Nodes.WebFolder`
- `initialPath()` — called by the dialog after `open` has successfully acquired a session. Tries `session.client().canonicalPath(".")` (the remote's home dir) and caches the result for the lifetime of the source instance; falls back to `"/"` if the canonical-path call fails. Do not call before `open`.
- `open(project, owner)` — calls `SftpSessionManager.acquire(host, owner)`, caches the returned `SshSftpSession` on the instance until `close` is called
- `close(owner)` — calls `SftpSessionManager.release(host.id(), owner)`
- `list(path)` — calls `session.client().readDir(path)`, maps each `SftpClient.DirEntry` to `RemoteFileEntry.of(dirEntry)`, filters out "." and ".."
- `isDirectory(path)` — `session.client().stat(path).isDirectory()`, catches `IOException` and returns false for not-found
- `exists(path)` — `stat` with catch-and-return-false
- `parentOf(path)` — pure string operation: slice before the last `/`; return `/` for any single-segment path; return null for `"/"` itself
- `resolve(dir, child)` — `dir.endsWith("/") ? dir + child : dir + "/" + child`
- `writeFile(path, content)` — delegates to the shared `AtomicSftpWrite.writeAtomically(session.client(), path, content)` helper (see "Shared atomic-write helper" below)
- `readFile(path)` — `session.client().read(path)` directly (streaming)

**Session ownership lifecycle:**

- The picker's `owner` parameter is the `UnifiedFilePickerDialog` instance itself. When the user picks a source, the dialog calls `source.open(project, this)`, which calls `SftpSessionManager.acquire(host, this)` with the dialog as the ref-count owner.
- If the same session is already held by, e.g., the SFTP tool window, the manager reuses it; both owners share one session.
- When the dialog closes, its `dispose` calls `source.close(this)` for every source it opened, which in turn calls `manager.release(host.id(), this)`. The session is closed only if the dialog was the last owner.
- This means you can open the picker, browse an SFTP host, close the picker WITHOUT saving, and the session stays alive if the SFTP tool window is still holding it. No wasted reconnects.

## Shared atomic-write helper

Currently `SftpVirtualFile.writeAtomically(byte[] content)` contains the `.tmp`+rename logic (with the backup-restore fallback). `SftpFileSource.writeFile(...)` needs the exact same behavior.

Extract the method body into `com.termlab.sftp.vfs.AtomicSftpWrite`:

```java
package com.termlab.sftp.vfs;

import org.apache.sshd.sftp.client.SftpClient;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ThreadLocalRandom;

public final class AtomicSftpWrite {

    private AtomicSftpWrite() {}

    public static void writeAtomically(
        @NotNull SftpClient client,
        @NotNull String remotePath,
        byte @NotNull [] content
    ) throws IOException {
        // (Body matches the current SftpVirtualFile.writeAtomically:
        //  write to writeTmp, try rename, fall back to backup+rename+restore.)
    }
}
```

Both `SftpVirtualFile.writeAtomically` and `SftpFileSource.writeFile` become one-liner delegates to this helper. This is the "leave the code in a better state" change — one atomic-write implementation instead of two.

## `FileBrowserTable` widget

Lives at `com.termlab.core.filepicker.ui.FileBrowserTable`. Consumer API:

```java
public final class FileBrowserTable extends JPanel {
    public FileBrowserTable();

    /** Replace the current listing. Runs on the EDT. */
    public void setEntries(@NotNull List<FileEntry> entries);

    /** Currently-selected entry, or null if none. */
    public @Nullable FileEntry getSelectedEntry();

    /** Called when the user double-clicks a row. */
    public void addDoubleClickListener(@NotNull java.util.function.Consumer<FileEntry> listener);

    /** Called when the selected row changes. */
    public void addSelectionListener(@NotNull Runnable listener);

    /** Visible table component for embedding callers that need direct access. */
    public @NotNull JComponent getComponent();
}
```

Internally it owns a `FileTableModel` (the existing class, moved to `com.termlab.core.filepicker.ui`), a `JBTable`, the three cell renderers (Name, Size, Modified), and a `TableRowSorter`. The column layout matches today's `RemoteFilePane` / `LocalFilePane` exactly.

Both the new picker dialog AND the existing SFTP tool window panes use this widget. The panes add their own surrounding chrome (host dropdown, connect buttons, transfer buttons, context menus, path field, toolbar), but the table in the center is `FileBrowserTable`. No code duplicated between the panes and the picker.

## The dialog: `UnifiedFilePickerDialog`

Extends `com.intellij.openapi.ui.DialogWrapper`. Two public static entry points:

```java
public final class UnifiedFilePickerDialog extends DialogWrapper {

    /**
     * Show the dialog in Save mode. Returns the user's chosen
     * destination, or null if they cancelled.
     */
    public static @Nullable FilePickerResult showSaveDialog(
        @NotNull Project project,
        @NotNull String title,
        @NotNull String suggestedFileName,
        @Nullable String preferredSourceId);

    /**
     * Show the dialog in Open mode. Returns the user's chosen
     * file, or null if they cancelled.
     */
    public static @Nullable FilePickerResult showOpenDialog(
        @NotNull Project project,
        @NotNull String title,
        @Nullable String preferredSourceId);
}
```

Internally both entry points construct the same dialog with a mode flag (`SAVE` or `OPEN`). The mode flag drives:
- Visibility of the filename input row (Save only)
- Default button label ("Save" vs "Open")
- Double-click semantics on files (Save: populate filename; Open: accept and close)
- Whether files are selectable (Open: yes; Save: informational, clicking populates the input)
- The overwrite check (Save only)

### Layout

Logical structure (top to bottom):

1. **Source dropdown row** — `JComboBox<FileSource>` with a custom cell renderer that shows `icon + label`. `ItemListener` triggers source-switch.
2. **Path bar row** — Up button (`AllIcons.Actions.MoveUp`) + editable `JTextField` showing the current absolute path. Enter in the field navigates to the typed path.
3. **File list area** — A `JPanel` with a `CardLayout` managing three cards:
   - `"table"` — the `FileBrowserTable` widget
   - `"loading"` — centered `AsyncProcessIcon` + "Loading…" label
   - `"error"` — centered error icon + message text + "Retry" button
4. **Filename input row** (Save mode only) — `JLabel("File name:")` + `JTextField` pre-populated with `suggestedFileName`.
5. **Button row** — inherited from `DialogWrapper`; the dialog declares `Save` / `Open` as the default action and `Cancel` as the escape action.

### Initial load sequence

1. `showSaveDialog(...)` / `showOpenDialog(...)` constructs the dialog.
2. Dialog builds the source list: `FileSourceProvider.EP_NAME.getExtensionList().stream().flatMap(p -> p.listSources().stream()).toList()`. Sources are sorted by: (preferred-source-id first) → (last-used-source-id next) → (remaining sources alphabetically).
3. Dialog selects the first source from the sorted list.
4. Dialog calls `source.open(project, this)` on a background executor wrapped in `Task.Modal("Connecting to " + source.label() + "...")`. File list card is set to `"loading"`.
5. On success, file list card switches to `"table"` and the dialog lists `source.initialPath()`.
6. On failure, file list card switches to `"error"` with the translated message.
7. Dialog becomes visible to the user once the file list card has a final state (table or error). Alternatively, the dialog is visible throughout with the spinner card; choose whichever feels smoother in testing.

### Source switching

Triggered by the source dropdown's `ItemListener`:

1. Capture the previously-selected source (may be null on first open).
2. Set file list card to `"loading"`.
3. Run `source.open(project, this)` in modal progress.
4. On success:
   - If previous source is non-null, call `previousSource.close(this)`.
   - Update the path field to `newSource.initialPath()`.
   - Run `newSource.list(initialPath)` (also in modal progress if slow).
   - Set file list card to `"table"` with the new entries.
5. On failure:
   - Do NOT close the previous source (user might want to go back).
   - Set file list card to `"error"` with the message.
   - Leave the dropdown's selection on the failed source so the user can retry or pick a different source.

### Directory navigation

Triggered by double-click on a directory row OR by Enter when a directory row is selected OR by Enter in the path field OR by the Up button:

1. Compute the target path.
2. Call `source.list(targetPath)` on a background executor (delayed modal progress).
3. On success: update `currentPath`, update the path field, update the file list.
4. On failure: flash the path field red and revert to the previous path (for Enter-in-field case); show an error dialog for other cases.

### Save flow

Triggered by clicking Save or pressing Enter when focus is in the filename input:

1. Validate: filename is non-empty, contains no `/` or `\`. If invalid, beep or show inline feedback.
2. Compute `destPath = source.resolve(currentPath, filename)`.
3. Call `source.exists(destPath)` in modal progress.
4. If exists: show `Messages.showYesNoDialog("<filename> already exists on <source.label()>. Overwrite?", "File Exists", null)`. If No, dialog stays open. If Yes, proceed.
5. Close the dialog with `close(OK_EXIT_CODE)` and set an instance field `result = new FilePickerResult(source, destPath)`.
6. The `showSaveDialog(...)` static method returns `result` (or null if exit code is CANCEL).
7. The CALLER then calls `result.source().writeFile(result.absolutePath(), bytes)`. The dialog does NOT perform the write itself.

### Open flow

Triggered by double-click on a file OR by clicking Open OR by pressing Enter when a file row is selected:

1. Validate: selection is a non-directory file.
2. Close with `result = new FilePickerResult(source, resolved)`.
3. `showOpenDialog(...)` returns the result. Caller performs the read.

### Result type

```java
package com.termlab.core.filepicker;

import org.jetbrains.annotations.NotNull;

public record FilePickerResult(
    @NotNull FileSource source,
    @NotNull String absolutePath
) {}
```

## Threading model

| Operation | Thread | Wrapping |
|---|---|---|
| Dialog construction | EDT | none |
| Building the source list (via EP lookup) | EDT | none (cheap) |
| Initial `source.open(...)` | Background | `Task.Modal` "Connecting…" |
| `source.list(currentPath)` (delayed) | Background | `Task.Modal` "Loading…" surfacing after 200ms |
| `source.isDirectory(path)` on path-bar Enter | Background | brief modal progress |
| `source.exists(path)` on Save | Background | brief modal progress |
| `source.writeFile(...)` | Caller's thread (SaveScratchToRemoteAction wraps this in its own modal progress) |

The dialog never blocks the EDT on a source call. Every source method that can do network I/O goes through `ProgressManager.getInstance().run(new Task.Modal(...))`. For methods that are usually instant (local listings, cached SFTP listings) the delayed-progress variant avoids spinner flashing.

## Error handling

**Error rendering inside the dialog**: a `CardLayout`-managed file list area with three cards (`"table"`, `"loading"`, `"error"`). The error card shows the translated message, a Retry button (re-invokes the operation that failed), and a "Pick a different source" action (pops the source dropdown open).

**`ErrorMessages` translation** (lives at `com.termlab.core.filepicker.ui.ErrorMessages`):

```java
public static String translate(IOException e) {
    String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
    if (msg.contains("auth fail") || msg.contains("permission denied"))
        return "Permission denied. Check credentials and folder permissions.";
    if (msg.contains("connection refused"))
        return "The host refused the connection. Is SSH running on the expected port?";
    if (msg.contains("no route to host") || msg.contains("unknown host"))
        return "Could not reach the host. Check the hostname and network connection.";
    if (msg.contains("timed out"))
        return "The connection timed out.";
    if (msg.contains("no such file"))
        return "File or directory not found.";
    return "Error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
}
```

Stays small. If it grows past ~15 rules we reconsider.

**Connection failure during source switch**: the error card shows, the previous source is preserved (not closed), and the dropdown stays on the failed source so the user can retry or pick a different source. This means the user can always recover without re-opening the dialog.

**Empty directory**: renders the table card with an empty model plus a subtle overlay label "This folder is empty." No error state.

**Path-bar typo**: user types a non-existent path and hits Enter → `source.isDirectory(path)` returns false → path field flashes red for 300 ms and reverts to the previous valid path. No popup.

**Cancellation during connect**: the modal progress Task is cancellable; cancel calls `source.close(this)` in the task's `onCancel()` to release any partial state. If there was a previous successfully-opened source, the dropdown reverts to it; otherwise the dialog closes.

**Dialog-close cleanup**: `dispose()` calls `close(this)` on every source the dialog ever successfully opened during its lifetime. Final refcount in `SftpSessionManager` should match the state before the dialog was opened.

## Wiring into `SaveScratchToRemoteAction`

After the picker lands, the action shrinks to approximately:

```java
public final class SaveScratchToRemoteAction extends AnAction {

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
            lastUsedRemoteSourceId());
        if (result == null) return; // user cancelled

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

    private static @Nullable String lastUsedRemoteSourceId() {
        return PropertiesComponent.getInstance().getValue("termlab.editor.lastRemoteSourceId");
    }

    private static void rememberLastUsedSource(@NotNull String id) {
        PropertiesComponent.getInstance().setValue("termlab.editor.lastRemoteSourceId", id);
    }

    /**
     * Convert a FilePickerResult into an IntelliJ VirtualFile so the
     * saved file opens as a proper editor tab. Local files go through
     * LocalFileSystem; SFTP files go through SftpVirtualFileSystem.
     */
    private static @Nullable VirtualFile resolveSavedVirtualFile(@NotNull FilePickerResult result) {
        String id = result.source().id();
        if ("local".equals(id)) {
            return LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(Paths.get(result.absolutePath()));
        } else if (id.startsWith("sftp:")) {
            UUID hostId = UUID.fromString(id.substring("sftp:".length()));
            String url = SftpUrl.compose(hostId, result.absolutePath());
            return VirtualFileManager.getInstance().findFileByUrl(url);
        }
        return null;
    }
}
```

Net delta: ~150 lines removed from the action (host picker, connect flow, FileChooser wrapping, `SftpVirtualFile` construction, session transitioning, overwrite handling) and ~50 lines added (the new call-through). All the deleted logic lives exactly once inside the dialog and `SftpFileSource`.

## Refactor of existing panes

`RemoteFilePane` and `LocalFilePane` at `plugins/sftp/src/com/termlab/sftp/toolwindow/` currently construct their own `JBTable`, `FileTableModel`, column renderers, and row sorters. That setup code gets moved into `com.termlab.core.filepicker.ui.FileBrowserTable`.

Refactor steps (mechanical):

1. Move `FileTableModel`, `FileNameCellRenderer`, `SizeCellRenderer`, `ModifiedCellRenderer` from `plugins/sftp/src/com/termlab/sftp/toolwindow/` to `core/src/com/termlab/core/filepicker/ui/`. Update the SFTP plugin's imports.
2. Create `FileBrowserTable.java` in core that wraps the model + table + renderers + row sorter. Expose the consumer API above.
3. In `RemoteFilePane`, replace the ~50-line `JBTable` construction block with `this.table = new FileBrowserTable(); table.addDoubleClickListener(this::onRowActivated); table.addSelectionListener(this::fireConnectionStateChanged);`. Remove the now-unused imports. The DnD transfer handler stays on the pane's surrounding JPanel.
4. Same refactor in `LocalFilePane`.
5. `FileEntry` interface moves from `plugins/sftp/src/com/termlab/sftp/model/` to `core/src/com/termlab/core/filepicker/`. `LocalFileEntry` and `RemoteFileEntry` records update their `implements` clauses to point at the new location. No behavior change.

Rough LoC delta:
- `core/` gains ~200 lines (the widget + interface + local source)
- `plugins/sftp/src/com/termlab/sftp/toolwindow/RemoteFilePane.java` loses ~60 lines
- `plugins/sftp/src/com/termlab/sftp/toolwindow/LocalFilePane.java` loses ~60 lines
- `plugins/editor/src/com/termlab/editor/scratch/SaveScratchToRemoteAction.java` loses ~150 lines, gains ~50
- Net: roughly neutral to slightly negative LoC, but the table logic and the remote-save logic each live in exactly one place.

## Testing

Unit-test what can be tested with pure JUnit 5; rely on manual E2E for the Swing dialog itself.

### New `core_test_runner` infrastructure

The `core` plugin does not currently have a test runner. This spec adds one modeled on `vault_test_runner` and `sftp_test_runner`:

- `core/test/com/termlab/core/TestRunner.java` — shared JUnit 5 platform launcher (copy-paste from vault pattern)
- `core/BUILD.bazel` — add `core_test_lib` + `core_test_runner` targets modeled on vault

### Unit tests (new)

**`core_test_runner`:**

- `LocalFileSourceTest`:
  - `list` returns expected entries for a populated tmp directory
  - `list` of a missing path throws `IOException`
  - `list` of a file (not a directory) throws
  - `isDirectory` / `exists` for directories, files, and missing paths
  - `writeFile` creates a new file with exact content
  - `writeFile` overwrites an existing file
  - `readFile` reads back what was written
  - `parentOf("/")` returns null; `parentOf("/a/b")` returns `/a`
  - `resolve("/a", "b")` returns `/a/b`; `resolve("/a/", "b")` returns `/a/b`
- `ErrorMessagesTest`:
  - Each translation rule triggers on its matching message
  - Unrecognized messages fall through to `"Error: <msg>"`
  - Null message handled gracefully

**`sftp_test_runner`:**

- `SftpFileSourceTest`:
  - `open` acquires a session via an injected `SessionFactory` (verified by a `FakeSessionFactory`)
  - `close` releases
  - `list` decodes `SftpClient.DirEntry` into `RemoteFileEntry` correctly (uses a fake `SftpClient`)
  - `writeFile` delegates to `AtomicSftpWrite.writeAtomically`
  - `parentOf("/")` returns null; `parentOf("/etc/foo")` returns `/etc`
  - `resolve` with and without trailing slashes
- `AtomicSftpWriteTest`:
  - Happy path: write temp, rename, no backup needed
  - Fallback path: first rename fails, backup-then-rename succeeds, backup removed on success
  - Recovery path: fallback's final rename fails, original restored from backup
  - Cleanup path: write-tmp removed on IOException from the write step

### No UI tests

`UnifiedFilePickerDialog`, `FileBrowserTable`, and the refactored panes are all Swing UI. Testing them properly requires a heavy IntelliJ platform fixture (`HeavyPlatformTestCase` or similar) that TermLab doesn't currently have. This spec does NOT add that infrastructure.

Instead, manual E2E verification covers:
- Dialog renders with source dropdown, path bar, file list, (filename input for save), buttons
- Source switching triggers the connect flow
- Directory navigation updates the path bar and file list
- Double-click semantics differ between Save and Open modes
- Overwrite confirmation appears when expected
- Cancel behavior (Escape, Cancel button, Cancel during connect)
- Error rendering in the file list card
- Existing SFTP tool window panes behave identically after the refactor

### Manual E2E checklist

Run after implementation lands.

1. **Build + launch**: `bash bazel.cmd run //termlab:termlab_run` — no startup errors.
2. **Save scratch to local**: `Cmd+N` → Java → type content → `Cmd+Alt+S` → picker opens with "Local" pre-selected → navigate to `~/scratch` → filename input shows `scratch-1.java` → click Save → file lands on disk → notification → scratch tab closes, new tab opens on the real file.
3. **Save scratch to SFTP with active session**: Connect a host in the SFTP tool window → `Cmd+N` → Python → `Cmd+Alt+S` → picker opens with the connected host pre-selected → navigate remote tree → pick a directory → click Save → verify on the remote via shell.
4. **Save scratch to SFTP with no active session**: Disconnect the SFTP tool window → `Cmd+N` → `Cmd+Alt+S` → picker opens → dropdown shows all saved hosts + Local → pick a host → modal "Connecting…" → file list appears → save → verify.
5. **Switch source mid-dialog**: In the picker with Local active, pick a different source → modal connect → file list updates → click Save.
6. **Overwrite confirmation**: Save a file, then `Cmd+N` → `Cmd+Alt+S` with the same filename → Save → "foo.java already exists. Overwrite?" Yes → overwrite. No → dialog stays open.
7. **Cancel during connect**: Pick an unreachable host → modal "Connecting…" → Cancel → dialog returns to previous source (or closes if there wasn't one).
8. **Connect failure**: Pick a host with wrong credentials → modal "Connecting…" → error card with readable message → Retry button re-attempts.
9. **Path bar typing**: Type a valid path, Enter → navigate. Type a non-existent path, Enter → path field flashes red, reverts.
10. **Empty directory**: Navigate to an empty directory → "This folder is empty" overlay.
11. **SFTP tool window regression**: Connect, browse, disconnect — identical to before the refactor.
12. **Keyboard**: `Cmd+Up` navigates up. `Enter` on a directory navigates in. `Enter` on a file (Save mode) populates filename; does not save. `Escape` cancels.

## Known Limitations

1. **No directory-creation in the dialog.** Follow-up.
2. **No type filter / save-as-type dropdown.** Follow-up if needed.
3. **No hidden-file toggle.** Dotfiles always shown. Follow-up.
4. **No multi-select in Open mode.** Follow-up when a caller needs it.
5. **No drag-and-drop between sources inside the dialog.** Out of scope; the SFTP tool window already handles transfers between panes.
6. **No column-sort persistence across dialog sessions.** Sort resets each open.
7. **No bookmarks / recent locations sidebar.** The `last-used source` preference handles the most common case.
8. **`FileSource` is synchronous.** Sources that must be async (OAuth-refreshing cloud storage, etc.) block their caller thread; the dialog's modal-progress wrapping makes this acceptable but not async-native.
9. **Initial `open` call is blocking.** The dialog shows a modal progress for the duration of the first source connect. For a slow network this is a few seconds of "Connecting…". No partial-rendering optimization.
10. **`FileSourceProvider.listSources()` is called once per dialog open** — hosts added between opens are reflected next time; hosts added mid-dialog are not. Acceptable because the dialog is modal.

## Risks

- **`FileBrowserTable` refactor touches two working classes.** `RemoteFilePane` and `LocalFilePane` are well-tested by hand via the SFTP tool window. The refactor is mechanical (move `JBTable` construction into the new widget), but any subtle behavior change (row selection events, column widths, sort state) would be visible. Mitigation: E2E checklist step 11.
- **Modal-within-modal (dialog + Task.Modal for connect).** New UX pattern in TermLab. Stacking should work — `DialogWrapper` is a real modal, `Task.Modal` creates its own sub-progress dialog. Watch for focus/escape anomalies during testing.
- **`core_test_runner` is new infrastructure.** Adding it requires Bazel deps similar to `vault_test_runner`/`sftp_test_runner`. Low risk; same pattern.
- **Extracting `AtomicSftpWrite` touches `SftpVirtualFile`.** `SftpVirtualFile.writeAtomically` becomes a delegate. Semantics must stay identical. Existing behavior is already well-exercised by manual E2E and by the fix from VFS-T7 review; extracting it should not introduce regressions, but verify with a round-trip test of an SFTP edit.
- **`FileSource.id()` stability.** The spec requires stable IDs for the "last used source" preference. If a host is renamed, its UUID stays the same (→ `"sftp:" + uuid`), so the id is stable across renames. If a host is deleted and re-added, the new UUID won't match the old one, so the preference falls back to the first available source. Acceptable.

## Follow-ups (Out of Scope)

1. **`File → Open Remote File…` action** using `UnifiedFilePickerDialog.showOpenDialog(...)`.
2. **Migrate remaining existing file-picker call sites** in TermLab to the unified picker.
3. **New Folder button** inside the dialog.
4. **Type filter dropdown**, **hidden-file toggle**, **multi-select** — incremental features as needed.
5. **UI unit tests** for the dialog via `HeavyPlatformTestCase` or similar IntelliJ test fixture.
6. **Remote path autocomplete** in the path field (currently plain text).
7. **Recent-locations sidebar / bookmark list** beyond the single `last-used source` preference.
