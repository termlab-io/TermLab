# TermLab Remote Desktop — Design Spec

**Date:** 2026-04-16
**Plugin ID:** `com.termlab.rdp`
**Plugin Name:** TermLab Remote Desktop

## Overview

A new standalone plugin that adds RDP (Windows Remote Desktop) sessions to TermLab. Users double-click a host in a dedicated "Remote Desktops" tool window, and a Windows desktop renders inside a TermLab editor tab with clipboard sharing, shared-drive file transfer, and drag-and-drop upload — feeling like a native part of TermLab, not an external launch.

Rendering uses Apache Guacamole's HTML5 client running inside a JCEF browser, talking through an in-process WebSocket proxy to a bundled `guacd` sidecar. The plugin owns host profiles, credential resolution (via the vault), the `guacd` supervisor, the WebSocket proxy, and the session tab UI.

## Plugin Structure & Dependencies

```
plugins/rdp/
├── resources/
│   ├── META-INF/plugin.xml
│   └── web/                       # JCEF assets
│       ├── index.html
│       ├── client.js              # guacamole-common-js glue
│       ├── style.css
│       └── guacamole-common-js/   # vendored, pinned
├── src/com/termlab/rdp/
│   ├── model/                     # RdpHost, RdpAuth, RdpSecurity
│   ├── persistence/               # RdpHostStore, RdpCertTrustStore, RdpGson
│   ├── guacd/                     # GuacdSupervisor, GuacdProxy, ConnectionParams
│   ├── session/                   # RdpSessionTab, RdpSessionEditor(Provider),
│   │                              # RdpSessionVirtualFile
│   ├── toolwindow/                # RdpHostsToolWindow, factory, cell renderer
│   ├── ui/                        # RdpHostEditDialog, credential picker glue
│   ├── actions/                   # New session, connect, add host, send C-A-D,
│   │                              # reconnect/disconnect, open shared folder
│   └── palette/                   # RdpHostsSearchEverywhereContributor
├── test/                          # Unit + opt-in integration tests
└── BUILD.bazel
```

**Dependencies:**
- `com.intellij.modules.platform`
- `com.termlab.core`
- `com.termlab.vault` — for `CredentialProvider`

No dependency on `com.termlab.ssh`; no `sdk/` changes. Registered as an essential plugin in `TermLabApplicationInfo.xml`.

## High-Level Architecture

```
┌─────────────────────── TermLab (JVM) ───────────────────────┐
│                                                             │
│  plugins/rdp/                                               │
│  ┌──────────────────┐  ┌────────────────────────────────┐   │
│  │ RdpHostStore     │  │ "Remote Desktops" tool window  │   │
│  │  (app service)   │  │  (own, right anchor)           │   │
│  └──────────────────┘  └────────────────────────────────┘   │
│                                                             │
│  ┌──────────────────┐  ┌────────────────────────────────┐   │
│  │ GuacdSupervisor  │  │ RdpSessionTab (editor tab)     │   │
│  │  (app service)   │  │  ┌──────────────────────────┐  │   │
│  └────────┬─────────┘  │  │ JCEF browser             │  │   │
│           │            │  │  bundled index.html +    │  │   │
│           │            │  │  guacamole-common-js     │  │   │
│           │            │  │  WebSocket → GuacdProxy  │  │   │
│           │            │  └──────────────────────────┘  │   │
│           │            └──────────┬─────────────────────┘   │
│           │                       │ guac protocol (WS)      │
│           ▼                       ▼                         │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ GuacdProxy (Java NIO, bound 127.0.0.1:random)        │   │
│  │   bridges browser WS ↔ guacd TCP                     │   │
│  │   injects RDP params + credentials per connection    │   │
│  └──────────────────────────────────────────────────────┘   │
│           │                                                 │
└───────────┼─────────────────────────────────────────────────┘
            │ guacd protocol (127.0.0.1:4822-class, loopback only)
            ▼
  ┌──────────────────────────────────────────┐
  │ guacd (bundled C daemon, sidecar)        │
  │   speaks RDP 10 to the Windows host      │
  │   virtual channels: clipboard, drive,    │
  │   display, input                         │
  └────────────────┬─────────────────────────┘
                   │ RDP (TCP 3389, TLS/NLA)
                   ▼
          [ Windows host ]
```

