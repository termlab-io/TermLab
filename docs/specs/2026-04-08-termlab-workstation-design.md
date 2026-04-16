# TermLab — Design Specification

> **Build approach updated 2026-04-10.** This spec was originally written assuming TermLab would be a standalone Gradle project consuming the IntelliJ Platform as a Maven dependency. The implementation took a different route: TermLab is now built **inside** the `intellij-community` source tree using **Bazel**, the same build system JetBrains uses internally. The TermLab source lives in this repo (`termlab_workbench`) and is symlinked into `intellij-community/termlab/` so Bazel can find it. See `README.md` in this repo for the layout.
>
> Implications for this spec:
> - "Tech Stack" section's "Build" and "Plugin SDK" rows are obsolete. The active build system is Bazel; there is no published Maven artifact for the plugin SDK yet (third-party plugins are out of scope until v2).
> - Module/package layout described below is conceptually correct, but the actual on-disk paths are under `termlab_workbench/` (this repo) rather than the original `termlab_2/` Gradle layout.
> - Everything else — extension points, terminal/workspace model, plugin responsibilities, security model, file layout under `~/.config/termlab/` — is still authoritative.
>
> Implementation plans live alongside this spec under `docs/plans/`. Each plan notes any further divergence from this spec.

## Overview

TermLab is a terminal-driven workstation built as an IntelliJ Platform product. Where IntelliJ IDEA is an IDE with code editors as the primary viewport, TermLab replaces editors with JediTerm terminal instances. It ships with bundled plugins for SSH host management, SFTP file browsing, an encrypted credential vault, and SSH tunnel management. Third-party plugins extend the workstation through the same extension point system used by the bundled plugins.

**Guiding principles:**
- Terminal is the primary viewport; everything else supports it
- Keyboard-driven — core workflows reachable in 2-3 keystrokes
- Plugin-first — bundled features are plugins, proving the API is sufficient
- Stable, simple, "just works" — clean architecture over cleverness
- The developer (Dustin) must be able to understand and debug every line of TermLab-specific code

## 1. Platform & Architecture

### 1.1 Technology Stack

| Component | Technology |
|---|---|
| Platform framework | IntelliJ Platform (Community Edition, stripped) |
| Terminal emulator | JediTerm (bundled with IntelliJ Platform) |
| Language | Java (JDK 21+) |
| SSH/SFTP | Apache MINA SSHD (in SSH plugin) |
| Encryption | javax.crypto (AES-256-GCM) + Argon2 (Bouncy Castle or argon2-jvm) |
| Distribution | jpackage — native installer with bundled JRE |
| Build | Gradle with `org.jetbrains.intellij` plugin |
| Plugin SDK | Published Maven/Gradle artifact (`termlab-plugin-sdk`) |

### 1.2 Stripped IntelliJ Platform

TermLab excludes all developer-focused plugins and infrastructure:
- No language plugins (Java, Kotlin, Python, etc.)
- No build system plugins (Gradle, Maven)
- No VCS plugins (Git)
- No code indexing, PSI parsing, or inspections
- No Search Everywhere contributors for Files, Classes, Symbols, or Text

The remaining platform provides: window management, tool window docking framework, action system, keymap, settings framework, theming, extension point infrastructure, Project View (file explorer), VFS (scoped narrowly), and status bar.

### 1.3 Architecture Layers

```
+---------------------------------------------+
|              TermLab Core Module               |
|  - Terminal tab management (JediTerm)        |
|  - Workspace persistence (save/load)         |
|  - Mini-window quick terminal                |
|  - CWD-aware Project View wiring             |
|  - Extension points (the plugin contract)    |
|  - Command Palette (Search Everywhere)       |
+---------------------------------------------+
|            Bundled Plugins                   |
|  +-----------+ +----------+ +------------+  |
|  | SSH Host  | |  SFTP    | | Credential |  |
|  | Manager   | | Browser  | |   Vault    |  |
|  +-----------+ +----------+ +------------+  |
|  +-----------+                               |
|  |  Tunnel   |                               |
|  |  Manager  |                               |
|  +-----------+                               |
+---------------------------------------------+
|         IntelliJ Platform (stripped)          |
|  Window mgmt, tool windows, docking,         |
|  actions, keymap, settings, theming,          |
|  extension points, Project View, VFS          |
+---------------------------------------------+
```

