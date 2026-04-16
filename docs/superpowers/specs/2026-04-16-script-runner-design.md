# TermLab Script Runner — Design Spec

**Date:** 2026-04-16
**Plugin ID:** `com.termlab.runner`
**Plugin Name:** TermLab Script Runner

## Overview

A new standalone plugin that adds lightweight script execution to the TermLab light editor. Users can run scripts on the local machine or on remote hosts via SSH, with live-streamed output in a dedicated tool window. No full IDE run/debug infrastructure — just execute and observe.

## Plugin Structure & Dependencies

```
plugins/runner/
├── resources/META-INF/plugin.xml
├── src/com/termlab/runner/
│   ├── config/        # Configuration model, persistence, dialog
│   ├── execution/     # Local + remote execution engines
│   ├── output/        # Tool window, output tabs, streaming
│   └── actions/       # Toolbar actions (Run, Edit Config, dropdown)
└── BUILD.bazel
```

**Dependencies:**
- `com.intellij.modules.platform`
- `com.termlab.core`
- `com.termlab.ssh` — for `TermLabSshClient`, `HostStore`, `SshHost`, credential resolution
- `com.termlab.editor` — for `ScratchMarker` (unsaved scratch detection) and `SaveAsHelper` (save-as dialog)

Registered as an essential plugin in `TermLabApplicationInfo.xml`.

## Configuration Model

### RunConfig

A single named execution configuration:

| Field            | Type                  | Description                                                        |
|------------------|-----------------------|--------------------------------------------------------------------|
| `id`             | `UUID`                | Auto-generated unique identifier                                   |
| `name`           | `String`              | User-facing label (e.g., "Python on prod-server")                  |
| `hostId`         | `UUID` (nullable)     | `null` = local; otherwise UUID referencing a host in `HostStore`   |
| `interpreter`    | `String`              | Command to invoke (e.g., `python3`, `/usr/local/bin/node`)         |
| `args`           | `List<String>`        | Extra arguments passed to the interpreter before the script path   |
| `workingDirectory` | `String` (nullable) | Working directory; `null` uses the script's parent directory       |
| `envVars`        | `Map<String, String>` | Environment variable overrides (key → value)                       |
| `scriptArgs`     | `List<String>`        | Arguments passed after the script path                             |

### RunConfigStore

Application service persisting to `~/.config/termlab/run-configs.json`.

- Versioned JSON envelope (same pattern as `HostsFile`):
  ```json
  {
    "version": 1,
    "configs": [ { "id": "...", "name": "...", ... } ]
  }
  ```
- Atomic write (temp file → rename)
- GSON serialization
- CRUD operations: `add`, `update`, `remove`, `getAll`, `getById`

### FileConfigBinding

Tracks which configuration a file last used.

- Persisted to `~/.config/termlab/run-bindings.json`
- Map of file path (string) → config UUID
- When a file is opened, the last-used config is pre-selected in the dropdown

### InterpreterRegistry

Built-in mapping of file extensions to default interpreter commands:

| Extension | Interpreter |
|-----------|-------------|
| `.py`     | `python3`   |
| `.sh`     | `bash`      |
| `.js`     | `node`      |
| `.rb`     | `ruby`      |
| `.pl`     | `perl`      |
| `.go`     | `go run`    |
| `.java`   | `java`      |
| `.lua`    | `lua`       |
| `.php`    | `php`       |

Used for quick-run (no config) and as defaults when creating a new config. If no mapping exists for an extension, the user is prompted to specify an interpreter.

## Toolbar Actions

Two actions registered by the runner plugin, placed in `EditorTabActionGroup` (the small action strip in the top-right corner of each editor tab):

### Run Action (`TermLab.Runner.Run`)

- **Icon:** `AllIcons.Actions.Execute` (green play triangle)
- **Shortcut:** `Cmd+R` / `Ctrl+R`
- **Visibility:** Enabled when the active editor has a runnable file (recognized extension or existing config)
- **Behavior:**
  1. If file is an unsaved scratch (via `ScratchMarker`), trigger save-as first; abort if cancelled
  2. If file has unsaved modifications, auto-save via `FileDocumentManager.saveDocument()`
  3. If file has a bound config, use it
  4. If no config, quick-run with defaults; after execution, offer "Save as configuration?" via notification balloon