### Why Guacamole + JCEF rather than native FreeRDP embedding

- **One cross-platform rendering path.** TermLab ships on macOS, Linux, and Windows. Embedding a native FreeRDP window into the Swing toolkit is a different problem on each OS (NSView / XEmbed / HWND) and historically fragile under dock/resize/re-parenting. Guacamole renders into an HTML5 canvas inside a JCEF panel — one code path, already available in the IntelliJ Platform.
- **Clipboard, file-transfer, and keymap channels are already solved in the Guacamole protocol.** We wire them to TermLab's existing vault and the JCEF message bridge rather than re-implementing them.
- **Tabs, resize, split, and pop-out come for free** because the session is just a Swing panel hosting a JCEF browser.
- **One bundled native dependency (`guacd`)** vs. bundling FreeRDP plus writing three platform-specific window embedders.

### Honest drag-and-drop ceiling

RDP captures mouse events inside the remote session, so literal mouse-drag from the remote desktop out into the local TermLab window is not supported by any RDP client and is out of scope. What the plugin delivers instead:

- **Local → remote:** drop a file onto the session tab → streamed over Guacamole `file` instruction → appears in the shared drive visible as `T:\` inside Windows.
- **Remote → local:** user copies or drags a file into `T:\` inside Windows → the file is immediately present in the local shared folder.
- **Clipboard:** text round-trips via the Guacamole `clipboard` channel; file clipboard falls out of the same shared-drive plumbing.

## Data Model & Persistence

### `RdpHost` (record)

| Field              | Type                | Notes                                                           |
|--------------------|---------------------|-----------------------------------------------------------------|
| `id`               | `UUID`              | Auto-generated                                                  |
| `label`            | `String`            | User-facing name                                                |
| `host`             | `String`            | Hostname or IP                                                  |
| `port`             | `int`               | Default `3389`                                                  |
| `username`         | `String`            |                                                                 |
| `domain`           | `String` (nullable) | Windows domain or AAD tenant; blank for workgroup hosts         |
| `auth`             | `RdpAuth` (sealed)  | `VaultAuth(credentialId)` \| `PromptPasswordAuth`               |
| `security`         | `RdpSecurity`       | `NLA` (default) \| `TLS` \| `RDP` (legacy) \| `ANY`             |
| `colorDepth`       | `int`               | 16/24/32. Default 32                                            |
| `initialWidth`     | `int` (nullable)    | `null` = fit tab                                                |
| `initialHeight`    | `int` (nullable)    | `null` = fit tab                                                |
| `dpiScale`         | `int`               | 100/125/150/175/200. Default 100                                |
| `multiMonitor`     | `boolean`           | Span all local monitors (single tab becomes a composite canvas) |
| `enableClipboard`  | `boolean`           | Default `true`                                                  |
| `enableDriveShare` | `boolean`           | Default `true`                                                  |
| `sharedFolderPath` | `String` (nullable) | Local dir surfaced as a Windows drive; `null` = auto-create per host under `~/TermLab/RDP Shared/<label>/` |
| `sendCmdAsCtrl`    | `boolean`           | macOS only; default `false`                                     |
| `ignoreCertErrors` | `boolean`           | First-connect trust tracked separately; this is an explicit override |

Sealed `RdpAuth` mirrors `SshAuth`: `VaultAuth(credentialId)` and `PromptPasswordAuth` for MVP. Key-based and smart-card auth are deferred to phase 2.

`RdpSecurity` is an enum (`NLA` default, `TLS`, `RDP`, `ANY`).

### `RdpHostStore` (application service)

- Persists to `~/.config/termlab/rdp-hosts.json`
- Versioned JSON envelope matching the `HostsFile` pattern:
  ```json
  { "version": 1, "hosts": [ { "id": "...", "label": "...", ... } ] }
  ```
- Atomic write (temp file → rename)
- GSON serialization via a dedicated `RdpGson` (sealed `RdpAuth` registered)
- CRUD: `addHost`, `updateHost`, `removeHost`, `getHosts`, `findById`
- `addChangeListener(Runnable)` / `removeChangeListener(Runnable)` for tool-window reactivity
- `reload()` entry point for the "refresh from disk" toolbar button

### `RdpCertTrustStore` (application service)

RDP servers present TLS certs for NLA. Mirrors SSH's `KnownHostsFile`:

- Persists to `~/.config/termlab/rdp-known-certs.json`
- Keyed by `host:port`
- Each entry stores: SHA-256 fingerprint, cert subject, last-seen timestamp
- First connect → trust-on-first-use modal
- Fingerprint mismatch → blocking modal with old + new fingerprint; `Trust new` is never the default button

### Credentials

Reuses the vault via the existing `sdk/CredentialProvider` SPI. No new credential types. `RdpHost.auth = VaultAuth(credentialId)` points at a vault entry whose secret field is the password. Domain is stored on the host record (not in the vault), matching how `SshHost.username` is stored today.

## `guacd` Supervisor & Proxy

### `GuacdSupervisor` (application service)

Owns the bundled `guacd` process for the lifetime of the TermLab app.

**Binary layout.** The installer ships per-platform `guacd` under:
```
<TermLab install>/bin/guacd/<platform>/guacd[.exe]
```
- `macos-universal/` (Apple Silicon + Intel fat binary, codesigned and notarized)
- `linux-x64/`, `linux-arm64/`
- `windows-x64/` (Authenticode signed)

Development override: if the `TERMLAB_GUACD_BIN` env var is set, the supervisor uses that path instead of the bundled binary.

**Lifecycle.**
- **Lazy start** — `getOrStart()` is the only entry point; the daemon is not launched until the first RDP connect.
- **Port allocation** — binds `127.0.0.1` on an OS-assigned ephemeral port. Never listens on a public interface.
- **Supervision** — watches stderr; lines logged to `idea.log` with `[guacd]` prefix. On unexpected exit, `isAlive()` flips to `false` and the next connect attempt restarts the daemon.
- **Health check** — on start, open a TCP socket and exchange a minimal handshake (`select` instruction + expected response). 3s timeout; failure surfaces as a user-visible error with log location.
- **Shutdown** — registered with `Disposer`; on app close send SIGTERM, wait up to 2s, then SIGKILL.
- **Crash mid-session** — active `RdpSessionTab`s receive a `SessionTerminated("guacd process exited")` event and show a reconnect banner.

### `GuacdProxy` (application service)

An in-process WebSocket server bridging the JCEF browser to `guacd` over TCP.

**Why it exists.** JCEF browsers cannot open raw TCP sockets, and `guacamole-common-js` speaks the Guacamole protocol over WebSocket. Running the WebSocket side in-JVM keeps everything on loopback and lets us inject credentials without exposing them to JS.

**Behavior.**
- Starts on the first RDP connect; binds `127.0.0.1` on an ephemeral port.
- Per-connection flow:
  1. Browser opens `ws://127.0.0.1:<proxyPort>/tunnel?token=<one-time-token>`.
  2. Proxy validates the token (single-use, 30s TTL, generated when the tab is created).
  3. Proxy dials `guacd` on its supervisor port.
  4. Proxy reads the pre-registered `ConnectionParams` for that token and sends the `select rdp` → parameter negotiation → `connect` handshake.
  5. Once handshaked, bytes are relayed bidirectionally until either side closes.