The core module is thin. It defines extension points, wires JediTerm into the editor area, and manages workspace state. All SSH, SFTP, vault, and tunnel functionality lives in plugins.

### 1.4 Core Extension Points

**TermLab-defined extension points** (defined by the core module):

| Extension Point | Purpose | Example Implementors |
|---|---|---|
| `terminalSessionProvider` | Supply a `TtyConnector` for terminal tabs | Local PTY (core), SSH plugin, Docker plugin |
| `commandPaletteContributor` | Add searchable items to command palette | SSH hosts, vault accounts, terminals |
| `credentialProvider` | Supply credentials to other plugins | Vault plugin (primary), future: 1Password, HashiCorp |

**Platform extension points** (provided by IntelliJ Platform, used by bundled and third-party plugins):

| Extension Point | Purpose | Example Implementors |
|---|---|---|
| `toolWindowFactory` | Register dockable tool windows | SSH host list, SFTP browser, tunnels |
| `statusBarWidgetFactory` | Add widgets to the status bar | Vault lock indicator, connection status |
| `configurableProvider` | Add pages to Settings | SSH settings, vault settings |

The core ships with one built-in `TerminalSessionProvider`: the local PTY provider. Everything else is plugins.

## 2. Terminal as Primary Viewport

### 2.1 Editor Area Replacement

A custom `FileEditorProvider` maps terminal sessions to the editor area. Each terminal tab is a JediTerm instance backed by a `TtyConnector` provided by either the built-in local PTY provider or a plugin.

Terminal sessions are opaque to the core. The core receives a `TtyConnector` and a display name. It does not know or care whether it's a local shell, SSH, Docker exec, or a serial port.

### 2.2 TerminalSessionProvider Interface

```java
public interface TerminalSessionProvider {
    String getId();
    String getDisplayName();
    Icon getIcon();

    // Can this provider open a session without user input?
    // Local PTY: true. SSH (needs host selection): false.
    boolean canQuickOpen();

    // Open a session, possibly showing UI to collect
    // parameters (host picker, etc.)
    TtyConnector createSession(Project project);
}
```

### 2.3 Tab Behavior

- New tab: `Cmd+T` — opens a local shell tab at `~` (or the CWD of the currently focused terminal)
- Close tab: `Cmd+W`
- Navigate tabs: `Cmd+Shift+[` / `Cmd+Shift+]`, or `Cmd+1` through `Cmd+9`
- Split horizontal: `Cmd+D`
- Split vertical: `Cmd+Shift+D`
- Tabs display their title (customizable, defaults to process name or SSH host label)
- Tabs are draggable, reorderable, and can be dragged between split groups

### 2.4 CWD-Aware File Explorer

The Project View (file explorer tool window) is rooted at `~`. When a terminal emits OSC 7 (working directory update), the core navigates the file explorer to the current directory. Switching terminal tabs updates the file explorer to that tab's CWD.

### 2.5 Mini-Window

`Cmd+Shift+N` opens a bare `JFrame` — just a JediTerm instance with a title bar. No tool windows, no status bar, no tabs. A quick scratchpad terminal for one-off commands. Multiple mini-windows can be open simultaneously. They are ephemeral and do not participate in workspace persistence.

## 3. Workspace & Session Persistence

### 3.1 What a Workspace Captures

- Open terminal tabs: which `TerminalSessionProvider` created them, display name, tab order, split layout geometry
- For local shells: the working directory (via OSC 7) so the tab can reopen in the same place
- For SSH sessions: the host identifier so the connection can be re-established
- Tool window state: which are open, dock position (left/right/bottom), size, pinned/floating
- Active tab selection and focus state

