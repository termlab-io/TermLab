# Minecraft Admin Plugin Design

**Goal:** Give a Minecraft server admin a single Conch tool window that shows live server health, lists online players, tails the console, sends RCON commands, and drives AMP lifecycle operations (start / stop / restart / backup) — across one or more configured Paper instances behind Cube Coders AMP.

**Driving context:** This is an optional, non-bundled Conch plugin. It exists for individual users (your friend) rather than as a core Conch feature. It does not replace AMP's web UI or in-game commands; it makes the common "watch-and-poke" admin loop fit inside Conch next to the user's terminal so they don't need to alt-tab to a browser. It depends on `com.conch.core` and `com.conch.vault`, and deliberately does **not** depend on the SSH plugin — SSH-style host shell access stays in the existing SSH tool window when the user needs it.

## Plugin structure

Separate plugin at `plugins/minecraft-admin/` with id `com.conch.minecraft-admin` (Java package `com.conch.minecraftadmin`, since Java package names can't contain hyphens). Bazel target `//plugins/minecraft-admin:minecraft-admin`. Layout mirrors `plugins/ssh/`:

```
plugins/minecraft-admin/
├── BUILD.bazel
├── resources/META-INF/plugin.xml
├── src/com/conch/minecraftadmin/
│   ├── client/         AmpClient, RconClient, ServerPoller, RconPacketCodec, parsers
│   ├── model/          ServerProfile, ServerState, Player, McServerStatus
│   ├── persistence/    ServersFile, McPaths
│   ├── credentials/    McCredentialResolver (vault-backed AMP + RCON passwords)
│   ├── toolwindow/     McAdminToolWindow, StatusStripPanel, PlayersPanel,
│   │                   ConsolePanel, LifecycleButtons, ServerSwitcher
│   ├── ui/             ServerEditDialog, ServerPickerDialog
│   └── actions/        StartServerAction, StopServerAction, RestartServerAction,
│                       BackupServerAction, BroadcastAction, KickPlayerAction,
│                       BanPlayerAction, OpPlayerAction
└── test/com/conch/minecraftadmin/
    └── …  (mirror of src/, one test per non-trivial class)
```

The plugin is **not** bundled in Conch's essential-plugin set. It's built by Bazel alongside the other plugins and installable as an optional module.

Plugin.xml declares:
- `<depends>com.conch.core</depends>`
- `<depends>com.conch.vault</depends>`
- One `<toolWindow>` with id `Minecraft Admin`, anchor `right`
- One `<applicationConfigurable>` under Conch's settings group for server profile management (separate from the tool window's inline gear button, which opens the same dialog)
- No keymap bindings in v1

## Data model

### `ServerProfile` record

```java
public record ServerProfile(
    UUID id,                    // stable id, survives renames
    String label,               // "Survival" — shown in dropdown
    String ampUrl,              // "https://amp.example.com:8080"
    String ampInstanceName,     // friendly AMP instance name (resolved to id at connect time)
    String ampUsername,         // plaintext, no secrets
    UUID ampCredentialId,       // vault reference → AMP password
    String rconHost,            // may be same host as AMP, may be different
    int rconPort,               // default 25575
    UUID rconCredentialId,      // vault reference → RCON password
    Instant createdAt,
    Instant updatedAt
)
```

AMP instance names are stored instead of numeric ids because ids are re-minted when an instance is recreated; the plugin resolves name → id at connect time via `Core/GetInstances` and caches the mapping in memory for the life of the `ServerPoller`.

### `ServerState` snapshot

```java
public record ServerState(
    McServerStatus status,       // RUNNING, STARTING, STOPPED, CRASHED, UNKNOWN
    int playersOnline,
    int playersMax,
    List<Player> players,        // each: (String name, int pingMs)
    double tps,                  // NaN if unavailable
    double cpuPercent,           // NaN if AMP unreachable
    long ramUsedMb,
    long ramMaxMb,
    Duration uptime,
    Optional<String> ampError,   // last AMP failure message, empty if healthy
    Optional<String> rconError,  // last RCON failure message, empty if healthy
    Instant sampledAt
)
```

Immutable, so the EDT never sees a torn snapshot. `NaN` + `Optional.empty()` encode "field unavailable this tick" without forcing the UI to special-case missing data.

### `McServerStatus` enum

`RUNNING`, `STARTING`, `STOPPING`, `STOPPED`, `CRASHED`, `UNKNOWN`. Mapped from AMP's state codes in `AmpClient`.

### `Player` record