- **Credentials.** Passwords are pulled from the vault once at connect time and written into the `connect` instruction sent to `guacd`. Never touch JS, URL query strings, page HTML, localStorage, or disk.
- **Tokens.** 128-bit random, single-use, 30s TTL. Represent "this one RDP session is authorized" so the browser never sees credentials.

### `ConnectionParams`

Struct the tab builds and hands to the proxy before loading the browser:

| Field                          | Source                                              |
|--------------------------------|-----------------------------------------------------|
| `hostname`, `port`             | `RdpHost`                                           |
| `username`, `domain`           | `RdpHost`                                           |
| `password`                     | Vault (resolved at tab-open time, in-memory only)   |
| `security`, `ignoreCert`       | `RdpHost`                                           |
| `width`, `height`, `dpi`       | `RdpHost` + live tab size                           |
| `enableClipboard`, `enableDrive`, `drivePath` | `RdpHost`                            |
| `serverCertFingerprint`        | `RdpCertTrustStore` (for pinned verification)       |

## The RDP Session Tab

### Tab registration

RDP sessions open as **editor tabs** via `FileEditorProvider`, matching how SSH terminal sessions and SFTP panels open today.

- `RdpSessionVirtualFile` — lightweight virtual file carrying `(hostId, sessionInstanceId)`; filename `<host label>.rdp-session`.
- `RdpSessionEditorProvider` — claims `RdpSessionVirtualFile`, returns an `RdpSessionEditor` wrapping `RdpSessionTab`.
- Tab title: `<host label>`, with a remote-desktop icon; tooltip shows `user@host:port`.
- Multiple sessions to the same host allowed — each gets a distinct `sessionInstanceId` and appears in its own tab.