### 3.2 Storage

Workspace state lives in `~/.config/termlab/workspaces/`. The default workspace is `default.json`.

### 3.3 Lifecycle

1. **First launch**: app opens with a single local shell tab at `~`, no tool windows open
2. **Working**: user opens tabs, SSH sessions, arranges tool windows
3. **Quit**: workspace auto-saves to `default.json`
4. **Relaunch**: workspace restores. Local shell tabs reopen at their last CWD. SSH tabs show as "disconnected" with a "reconnect" action (no auto-connect — credentials may require interaction)
5. **Save workspace**: `Cmd+Shift+S` or command palette — "Save Workspace As..." prompts for a name
6. **Load workspace**: command palette — "Load Workspace" shows a list, replaces current session state
7. The default workspace always auto-saves. Named workspaces are explicit snapshots — loading one makes it the active workspace for auto-save until you switch.

### 3.4 What is NOT Persisted

- Terminal scrollback/history (shell's responsibility via `.bash_history`, `.zhistory`)
- Mini-windows (ephemeral by design)
- Vault unlock state (always starts locked)
- Active SSH connections (restored as disconnected stubs)

## 4. Plugin System

### 4.1 Plugin Structure

Plugins are standard IntelliJ Platform plugins: a JAR (or set of JARs) with a `plugin.xml` descriptor. Plugin authors depend on `termlab-plugin-sdk` (published Maven/Gradle artifact) which contains TermLab-specific interfaces and extension points.

### 4.2 Plugin Development Workflow

1. Create a Gradle project with `org.jetbrains.intellij` plugin
2. Add `termlab-plugin-sdk` as a dependency
3. Implement interfaces, register in `plugin.xml`
4. Build JAR, drop into `~/.config/termlab/plugins/` or install via plugin manager

### 4.3 Plugin Capabilities

Plugins can:
- Open and manage terminal tabs (via any `TtyConnector`)
- Register tool windows (dockable panels with Swing UI)
- Register actions (keyboard shortcuts, menu items, command palette entries)
- Add settings pages
- Contribute status bar widgets
- Provide and consume credentials
- React to events (terminal opened/closed, workspace loaded, etc.)
- Bundle any Java library (Apache MINA SSHD, HTTP clients, etc.)

### 4.4 Inter-Plugin Communication

Plugins depend on each other via `plugin.xml` dependencies. The SSH plugin declares a dependency on the `credentialProvider` extension point. When it needs credentials, it queries the registered `CredentialProvider`. This is loose coupling — an alternative credential provider (1Password, HashiCorp Vault) works without SSH plugin modification.

### 4.5 Plugin Isolation

Each plugin gets its own classloader. Plugins can bundle conflicting library versions without collision. The platform handles dependency resolution and load ordering.

## 5. Bundled Plugins

### 5.1 SSH Host Manager

- **Tool window** (left dock): tree view of saved hosts organized in folders
- **Data model**: `ServerEntry` (id, label, host, port, vault account reference, proxy settings) and `ServerFolder` (name, children)
- **Implements `TerminalSessionProvider`**: connects via Apache MINA SSHD, collects credentials via `CredentialProvider`, returns `TtyConnector` that bridges the SSH channel to JediTerm
- **Implements `CommandPaletteContributor`**: all saved hosts searchable, select to connect
- **Host key verification**: first-connect prompt with SHA256 fingerprint, stored in `~/.config/termlab/known_hosts`, mismatch warning (potential MITM)
- **Proxy support**: ProxyCommand and ProxyJump
- **Settings page**: default SSH options (preferred auth method, default port, default username)
- **Persistence**: `~/.config/termlab/servers.json`, atomic writes

### 5.2 SFTP Browser

- **Tool window** (bottom dock): dual-pane file browser — local on left, remote on right
- **Depends on SSH Host Manager**: uses active SSH connections to open SFTP subsystems
- **Operations**: browse, upload, download, rename, delete, mkdir, permissions
- **File transfers**: background tasks with progress in status bar, cancelable
- **Drag and drop**: between local/remote panes, or from Project View into remote pane

### 5.3 Credential Vault

- **Implements `CredentialProvider`**: the primary way other plugins obtain credentials
- **Vault management dialog**: accessed via action (`Cmd+Shift+V`) or command palette — not a persistent dockable tool window, since vault management is infrequent. Opens a modal dialog for account CRUD (add, edit, delete credentials)
- **Account model**: UUID, display name, username, auth method (password, SSH key + passphrase, or combined)
- **Encryption**: AES-256-GCM with Argon2id key derivation (m=65536, t=3, p=4). Vault file always encrypted on disk at `~/.config/termlab/vault.enc`
- **Device binding**: random 256-bit device secret stored in macOS Keychain (or platform equivalent). Key derivation: `Argon2(password + device_secret + salt)`. Vault is undecryptable on another machine without the device secret. Degraded mode (password-only) if no system keychain available, with user warning.
- **Lock behavior**: auto-lock on configurable inactivity timeout (default: 15 minutes). Always starts locked on launch. Zeros `byte[]`/`char[]` on lock.
- **SSH key generation**: Ed25519, ECDSA P-256/P-384, RSA. OpenSSH format, proper file permissions (0600), tracked in vault.
- **Status bar widget**: lock icon showing vault state (locked/unlocked, time remaining)

### 5.4 Tunnel Manager

- **Tool window** (bottom dock, tab alongside SFTP): list of active and saved tunnels
- **Local port forwarding**: binds local port, forwards through SSH to remote host:port
- **Depends on SSH Host Manager**: uses existing or opens dedicated SSH connections
- **Persistence**: saved tunnels restored in workspace, not auto-started (user activates manually)
- **Status indicators**: connecting, active, error per tunnel

## 6. Command Palette & Keyboard-Driven UX

### 6.1 Command Palette

Search Everywhere, rebranded. Default shortcut: `Cmd+Shift+P`. All built-in Search Everywhere contributors (Files, Classes, Symbols, Text) are excluded.

| Tab | Searches | Source |
|---|---|---|
| All | Everything below, merged | All contributors |
| Actions | Registered actions (settings, commands, plugin actions) | Platform (kept) |
| Hosts | Saved SSH hosts by label, hostname, folder | SSH Host Manager plugin |
| Terminals | Open terminal tabs by title, CWD, SSH host | Core |
| Tunnels | Saved and active tunnels | Tunnel Manager plugin |
| Vault | Credential accounts by name, username | Vault plugin |

Selecting a result executes it: an action runs, a host connects, a terminal tab focuses, a tunnel starts.

### 6.2 Global Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| `Cmd+T` | New local terminal tab |
| `Cmd+W` | Close current tab |
| `Cmd+D` | Split horizontal |
| `Cmd+Shift+D` | Split vertical |
| `Cmd+Shift+P` | Command palette |
| `Cmd+Shift+N` | Mini-window (quick terminal) |
| `Cmd+Shift+S` | Save workspace as |
| `Cmd+,` | Settings |
| `Cmd+1..9` | Focus tab by position |
| `Cmd+Shift+[` / `]` | Previous / next tab |
| `Alt+1..9` | Toggle tool windows by number |

All shortcuts are remappable via IntelliJ's keymap system. Plugin-contributed actions automatically get keymap entries.

### 6.3 UX Principle

Hands never leave the keyboard for core workflows. Connect to a host: `Cmd+Shift+P`, type hostname, Enter. Split and SSH into a second server: `Cmd+D`, `Cmd+Shift+P`, type hostname, Enter.

## 7. Security Model

### 7.1 Vault Encryption (At Rest)

- File format: `[MAGIC | VERSION | SALT | NONCE | CIPHERTEXT]`
- AES-256-GCM authenticated encryption
- Argon2id key derivation (m=65536, t=3, p=4) — GPU brute-force resistant
- Vault file on disk is always encrypted; "unlocked" is purely in-memory

### 7.2 Device Binding

- On first vault creation, generate a random 256-bit device secret
- Store in macOS Keychain (Windows: Credential Manager, Linux: Secret Service / libsecret)
- Key derivation: `Argon2(password + device_secret + salt)`
- Vault file copied to another machine is undecryptable without the device secret
- Fallback: if no system keychain available, derive from password + salt only (degraded mode, user warned)

### 7.3 In-Memory Protections

- All secrets held as `byte[]` / `char[]`, never `String`
- Zeroed immediately after use via `Arrays.fill()`
- Vault contents zeroed on lock/seal
- Auto-lock on configurable inactivity timeout (default: 15 minutes)
- Always starts locked on app launch
- Shutdown hook: seal vault and zero memory on `SIGTERM`, `SIGINT`, app close

### 7.4 Process-Level Hardening

- `-XX:+DisableAttachMechanism` — prevents debugger attachment
- Heap dumps disabled
- Shutdown hook for graceful vault sealing on process termination

### 7.5 SSH Security

- Host key verification with SHA256 fingerprints
- Known hosts stored in `~/.config/termlab/known_hosts`
- Host key mismatch warns user (potential MITM)
- Credential lifetime: read from vault, passed to SSH library, zeroed. Plaintext credentials do not persist in TermLab code.

### 7.6 Plugin Trust

- Plugins run in the same JVM (IntelliJ model) — plugins are trusted code
- Installation is an explicit user action
- Future: plugin marketplace with signing/verification (not in v1)

## 8. Configuration & Theming

### 8.1 Configuration Files

| Path | Purpose | Managed By |
|---|---|---|
| `~/Library/Application Support/TermLab/` | IntelliJ platform settings (keymap, appearance, UI state) | Platform |
| `~/.config/termlab/workspaces/` | Saved workspace state files | Core |
| `~/.config/termlab/servers.json` | SSH host/folder/tunnel configuration | SSH Host Manager plugin |
| `~/.config/termlab/known_hosts` | SSH host key store | SSH Host Manager plugin |
| `~/.config/termlab/vault.enc` | Encrypted credential vault | Vault plugin |
| `~/.config/termlab/plugins/` | User-installed plugin JARs | Platform |

Settings managed through the Settings UI use IntelliJ's built-in persistence (XML). TermLab-specific data (SSH hosts, vault, workspaces) uses `~/.config/termlab/` with atomic writes (temp file + rename).

### 8.2 Theming

- **Application theme**: Darcula (dark) / Light / custom — controls tool windows, chrome, menus. Uses IntelliJ's theming system. Plugins can contribute themes.
- **Terminal color scheme**: bundled schemes (Dracula, Solarized, Nord, etc.) plus custom TOML import compatible with Alacritty theme format.

### 8.3 Font Configuration

- Terminal font and application font are separate settings
- Terminal font defaults to JetBrains Mono (ships with platform)
- User configurable: family, size, line height, ligatures on/off

## 9. Reference: Existing Implementations

The following existing Rust implementations serve as architectural reference and API design guides:

- **termlab_remote** (`~/projects/rusty_termlab_2/crates/termlab_remote/`): SSH connection management, SFTP operations, tunnel manager, file transfers, known hosts. The `RemoteCallbacks` trait pattern maps to TermLab's `CredentialProvider` and event-based prompting model.
- **termlab_vault** (`~/projects/rusty_termlab_2/crates/termlab_vault/`): Vault encryption (AES-GCM + Argon2), account CRUD, SSH key generation, lock manager with inactivity timeout, in-memory SSH agent. The data model (`VaultAccount`, `AuthMethod`, `GeneratedKeyEntry`) and encryption format should be replicated in Java.
- **termlab plugin system** (`~/projects/termlab_2/crates/termlab_plugin/`): HostApi trait, plugin bus (pub/sub + query routing), widget system, Lua/Java runtimes. The `HostApi` trait's surface area informed the TermLab extension point design.