```java
public record Player(String name, int pingMs)
```

Gamemode, avatar, and playtime are deliberately out of scope for v1.

## Persistence

`~/.config/conch/minecraft-servers.json` with a versioned envelope and atomic writes (same shape as `ssh-hosts.json` and `tunnels.json`):

```json
{
  "version": 1,
  "servers": [
    {
      "id": "…",
      "label": "Survival",
      "ampUrl": "https://amp.example.com:8080",
      "ampInstanceName": "survival",
      "ampUsername": "admin",
      "ampCredentialId": "…",
      "rconHost": "mc.example.com",
      "rconPort": 25575,
      "rconCredentialId": "…",
      "createdAt": "…",
      "updatedAt": "…"
    }
  ]
}
```

Loaded lazily on tool window open. Writes go through a temp-file-and-rename helper to avoid partial files on crash — same pattern `HostsFile` uses in the SSH plugin.

Passwords never touch this file; both AMP and RCON secrets live in the Conch vault and are referenced by UUID.

## Network clients

### `AmpClient`

Thin wrapper around `java.net.http.HttpClient` for AMP's REST/JSON API. Gson for encoding/decoding (already on the classpath via the SSH plugin). All timeouts capped at 10 seconds.

```java
class AmpClient {
    AmpSession login(String url, String user, char[] password);
    InstanceStatus getInstanceStatus(AmpSession s, String instanceName);
    void startInstance(AmpSession s, String instanceName);
    void stopInstance(AmpSession s, String instanceName);
    void restartInstance(AmpSession s, String instanceName);
    void backupInstance(AmpSession s, String instanceName);
    ConsoleUpdate getConsoleUpdates(AmpSession s, String instanceName);
}
```

`AmpSession` holds the session token returned from `/API/Core/Login`. On a 401 response, the client automatically re-logs in once and retries the original call.

`ConsoleUpdate` is the response shape for `Core/GetUpdates` — a list of new console lines since the last call, used by the console panel when it's focused.

### `RconClient`

Raw Minecraft RCON protocol over a plain `Socket`. ~150 lines total. No external dependency — there is no good Java RCON library, and the protocol is simple enough to implement directly.

```java
class RconClient {
    RconSession connect(String host, int port, char[] password);
    String command(RconSession s, String cmd);
    void close(RconSession s);
}
```

One persistent socket per `ServerPoller` — RCON servers happily handle thousands of commands per connection, so we do **not** reconnect per request. Auto-reconnect on I/O failure with exponential backoff capped at 30 seconds.

Packet encode/decode lives in a separate `RconPacketCodec` class so the framing logic is independently testable.

### `ServerPoller`

One instance per active server profile (typically one at a time — the visible profile in the dropdown). Orchestrates both clients on a scheduled executor.

```java
class ServerPoller {
    ServerPoller(ServerProfile profile, StateListener listener);
    void start();
    void stop();
    CompletableFuture<String> sendCommand(String cmd);
}
```

- Fixed 5-second tick
- Each tick fires **two parallel requests**: AMP `getInstanceStatus`, and a single RCON batch containing `list` + `tps` (parsed individually from the joined reply)
- Uses `AppExecutorUtil.getAppScheduledExecutorService()` — the same shared scheduler the SSH plugin borrows
- Results merge into a new `ServerState` snapshot; if both halves succeed, or if either half succeeds and the other fails, the listener is called on the EDT via `invokeLater(..., ModalityState.any())`
- Stop cancels all scheduled tasks, closes the RCON socket, and lets the AMP session time out naturally
- `sendCommand` bypasses the loop and runs on the background executor, surfacing its reply through a `CompletableFuture`

### Parsers

- **`PaperListReplyParser`** — converts `list` output (e.g. `"There are 3 of a max of 20 players online: alice, bob, carol"`) into a `List<Player>`. Handles empty servers, single-player servers, and plugin-injected color codes.
- **`PaperTpsReplyParser`** — converts `/tps` output (e.g. `"§6TPS from last 1m, 5m, 15m: §a19.98, §a20.0, §a20.0"`) into a single most-recent TPS value.

**Player ping is best-effort.** Paper's built-in RCON `list` command returns names only, and there is no guaranteed per-player ping source in the vanilla RCON surface. The plugin looks for ping in this order at connect time:

1. Parse `list` output for a `[Nms]` suffix if the server is running a plugin that extends `list` (e.g. EssentialsX).
2. Try `minecraft:ping` — some modern Paper builds expose this.
3. Fall back to hiding the ping column entirely and showing only names.