### Layout

```
┌────────────────────────────────────────────────────────────┐
│ [status dot] <label>  user@host:port  •  1920×1080 @ 100% │ ← thin header strip
│                                        [⟳] [⎘] [⏏]        │   (reconnect / fullscreen / disconnect)
├────────────────────────────────────────────────────────────┤
│                                                            │
│                                                            │
│                JCEF browser: Guacamole canvas              │
│                                                            │
│                                                            │
│                                                            │
├────────────────────────────────────────────────────────────┤
│ clipboard: synced  •  shared drive: ~/TermLab/RDP Shared/ │ ← thin footer strip
└────────────────────────────────────────────────────────────┘
```

**Header strip (~24 px):**
- Status dot: grey (connecting) / green (connected) / amber (reconnecting) / red (disconnected)
- Host identity text
- Current resolution and DPI scale
- `⟳` Reconnect, `⎘` Fullscreen (detaches to a frameless window), `⏏` Disconnect

**Footer strip (~20 px):**
- Clipboard sync status
- Shared drive path (click reveals in the OS file manager)
- Drop-target hint while a drag is in progress: "Drop to copy to remote"

### JCEF page

Bundled under `plugins/rdp/resources/web/`:
```
web/
├── index.html
├── client.js              # thin glue around guacamole-common-js
├── guacamole-common-js/   # vendored, pinned
└── style.css
```

- `index.html` receives the one-time connection token via a JS bridge call from the JVM side (`JBCefJSQuery`). Never via URL query string.
- `client.js` opens `ws://127.0.0.1:<proxyPort>/tunnel?token=<token>`, instantiates `Guacamole.Client` + `Guacamole.Mouse` + `Guacamole.Keyboard`, and mounts the canvas.
- Resize listener: on JCEF panel resize, the client sends a `size` instruction to `guacd` so the Windows host re-negotiates the display. No client-side scaling — crisp text.
- HTML5 file-drop listener on the canvas → Guacamole `file` stream → `guacd` → shared drive.

### Clipboard

Guacamole's `clipboard` instruction is bidirectional. Bridging to the OS clipboard:

- **Remote → local:** `guacd` sends a `clipboard` instruction; `client.js` receives it, forwards to the JVM via `JBCefJSQuery`; JVM writes to `CopyPasteManager` (text, and for the file-list case, a synthetic `Transferable` pointing into the shared drive).
- **Local → remote:** a `ClipboardOwner` registered while the tab has focus listens for OS clipboard changes, serializes text (and file references as a Guacamole stream of file bytes), forwards to `client.js` which writes the `clipboard` instruction.

MVP guarantees **text clipboard** both directions. File clipboard is a nice-to-have that falls out of the shared-drive pipeline.

### Input