### Edit Configuration Action (`TermLab.Runner.EditConfig`)

- **Icon:** `AllIcons.General.Settings`
- **Shortcut:** `Cmd+Shift+R` / `Ctrl+Shift+R`
- **Visibility:** Enabled when an editor tab is active
- **Behavior:** Opens the configuration dialog, pre-populated with the current file's bound config or defaults for a new one

### Config Dropdown

A dropdown widget next to the Run button showing the currently selected config name (or "Local default" for quick-run). Clicking it shows:
- All named configs
- Separator
- "Edit Configurations..." entry (opens the configuration dialog)

## Execution Engine

### ScriptExecution Interface

Represents a running script instance:

- `getOutputStream()` — live output stream (stdout + stderr interleaved)
- `sendInterrupt()` — sends SIGINT
- `kill()` — forceful termination
- `getExitCode()` — nullable `Integer`, available when finished
- `addTerminationListener(Runnable)` — callback when process ends
- `isRunning()` — current state check

### LocalExecutor

Runs scripts on the local machine:

- Uses `ProcessBuilder` to launch the interpreter with the script path
- Sets working directory from config (or script's parent directory as default)
- Applies environment variable overrides from config
- Merges stdout + stderr into a single stream via `ProcessBuilder.redirectErrorStream(true)`
- **Stop behavior:** First click sends SIGINT via `Process.destroy()`. Second click escalates to `Process.destroyForcibly()`.

### RemoteExecutor

Runs scripts on a remote host via SSH:

- Uses `TermLabSshClient.connectSession()` to obtain a raw `ClientSession` (no shell channel)
- Opens an exec channel via `session.createExecChannel(command)`
- Command assembled as: `cd <workingDir> && ENV_VAR=val ... <interpreter> <args> <scriptPath> <scriptArgs>`
- Streams stdout/stderr back over the exec channel
- Credentials resolved via existing vault + `SshCredentialResolver` flow (vault unlock prompt if locked)
- **Stop behavior:** First click sends `channel.sendSignal("INT")`. Second click closes the channel.

### Interpreter Resolution

When creating a quick-run (no existing config):

1. Look up file extension in `InterpreterRegistry`
2. If found, use the default interpreter command
3. If not found, prompt the user to specify an interpreter before running

Configs can override the default with any arbitrary command string.

## Output Tool Window

### Registration

- **Tool window ID:** `Script Output`
- **Anchor:** Bottom
- **Visibility:** Hidden by default; auto-shown when a script starts running

### Tab Structure

Each tab represents one `ScriptExecution`:

**Tab title:** `filename @ hostname` (or `filename @ local`)

**Header bar** — single row:
- Interpreter command (e.g., `python3.11`)
- Host label (from host store label, or "Local")
- Start timestamp
- Status indicator: `Running...` / `Finished (exit 0)` / `Failed (exit 1)`

**Output area:**
- Scrolling, read-only text pane
- Monospace font matching terminal settings
- Streams output line-by-line as it arrives

**Per-tab toolbar:**
- Stop button (enabled while running)
- Clear button
- Re-run button (enabled when finished — re-executes same file with same config)

### Tab Lifecycle

- New tab created on each run (including re-runs, so outputs can be compared)
- Tabs persist until manually closed (X button on tab)
- Configurable max tab count (default: 10). When exceeded, oldest completed tabs are auto-closed. Running tabs are never auto-closed.

### Output Streaming

- Output appended on EDT via `invokeLater`, batched in ~50ms intervals to avoid UI thrashing on fast output
- Auto-scroll to bottom while streaming, unless the user has manually scrolled up (pause auto-scroll; resume when user scrolls back to bottom)

## Save-Before-Run Flow

**Rule:** A file must be saved to disk before it can be executed.

### Flow when Run is triggered:

1. **Unsaved scratch** (file is `LightVirtualFile` with `ScratchMarker`): Trigger `SaveAsHelper` to show save-as dialog. If user cancels → abort. If user saves → continue with saved file path.

2. **Dirty file** (saved file with unsaved modifications): Auto-save silently via `FileDocumentManager.saveDocument()`. No dialog.

3. **Determine executor from file location:**
   - File on local filesystem → `LocalExecutor`
   - File is `SftpVirtualFile` (scheme `sftp://`) → `RemoteExecutor`, targeting the host embedded in the SFTP URL. Script already lives on the remote machine; no upload.

4. **Resolve config:** Look up file's bound config in `FileConfigBinding`. If none, build a transient quick-run config from `InterpreterRegistry` + file location. After execution completes, offer "Save as configuration?" via notification balloon with action link.

### Cross-Host Mismatch

If a file lives on host B (via SFTP VFS) but the bound config targets host A, show a confirmation dialog:

> "This file is on **host B** but the configuration runs on **host A**. Run anyway?"

This allows advanced use cases (shared NFS mounts) while catching mistakes.

## Configuration Dialog

Modal dialog with list + detail layout.

### Left Panel

- Scrollable list of saved configurations (from `RunConfigStore`)
- Add / Remove / Duplicate buttons at the bottom

### Right Panel — Config Fields

| Field               | Widget                          | Notes                                                              |
|---------------------|---------------------------------|--------------------------------------------------------------------|
| Name                | Text field                      | User-facing label                                                  |
| Host                | Dropdown                        | "Local" + all hosts from `HostStore.getHosts()`. Reactive to changes. |
| Interpreter         | Text field                      | Pre-filled from `InterpreterRegistry` based on current file extension |
| Script arguments    | Text field                      | Args passed after the script path                                  |
| Working directory   | Text field + browse button      | Local: filesystem browser. Remote: pre-fills with script's parent dir |
| Environment variables | Key/value table + add/remove  | Overrides only, not the full environment                           |

### Host Dropdown Reactivity

- Registers `HostStore.addChangeListener()` on dialog open
- Removes listener on dialog close
- When listener fires, dropdown refreshes from `HostStore.getHosts()`
- If a config references a deleted host UUID, dropdown shows "Unknown host (deleted)" in red

### Buttons

- **Apply** — save changes to current config
- **OK** — apply + close
- **Cancel** — discard changes + close

### Entry Points

1. "Edit Configuration" toolbar button → opens dialog, selects current file's bound config or creates a new pre-populated one
2. Config dropdown → "Edit Configurations..." → opens same dialog
3. "Save as configuration?" notification after quick-run → opens dialog with transient config pre-populated

## Host Store Integration

### HostStore (no changes needed)

`HostStore` already provides:
- `getHosts()` — snapshot of all hosts
- `findById(UUID)` — look up a specific host
- `addChangeListener(Runnable)` / `removeChangeListener(Runnable)` — reactive notifications

The runner plugin is a consumer only. No modifications to the SSH plugin required.

### Credential Resolution

Remote execution uses the same credential flow as existing SSH terminal sessions:
- `SshCredentialResolver.resolve()` with the host's auth config
- If vault is locked, user gets the existing unlock prompt
- Runner plugin delegates entirely to SSH + vault infrastructure

## Share Bundle Integration (Later Phase)

### ShareBundle Changes

- Add `List<RunConfig> runConfigs` field to the `ShareBundle` record
- Bump `CURRENT_SCHEMA_VERSION` to 2
- Backward compatible: v1 bundles deserialize with empty `runConfigs` list

### Export

- Export dialog gains a "Run Configurations" checkbox
- When checked, all named `RunConfig` entries are included
- Export planner warns if a config references a host not included in the export

### Import

- Import dialog shows run configs as selectable items
- Conflict detection by name: existing conflict resolution dialog (keep/overwrite/rename)
- Host UUID remapping: if imported hosts get new UUIDs during conflict resolution, run config `hostId` references are updated to match

This is deferred until the core runner plugin is functional.