The fallback is the baseline guarantee — v1 ships working even when ping is unavailable. The two probe attempts happen once per `ServerPoller.start()`, and the result is cached for the session so later ticks don't re-probe. Probing strategy and detection details are confirmed empirically in the implementation plan's first manual-verification step.

## UI: `McAdminToolWindow`

A single tool window anchored to the right, stripe button reused from `AllIcons.Webreferences.Server` (matching the SSH hosts tool window's icon choice for now — replaceable later with a custom SVG).

### Top bar
- **Server switcher:** dropdown listing all configured profiles; switching profiles stops the old `ServerPoller` and starts a new one
- **Gear button:** opens `ServerEditDialog` for the currently selected profile (or `ServerPickerDialog` if none is selected)
- **Add button:** opens `ServerEditDialog` for a new profile

### Status strip (below top bar)
Six cells, left to right:
- Status icon (colored dot) + status text
- Players `N/M`
- TPS (one decimal) with a red dot if <15
- CPU `NN%`
- RAM `NNNN / NNNN MB`
- Uptime `Nh Nm`

AMP-sourced fields gray out and show a tooltip "AMP offline: <msg>" when the last tick's AMP call failed. RCON-sourced fields gray out analogously. A small pill appears next to whichever side is failing.

### Lifecycle row
Four buttons: **Start**, **Stop**, **Restart**, **Backup Now**. Enabled/disabled based on `status`:
- `RUNNING` → Stop, Restart, Backup enabled; Start disabled
- `STOPPED` → Start enabled; rest disabled
- `STARTING` / `STOPPING` → all disabled
- `CRASHED` / `UNKNOWN` → Start and Backup enabled

When the user clicks Stop, the plugin records "user-requested stop at <instant>" locally and suppresses the crash balloon for the next 10 seconds so an intentional stop doesn't look like a crash.

### Players panel
JBTable with columns `name | ping (ms)`. Right-click menu: **Kick**, **Ban**, **Op**. Each action sends a confirming RCON command and relies on the next poll tick to reflect the result. No inline state mutation of the table — it's always a render of `state.players`.

### Console panel
Scrollable text area (read-only) with:
- Auto-scroll unless the user has scrolled up
- A single-line input at the bottom with up/down history recall (20 entries, in-memory only)
- "Broadcast" button next to the input — expands to a small dialog that sends the text via RCON `say <msg>` and closes

Lines arrive via `AmpClient.getConsoleUpdates`, polled at 1 second while the console panel is the active sub-panel and at 5 seconds otherwise (to cap traffic when the user is watching the players tab).

## Error handling and degraded modes

### Startup / user-initiated failures (loud)
- **AMP login fails:** modal dialog with the raw error message and an "Edit server…" button that opens `ServerEditDialog` on the offending profile
- **RCON connect fails at first open:** inline warning banner inside the tool window ("RCON unreachable — player list and commands unavailable"); the dashboard still renders AMP-sourced fields
- **Corrupt `minecraft-servers.json`:** tool window shows a "Could not read minecraft-servers.json" panel with "Open file" + "Reset" buttons (matches the SSH plugin's corrupt-hosts-file behavior)
- **AMP lifecycle button fails:** inline toast in the tool window, dismissable, not blocking

### Runtime / poll-loop failures (quiet)
- **Single AMP tick fails:** AMP-sourced fields gray out, "⚠ AMP offline" pill appears, RCON fields keep updating. Tooltip on the pill shows the error message.
- **Single RCON tick fails:** mirror — RCON fields gray out, "⚠ RCON offline" pill, AMP fields keep updating
- **Both fail simultaneously:** lifecycle row collapses to a single "Reconnect" button; status strip grays out entirely
- **Auto-recovery:** poll loop keeps ticking at 5s; as soon as either side succeeds, the gray/pill disappears with no user action
- **No balloon notifications** for these — they'd fire constantly on flaky networks and the UI already communicates the state

### Crash detection (the one balloon)
A `CrashDetector` tracks status transitions across ticks. When the previous tick saw `RUNNING` and the current tick sees `CRASHED`, or the current tick sees `STOPPED` without a user-requested stop within the last 10 seconds, it fires **one** IntelliJ error balloon: "Minecraft server '<label>' crashed". Clicking the balloon focuses the tool window on that server and scrolls the console panel to the end. Dedupe is per-transition: a subsequent restart + crash produces a second balloon. `UNKNOWN` is treated as a transient network/AMP hiccup and never triggers the balloon — the "⚠ AMP offline" pill already covers that case.

### Command submission failures
- **RCON send fails mid-command:** the console prints a red `[error] connection lost, retrying…` line, the command is queued once for retry after reconnect. If reconnect fails, the command is dropped with a red `[error] dropped: <cmd>` line.
- **No balloon** — the console is already visible when the user typed the command.

### Threading
- All network I/O runs on `AppExecutorUtil.getAppExecutorService()` or the scheduled executor; EDT only does rendering and button handlers
- `ServerState` updates are dispatched to the EDT via `ApplicationManager.invokeLater(..., ModalityState.any())` so modal progress dialogs don't starve them (same lesson that fixed the SSH host-key prompt hang)
- `ServerPoller.stop()` is always called before a new `start()` during profile switches; the UI waits for the old poller to drain before wiring up the new one

## Credentials

The Conch vault holds both the AMP password and the RCON password, referenced by UUID from the `ServerProfile`. `McCredentialResolver` fetches them at connect time and hands them to the clients as `char[]` (same approach the SSH plugin's `SshResolvedCredential` takes, for parity — the vault currently stores secrets as strings but the interface accepts `char[]` to allow zeroing later).

The `ServerEditDialog` contains a "Pick credential…" button for each password field that opens the existing Conch vault picker.

## Testing strategy

Same shape as the SSH plugin. JUnit 5 via a new `//plugins/minecraft-admin:minecraft_admin_test_runner` binary that mirrors `//plugins/ssh:ssh_test_runner`.

### Layer 1 — Pure unit tests (~40 tests)
- `RconPacketCodec` — known-good frames, malformed length, oversized payloads, UTF-8 edge cases
- `PaperListReplyParser` — empty / 1-player / N-player / color-coded variants
- `PaperTpsReplyParser` — TPS with and without color codes, stale server variants
- `ServersFile` — round-trip, missing file, corrupt JSON, version mismatch (using `@TempDir`)
- `ServerState` merge logic — every combination of AMP success/failure × RCON success/failure
- `CrashDetector` — clean stop suppresses balloon, unexpected stop fires balloon, crash-after-restart fires a second balloon, dedupe within one transition

### Layer 2 — Fake-server integration tests (~18 tests)
- `AmpClient` — uses `com.sun.net.httpserver.HttpServer` (JDK built-in, no dependency) to serve canned AMP JSON. Covers login happy path, login 401, session expiry → auto re-login, instance status, start/stop/restart/backup, malformed JSON, timeout behavior.
- `RconClient` — uses a tiny `ServerSocket`-based fake that implements the RCON handshake and echoes commands. Covers handshake, auth failure, command round-trip, multi-command persistence on one socket, disconnect mid-command + reconnect.

### Layer 3 — Tool window render smoke tests (~12 tests)
One test per major panel asserting it renders without throwing when fed canned `ServerState` values for every edge case (healthy / AMP-down / RCON-down / both-down / NaN TPS / empty players / N players). No pixel-level assertions — this layer exists to catch NPEs in state → Swing code paths.

### Not tested
- `ServerPoller` scheduling loop beyond a single-tick sanity check
- Real AMP / real Minecraft server integration — that's manual testing against the user's actual server, called out in the implementation plan's "Manual verification" step
- Credential UI flows — reused from the existing Conch vault surface

### Target profile
~60 tests, runtime under 2 seconds, matching the SSH plugin's 95 tests in ~450 ms profile.

## v1 scope summary

In:
1. Multiple server profiles with a dropdown switcher
2. Live status strip: status, players, TPS, CPU, RAM, uptime
3. Lifecycle row: Start / Stop / Restart / Backup
4. Players table with right-click Kick / Ban / Op
5. Console panel with tail, send box, history recall, and a broadcast ("say") button
6. Crash notification balloon, deduped per transition
7. Configuration via `~/.config/conch/minecraft-servers.json` + Conch vault
8. `ServerEditDialog` reached from the tool window gear and from a Settings page

Out (noted for future versions):
- Whitelist / banlist viewers and editors
- Log filter bar and colorization
- Player avatars, gamemode, playtime
- TPS and player-count history graphs (would need local persistence — Approach 3 in the brainstorm)
- AMP WebSocket push updates (would replace the 5s polling loop — Approach 2 in the brainstorm)
- Embedded Bluemap / Dynmap
- Scheduled restart with countdown broadcast
- `server.properties` editor
- Mod / plugin management
- Kill-with-prejudice button for runaway processes
- SSH-backed log tail as an alternative to AMP `Core/GetUpdates`