- Mouse events captured by `Guacamole.Mouse`; relative-mouse mode off (RDP is absolute).
- Keyboard captured by `Guacamole.Keyboard`, which handles most OS-specific remapping internally.
- macOS modifier remapping: `Cmd` → Windows key by default; `sendCmdAsCtrl` on the host record toggles `Cmd+C/V` → `Ctrl+C/V` on the remote side for users who prefer that mapping.
- `Esc` in fullscreen exits fullscreen (matches every RDP client).
- "Send special keys" header menu: `Ctrl+Alt+Del`, `PrtSc`, `Win+L` — since the host OS eats some of these.

### Shared drive (for drag-drop and copy-out)

- Default path: `~/TermLab/RDP Shared/<host label>/`, auto-created on first connect.
- Configurable per host via `RdpHost.sharedFolderPath`.
- `guacd` exposes the folder to the Windows session as a drive (default drive letter `T:`).
- On connect, a read-only `README.txt` is written into the folder explaining the two-way flow: drop on mac/linux to appear as `T:\`; copy into `T:\` on Windows to pull back locally.
- Files written from the Windows side appear locally as soon as RDP drive-redirect flushes them — no extra pipeline needed.

### Drag-and-drop

- **Local → remote (drag).** HTML5 drop on the canvas → file bytes streamed via Guacamole `file` instruction → `guacd` writes them into the shared drive → user sees them on Windows as `T:\…`. Toast confirms completion.
- **Remote → local.** User drags or copies a file into `T:\` on Windows; it appears in the local shared folder instantly (standard RDP drive redirect). Footer hint reminds the user of this pattern.
- **Cross-window literal mouse drag (remote window → local window).** Not supported by RDP. Documented; the shared-drive workflow is the equivalent.

## Actions, Palette, Tool Window

### Tool window

- **ID:** `Remote Desktops`
- **Anchor:** right (sibling of `Hosts` and `Tunnels`)
- **Icon:** `AllIcons.Nodes.Desktop` (or a custom monitor glyph if added later)
- **Factory:** `com.termlab.rdp.toolwindow.RdpHostsToolWindowFactory`
- **Content:** mirrors `HostsToolWindow` — `JBList<RdpHost>` + toolbar (Add / Edit / Delete / Refresh), double-click to connect, right-click for Connect / Edit / Duplicate / Delete.
- **Cell renderer:** label on the top line; `user@host:port` dim on the second line; monitor icon on the left.

### `RdpHostEditDialog`

Modal dialog, mirrors `HostEditDialog`. Two-tab layout to keep primary fields obvious:

**Tab 1 — Connection**
- Label (text field)
- Host, Port (3389 default)
- Username, Domain
- Authentication: `Vault` (credential picker wired to the vault) | `Prompt each connect`

**Tab 2 — Display & Experience**
- Security: `NLA` / `TLS` / `RDP` / `Any` (dropdown, default `NLA`)
- Color depth: 16 / 24 / 32 (dropdown, default 32)
- Initial size: `Fit to tab` (default) | explicit `W × H`
- DPI scale: 100 / 125 / 150 / 175 / 200
- `☐ Span all monitors`
- `☑ Enable clipboard sharing`
- `☑ Enable shared drive`
  - Shared folder: `<path>` + Browse (default auto-path shown as hint)
- `☐ Send Cmd as Ctrl` (macOS only)
- `☐ Ignore certificate errors` (with a warning label)

OK / Cancel. No "Test Connection" in MVP — validation happens on real connect.

### Actions

Registered in `plugins/rdp/resources/META-INF/plugin.xml`:

| Action ID                       | Title                         | Shortcut (mac / win-linux)     | Behavior                                                                  |
|---------------------------------|-------------------------------|--------------------------------|---------------------------------------------------------------------------|
| `TermLab.Rdp.NewSession`        | New Remote Desktop Session…   | `Cmd+Shift+L` / `Ctrl+Shift+L` | Opens a host picker (SSH-style), then connects in a new tab               |
| `TermLab.Rdp.ConnectToHost`     | Connect to Remote Desktop     | *(no shortcut)*                | Programmatic entry point; invoked by tool window, palette, context menu   |
| `TermLab.Rdp.AddHost`           | Add Remote Desktop Host…      | *(no shortcut)*                | Opens `RdpHostEditDialog` in add mode                                     |
| `TermLab.Rdp.OpenSharedFolder`  | Open RDP Shared Folder        | *(no shortcut)*                | Reveals the active session's shared folder in the OS file manager         |
| `TermLab.Rdp.Reconnect`         | Reconnect RDP Session         | *(no shortcut)*                | Context action on an `RdpSessionEditor`                                   |
| `TermLab.Rdp.Disconnect`        | Disconnect RDP Session        | *(no shortcut)*                | Context action on an `RdpSessionEditor`                                   |
| `TermLab.Rdp.SendCtrlAltDel`    | Send Ctrl+Alt+Del             | *(no shortcut)*                | Active only when an RDP tab is focused                                    |

Shortcut rationale: `Cmd+K` and `Cmd+Shift+K` are taken by SSH / SFTP; `Cmd+Shift+L` is free and mnemonic (Login / Logon).

### Command Palette

Reuses the `searchEverywhereContributor` pattern from SSH.

- `RdpHostsSearchEverywhereContributor` — lists all `RdpHost` entries; type-ahead matches label / host / username; selection connects in a new tab.
- Palette tab title: "Remote Desktops".
- `New Remote Desktop Session…` is also discoverable via palette action search.

### Context menus

- Right-click on an RDP tab → Reconnect, Disconnect, Open Shared Folder, Send Ctrl+Alt+Del, Edit Host…
- Right-click on an `RdpHost` in the tool window → Connect, Edit…, Duplicate, Delete, Open Shared Folder.

### Notifications

- First connect → "Connected to &lt;label&gt;. Drive `T:` maps to `~/TermLab/RDP Shared/<label>/`."
- Unexpected disconnect → "Lost connection to &lt;label&gt;. [Reconnect]"
- `guacd` crash mid-session → "RDP helper crashed. Active sessions disconnected. [Reconnect all]"

## Testing

**Unit tests** (run under the normal `make termlab-build` flow):
- `RdpHostStore` — JSON round-trip, versioned envelope, atomic write under concurrent callers, `reload()` after external edit, change-listener fan-out.
- `RdpCertTrustStore` — first-connect TOFU, fingerprint match on reconnect, mismatch → blocked, export/import fidelity.
- `GuacdSupervisor` — lifecycle state machine (stopped → starting → alive → dead → restart). Uses a `FakeGuacdProcess` that echoes a valid handshake. Covers crash-mid-session and startup-timeout paths.
- `GuacdProxy` — token lifecycle (single-use, 30s TTL), unknown-token rejection, handshake injection (credentials appear only in `connect` instruction, never in URL), bidirectional byte relay, close propagation.
- `ConnectionParams` → Guacamole `connect` instruction encoding (escaping, nullable field handling, cert-pin flag behavior).
- `RdpHost` Gson serialization with each `RdpAuth` variant.

**Integration tests** (opt-in, gated by `TERMLAB_RDP_IT=1`; require a running Windows target documented in `plugins/rdp/test/README.md`):
- Full connect → render frame → disconnect against a Windows Server test VM.
- Clipboard round-trip (text).
- File drop → file appears on shared drive → file visible in remote session.
- Cert fingerprint pin survives reconnect.

**Manual QA checklist** (tracked in the implementation plan, not automated):
- macOS (Apple Silicon + Intel), Linux (x64), Windows (x64) — all three connecting to the same Windows target.
- HiDPI screens on each platform.
- Reconnect after network drop.
- `guacd` kill-9 mid-session → reconnect banner works.
- Vault locked → unlock prompt → connect proceeds.
- Shortcut conflict sweep for `Cmd+Shift+L` / `Ctrl+Shift+L`.

## Error Handling — User-Facing Behavior

| Failure                             | User sees                                                                 |
|-------------------------------------|---------------------------------------------------------------------------|
| `guacd` binary missing from install | Modal at first-connect: "RDP helper missing. Reinstall TermLab."          |
| `guacd` fails to start              | Modal with log excerpt + link to full log                                  |
| TCP connect to Windows host fails   | Tab header flips to red; banner: "Could not reach &lt;host&gt;:&lt;port&gt;. [Retry]" |
| NLA rejected                        | Banner: "Authentication failed. [Edit Host] [Retry]"                       |
| Cert first-seen                     | Modal: subject, SHA-256, [Trust and connect] / [Abort]                     |
| Cert mismatch                       | Modal with old + new fingerprints, [Trust new] (explicit, never default) / [Abort] |
| Vault locked                        | Standard vault unlock prompt; on cancel, connect aborts                    |
| `guacd` crash mid-session           | Tab banner: "RDP helper crashed. [Reconnect]"                              |
| Network drop mid-session            | Tab header flips to amber; auto-reconnect attempt once after 2s, then banner with [Reconnect] |

All failures log to `idea.log` with a `[rdp]` prefix and the specific subsystem (`[rdp/guacd]`, `[rdp/proxy]`, `[rdp/session]`).

## Security Posture

- `guacd` and `GuacdProxy` bind `127.0.0.1` only — never a public interface.
- `GuacdProxy` requires a one-time token per WS connection; tokens are 128-bit random, single-use, 30s TTL.
- Passwords flow: vault (encrypted at rest) → decrypted in JVM at tab-open time → passed via `connect` instruction over loopback to `guacd` → RDP protocol (NLA-encrypted). Never written to disk as plaintext. Never appears in URLs, page HTML, JS locals that persist, or log lines.
- Cert trust-on-first-use + pinning by default. `Ignore certificate errors` is per-host and visibly warning-flagged in the dialog.
- Drive-share default path is under `~/TermLab/RDP Shared/` — user-visible; no magic temp dirs.

## Out of Scope for MVP

- **Audio redirect** — phase 2.
- **Session recording** — phase 2.
- **Share bundle export/import of RDP hosts** — phase 2, needs `ShareBundle` schema bump (same pattern as the Runner spec).
- **RD Gateway (RDG)** — phase 2.
- **Smart card / Kerberos / cert-based auth** — phase 2.
- **VNC** — separate plugin if ever built; not this one.
- **Cross-window literal drag-drop** (mouse drag from remote desktop into local TermLab UI) — not possible over RDP; documented.
- **Embedded WebAuthn / Windows Hello passthrough** — out of scope.
- **Per-monitor tabs for multi-head setups** — MVP does single-tab multimon (RDP spans monitors inside one canvas); per-monitor tabs deferred.

## Cross-Cutting Design-Risk Callouts

Three items that will affect the implementation plan if not handled explicitly:

1. **JCEF availability.** JCEF is optional in some IntelliJ Platform builds. `TermLabApplicationInfo.xml` must ensure the JCEF runtime is present; the plan needs a verification task up-front.
2. **WebSocket sub-protocol fidelity.** `guacamole-common-js` expects a WebSocket server that speaks the Guacamole protocol. We're building that server in-JVM. The `GuacdProxy` framing and sub-protocol negotiation must exactly match what the JS client expects; budget time for a first-integration debugging loop.
3. **`guacd` bundling and signing.** First time TermLab ships a native C binary inside the installer. Adds new installer-side work: building `guacd` for macOS (universal), Linux (x64 + arm64), Windows (x64); codesign + notarization on macOS; Authenticode on Windows. The plan needs a dedicated track for this, separate from the plugin code.

## Phase 2 Candidates

Documented here so the MVP scope is not accidentally compromised by "one more thing":

- Audio redirect.
- Session recording.
- Share bundle export/import integration.
- RD Gateway.
- Smart card / Kerberos / cert-based auth.
- Per-monitor tab splitting for multi-head.
- Test Connection button in `RdpHostEditDialog`.
- Performance profiling / frame-drop telemetry on large displays.
