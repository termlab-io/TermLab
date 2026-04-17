# File Search Design

**Goal:** Add a "Files" tab to TermLab's Search Everywhere that does filename-only search across the currently-open SFTP local pane's cwd and, when connected, the remote pane's cwd. Enter opens the selected file in the TermLab light editor. Result rows show a source indicator (`local` or `<hostname>`). No disk-persisted index — shell out to the best available file-listing tool (`rg → fd → find`), cache the resulting path list in memory until the pane moves or reconnects, and fall back to a live query when the cache misses.

**Driving context:** A prior attempt on branch `codex/search-subsystem` (commit `8002158`, ~2K lines) built a full indexed search subsystem with persistent config, crawlers, an index store, and four separate SE tabs (local-files, local-text, remote-files, remote-text). That design carried too much surface area for too little payoff. This spec replaces it with a much smaller shell-out-and-cache approach, filename-only, single SE tab, no persistent state.

**Scope one-liner:** one new plugin (`plugins/search/`), five classes, one SE tab id added to the allowlist.

## Architecture

**Five units, each small and focused:**

1. **`FileSearchContributor`** — `SearchEverywhereContributor<FileHit>` that registers the "Files" tab. Knows nothing about rg/find; asks the pane-owned caches and renders results. Implements the hybrid live-fallback: if filtered cache results are below a threshold and query length ≥3, fires a live `FileLister.query*` call and streams additional results.

2. **`FileListCache`** — per-pane in-memory holder: `List<String>` of paths + build state (`EMPTY | BUILDING | READY | FAILED`) + root + tool used. No disk persistence.

3. **`FileLister`** — runs the shell command. Static methods `listLocal`, `listRemote`, `queryLocal`, `queryRemote`. Probes tool order rg → fd → find per side, caches the choice per session. Pure (no IntelliJ awareness), unit-testable.

4. **`FileSearchFilter`** — application-level `PersistentStateComponent` for exclusion chips, custom globs, and a single optional regex. Feeds both the filter toolbar and the `FileLister` command construction.

5. **`FileSearchStatusProgress`** — thin wrapper over `Task.Backgroundable` that cache builds use to surface in the IDE status bar. Owns the title-format logic (`"Indexing files in <root>"` locally, `"Indexing files on <hostname>"` remotely) and the cancellation-to-`EMPTY` transition, so those concerns live next to each other rather than being inlined into both call sites.

### Data flow on SE open

```
user opens SE Files tab
    ↓
FileSearchContributor.fetchElements(query)
    ↓
ask LocalPane.cache  →  if EMPTY, trigger build via FileLister + status progress
ask RemotePane.cache →  same (skipped if pane is disconnected)
    ↓
filter both caches with MinusculeMatcher(query) + apply FileSearchFilter.regex
    ↓
merge, rank, stream to Search Everywhere
    ↓
if combined results < threshold (3) AND query length ≥ 3:
    fire live FileLister.query* on each side; stream deduped extras
```

### Ownership / wiring

- `FileListCache` is a **field on the existing `RemoteFilePane` and `LocalFilePane` classes**, lazy-inited. The panes already own cwd and connection state; adding the cache here avoids introducing a new project service.
- On cwd change or reconnect (existing callbacks in the pane classes) → `cache.invalidate()`.
- On pane dispose → cache is GC'd with the pane.
- The contributor looks up the SFTP tool window via `ToolWindowManager.getInstance(project).getToolWindow(SftpToolWindowFactory.ID)`, then grabs `contentManager.getSelectedContent()` — the currently-active SFTP session, matching the pattern already used by `SftpSearchEverywhereContributor`. Each SFTP content has its own `LocalFilePane` + `RemoteFilePane` and therefore its own pair of caches; other SFTP sessions not currently selected are not searched. No SFTP tool window open or no selected content → the contributor emits a single empty-state row with the "Open an SFTP tool window to search files." hint.

### Single contributor, not split

One SE tab ("Files") combines local + remote results with a source indicator per row. Rationale: matches the user's mental model ("find this file, wherever it lives right now"), and a single tab means one filter toolbar rather than two duplicated toolbars.

## `FileLister` — tool detection and commands

### Tool probing — local (mac / linux / windows)

1. **Bundled IntelliJ ripgrep** via `PathManager.getBinPath()` — present on every JetBrains install. Always-available fast path.
2. `which rg` / `where.exe rg` on PATH.
3. `which fd` / `where.exe fd`.
4. Platform-specific fallback:
   - macOS / Linux: `find`.
   - Windows: the OS has no POSIX `find` (the Windows `find.exe` is a text-search tool, unrelated). Last resort: in-JVM `Files.walk(root)`. Slow, but universal, and in practice this path should never trigger because the bundled JetBrains rg is always present.

Probe result cached in a static `AtomicReference<Tool>` per JVM lifetime.

### Tool probing — remote

Single exec: `command -v rg || command -v fd || command -v find`. Output tells us which tool the host has. Cached on the `ClientSession` (or a `WeakHashMap<ClientSession, Tool>`) until reconnect.

If all three are missing or the probe exec itself fails (permission denied, exotic shell), last-resort is `ls -R <root>` streamed back, with all filtering done JVM-side. Deliberately slow; just has to not crash.

### Commands per tool

| Tool | Listing all files | Substring live query |
|---|---|---|
| rg | `rg --files --hidden --no-ignore-vcs <root>` | `rg --files --hidden --no-ignore-vcs <root>` then JVM-side substring filter |
| fd | `fd --type f --hidden --no-ignore . <root>` | `fd --type f --hidden --no-ignore <pat> <root>` |
| find | `find <root> -type f` | `find <root> -type f -iname '*<pat>*'` |

Notes:
- `--hidden` / dotfile inclusion: on — dotfiles matter on a home directory.
- `--no-ignore-vcs` / `--no-ignore`: we're not inside a project, we're in `~`; respecting gitignore would drop things the user expects to see.
- Exclusions applied per-tool as `-g '!<pattern>'` (rg/fd) or `-not -path '<pattern>'` (find). See Filter section.
- Live-query for rg just re-runs the listing and filters JVM-side rather than piping through a second `rg` — one process instead of two, and rg's `--files` output is already quick to scan.

### Remote execution mechanics

Runs via `session.createExecChannel(command)` on the existing SFTP pane's `ClientSession` — no new SSH connection. Output streamed from `channel.getInvertedOut()`, one path per line, into a `BufferedReader`. `ProgressIndicator.isCanceled()` check in the read loop; cancellation closes the channel which kills the remote process via SSH signalling.

### Balloon hint on tool fallback

If the chosen tool for a side is `FIND` (or `Files.walk` on Windows), `FileSearchContributor` fires a one-shot notification:

- Title: "TermLab file search"
- Content: *"Using `find` for file search on \<host|local\>. Install [ripgrep](https://github.com/BurntSushi/ripgrep#installation) for much faster indexing."*
- Action: "Don't show again for this host"

Suppression state stored in application settings keyed by `hostId` (or the literal string `"local"`). One notification per host per session, regardless; the dismissal persists across sessions.

## `FileListCache` — lifecycle and invalidation

### State machine

```
EMPTY ──build──▶ BUILDING ──success──▶ READY
                     │                    │
                     └──failure──▶ FAILED │
                                          │
                  invalidate ◀────────────┘
                     │
                     ▼
                   EMPTY
```

### Fields

```java
final class FileListCache {
    enum State { EMPTY, BUILDING, READY, FAILED }
    volatile State state = State.EMPTY;
    volatile String root;              // "/Users/dustin" or "/home/foo"
    volatile FileLister.Tool tool;     // which tool built this listing
    volatile List<String> paths;       // immutable once READY
    volatile boolean truncated;        // hit the 200k cap
    volatile String failureMessage;    // shown in SE empty state
    private final Object buildLock = new Object();
}
```

### Build trigger (lazy)

Only `FileSearchContributor.fetchElements` calls `cache.ensureBuilt()`. First call takes `buildLock`, transitions `EMPTY → BUILDING`, submits a task to `AppExecutorUtil.getAppExecutorService()`, and kicks off a `Task.Backgroundable` for the status-bar indicator. Subsequent `ensureBuilt()` calls while `BUILDING` are no-ops. On result → `READY`; on exception → `FAILED` with `failureMessage`.

### Invalidation triggers (each resets to `EMPTY`)

1. SFTP pane navigates to a different root directory (existing `onCwdChange` callback).
2. Remote pane reconnects (new `ClientSession`).
3. Pane disposes (cache GC'd with it).
4. User hits "Refresh listing" in the Files tab filter panel (manual).
5. User toggles an exclusion chip / edits custom globs (because these change the listing command).
6. User edits the custom regex → **no** invalidation; the regex is applied JVM-side on the cached paths.

No time-based invalidation. Staleness after legitimate filesystem changes is handled by the hybrid live-fallback in `FileSearchContributor`.

### Size cap

Hard cap at **200,000 paths** per cache. If a listing overflows:
- Truncate at 200k.
- Set `truncated = true`, keep `state = READY`.
- Contributor surfaces inline message: *"Listing truncated at 200k — narrow the SFTP root to search more."*

Prevents runaway home dirs with huge `node_modules` trees from OOM'ing the IDE.

### Hybrid live-fallback

When `fetchElements` filters the cache and gets fewer than 3 matches, and the query length ≥ 3 characters:
- Fire `FileLister.queryLocal/queryRemote(root, query)` with the query as a substring argument.
- Stream additional results into the same SE result list, deduping against cache results.

This catches files created after the cache was built without auto-invalidating on time.

## `FileSearchFilter` — exclusions & toolbar

### State

```java
@State(name = "TermLabFileSearchFilter", storages = @Storage("termlab-file-search.xml"))
final class FileSearchFilter implements PersistentStateComponent<FileSearchFilter.State> {

    static final class State {
        // Default chips — toggleable, each maps to a canonical glob set
        // baked into the filter class.
        public boolean excludeGit = true;          // .git, .svn, .hg
        public boolean excludeNodeModules = true;  // node_modules
        public boolean excludeIdea = true;         // .idea, .vscode
        public boolean excludeBuild = true;        // build, dist, target, out
        public boolean excludeCache = true;        // .cache, __pycache__, .gradle
        public boolean excludeDsStore = true;      // .DS_Store, Thumbs.db

        // User-added glob patterns applied at listing time
        public List<String> customExcludes = new ArrayList<>();

        // Optional regex applied JVM-side on cached paths after listing
        public String excludeRegex = "";

        // Per-host dismissal state for the "install ripgrep" balloon
        public Set<String> dismissedRipgrepHints = new HashSet<>();
    }
}
```

Each chip maps to a canonical glob set via `FileSearchFilter.globsFor(chip)`. The mapping is baked into the class, not user-editable.

### Filter UI

`FileSearchContributor.getActions(Runnable onChanged)` returns a single `AnAction` — a "Filter" funnel icon. Clicking opens a `JBPopup` with:

1. A column of six checkboxes, one per default chip (`.git`, `node_modules`, `.idea / .vscode`, `build / dist / target / out`, `.cache / __pycache__ / .gradle`, `.DS_Store / Thumbs.db`). State reflects `FileSearchFilter.State`.
2. "Custom patterns" multiline text area — one glob per line.
3. "Custom regex" single-line text field. Compile-check on edit; if invalid, show inline red underline and don't apply.
4. "Reset to defaults" link.
5. "Refresh listing" button — calls `cache.invalidate()` on both caches and invokes `onChanged`, which tells SE to re-run the query.

Changing any chip / custom pattern:
- Persists the new state.
- Invalidates both caches.
- Invokes `onChanged`.

Changing the regex:
- Persists.
- Invokes `onChanged` (re-filters cached paths; no cache rebuild).

### Exclusion flag construction per tool

`FileSearchFilter.toListCommandFlags(Tool tool)` returns the tool-specific exclusion flags:

| Tool | Flag form |
|---|---|
| rg | `-g '!<pattern>'` for each chip-expanded glob + each custom pattern |
| fd | `-E '<pattern>'` for each |
| find | `-not -path '*/<pattern>/*'` for each (name-based patterns become `-not -name`) |

The regex is **not** included here — it's applied in the JVM after listing, because regex dialects differ across rg / fd / find and uniform JVM regex is simpler.

### Filter-active indicator

When `FileSearchFilter.State` is anything other than its defaults, the funnel `AnAction` icon shows a small dot overlay (reusing IntelliJ's `LayeredIcon` pattern). Tells the user results may be narrowed from baseline.

### Persistence scope

Application-level, not project-level. Exclusions encode "what I consider noise" — the user's preference, not a project property.

## Progress, errors, empty states

### Status bar progress

One `Task.Backgroundable` per cache build, titled:
- Local: `"Indexing files in <root>"`
- Remote: `"Indexing files on <hostname>"`

Cancellable via the status-bar widget's X button. Cancellation → `BUILDING → EMPTY`. Two concurrent builds (local + remote on a fresh SE open) show as two status-bar entries, which is standard IntelliJ behavior.

### Empty-state messages in SE results area

| Condition | Message |
|---|---|
| No SFTP tool window open | "Open an SFTP tool window to search files." |
| SFTP open, disconnected, local cache ready | *(no message; local results only)* |
| Both caches `EMPTY`, build just triggered | "Indexing…" |
| Both caches `BUILDING` | "Indexing… (progress in status bar)" |
| One cache `FAILED` | "<Side> search failed: <reason>. Other side still available." |
| Both caches `READY`, zero matches | "No files matching '<query>'." |
| Truncated at 200k | "Listing truncated at 200k — narrow the SFTP root to search more." |
| Regex invalid | "Regex invalid: <compile error>." |

### Balloon notifications

1. **Tool fallback:** see the `FileLister` section. One per host per session, dismissible persistently.

2. **Remote probe failure:** *"Could not probe for search tools on \<hostname\>. Falling back to in-memory walk."* One-shot per session.

### Error handling philosophy

- Cache failures are isolated per-side. Local failing never breaks remote and vice versa.
- Any exec error (non-zero exit from rg/fd/find; IOException reading stdout) → `state = FAILED`, `failureMessage` set, surfaced in the SE empty-state.
- `fetchElements` never throws. Worst case it emits zero result rows; the empty-state message tells the user what's up.

### Cancellation

- New query typed → prior in-flight **live-query** gets its `ProgressIndicator.cancel()` → kills subprocess (local) or closes exec channel (remote, which SIGHUPs the remote process via SSH semantics).
- SE popup closed → same, but background cache builds keep running (benefit from them on the next open).
- User cancels status-bar indicator → cache build aborts, state → `EMPTY`.

### Intentionally not surfaced

- Per-file read errors during listing (permission denied on individual subdirs). Swallowed silently by rg/fd/find; we match that behavior.
- Per-query timing / performance metrics. Not worth the UI.

## Result rendering

`FileHit` record:
```java
record FileHit(String path, String displayName, Side side, String hostName) {
    enum Side { LOCAL, REMOTE }
}
```

Cell renderer shows, per row:
- File icon (generic — we don't stat each file for mime type).
- File basename in primary text.
- Parent dir path in secondary (greyed) text, right-aligned or on a second line — following `TerminalPaletteContributor`'s two-line pattern.
- Source chip on the right: either a small monitor icon labeled `local`, or a server icon labeled `<hostName>`.

Ranking: `MinusculeMatcher` score (fuzzy, camelCase-aware). Ties broken by shorter path first, then alphabetical. Local results and remote results are interleaved by score — not grouped — so the best match wins regardless of side.

### Enter action

`processSelectedItem(FileHit hit, ...)`:
- `LOCAL`: open `LocalFileSystem.getInstance().findFileByPath(hit.path)` in the light editor via the existing editor plugin's open-file API.
- `REMOTE`: build an `sftp://<hostId>/<absolute path>` URL, resolve it via the existing `SftpVirtualFileSystem`, open in the light editor. Both existing plumbing — no new editor code.

## Registration

### New plugin `plugins/search/`

- `plugins/search/BUILD.bazel` — mirrors the structure of `plugins/sftp/BUILD.bazel`, depends on `core`, `ssh`, `sftp`, `editor`, IntelliJ platform APIs.
- `plugins/search/resources/META-INF/plugin.xml`:
  - `<id>` = `com.termlab.search`
  - `<searchEverywhereContributor implementation="com.termlab.search.FileSearchContributor$Factory"/>`
  - `<applicationService serviceImplementation="com.termlab.search.FileSearchFilter"/>`

### `TermLabTabsCustomizationStrategy` allowlist

`ALLOWED_TAB_IDS` gains `"TermLabFiles"`. Final set: `{"ActionSearchEverywhereContributor", "TermLabTerminals", "TermLabHosts", "TermLabVault", "TermLabSftp", "TermLabFiles"}`.

### SFTP pane wiring

- `LocalFilePane` and `RemoteFilePane` each gain a public `fileListCache()` accessor and an internal `invalidateFileListCache()` call added to their existing cwd-change and reconnect code paths.
- No change to public API used by any other plugin.

## Testing

### Unit tests (pure logic, no subprocess exec)

- `FileLister`: tool probing precedence (bundled rg → PATH rg → fd → find → platform fallback), command-string construction per tool per OS, exclusion flag emission from `FileSearchFilter.State`. Parameterized over {RG, FD, FIND} × {macOS, Linux, Windows}.
- `FileListCache`: state transitions, cancellation mid-build, concurrent `ensureBuilt()` calls serialize on `buildLock`, truncation flag set at 200k.
- `FileSearchFilter`: regex compile-error handling, chip-to-glob expansion, default-vs-non-default detection (for the dot overlay), glob-to-flag mapping per tool.

### Integration tests (hit real local filesystem)

- Temp dir tree with known files + excluded subdirs (`.git`, `node_modules`, plus one custom-excluded dir).
- `FileLister.listLocal` — assert excluded dirs are pruned, other files appear, `Tool` field matches the probed tool.
- One test per tool: `rg` if bundled or on PATH, `fd` if on PATH, `find` on *nix. Each gated with `assumeTrue(toolAvailable(...))`.
- Windows CI: `Files.walk` fallback test only.
- No integration tests for remote — stubbing `ClientSession.createExecChannel` is not worth it; covered by manual smoke.

### Manual smoke tests

1. SE Files tab with no SFTP tool window open → empty-state message.
2. Open SFTP local-only (no remote connected) → cache builds, status bar shows indicator, results appear with only `local` chips.
3. Connect to a host → second cache builds, results interleave with `<hostname>` chips.
4. Type a substring not present in cache → live fallback fires, returns disk-fresh results if any.
5. Toggle an exclusion chip → caches invalidate, rebuild on next query; funnel icon shows the dot overlay.
6. Enter a bad regex → doesn't crash; empty-state says "Regex invalid: <error>."
7. Enter on a local result → opens in light editor.
8. Enter on a remote result → opens in light editor via `sftp://` VFS.
9. Disconnect mid-search → remote results vanish, local still searchable.
10. Navigate SFTP local pane to a new cwd → cache invalidates; next SE open rebuilds.
11. Tool-fallback balloon: on a host with no rg/fd, first SE open shows "install ripgrep" balloon; "Don't show again for this host" suppresses it for future sessions.
12. Status bar cancel: kick off a listing on a large remote home, click X in status bar → cache returns to EMPTY, no crash.
13. 200k overflow: point remote pane at `/` on a host with a sprawling filesystem → listing truncates at 200k, warning row appears, no OOM.

## Scope

**In scope:**
- New `plugins/search/` plugin (BUILD.bazel, plugin.xml, 5 classes)
- `FileSearchContributor` registered as `<searchEverywhereContributor>`
- `TermLabTabsCustomizationStrategy.ALLOWED_TAB_IDS` updated with `"TermLabFiles"`
- `FileListCache` fields on `LocalFilePane` and `RemoteFilePane` + invalidation wired into existing cwd-change / reconnect hooks
- Bundled IntelliJ ripgrep detection via `PathManager.getBinPath()`
- Exclusion filter (chips + custom globs + custom regex), application-persisted
- Status-bar progress during builds, cancellable
- Balloon on tool fallback (rg / fd absent), dismissible per-host
- 200k result cap with truncation UX
- Light editor integration for Enter (local direct, remote via existing `sftp://` VFS)
- Unit + integration tests per the Testing section

**Out of scope:**
- File content search (grep inside files). Spec is filename-only.
- Multi-host search across more than the one currently-connected SFTP remote.
- User-configured additional search roots beyond the SFTP pane's cwd.
- Search history, recent-files ranking.
- Eager cache pre-warming before SE opens (considered as Approach 2, rejected in favor of lazy Approach 1).
- Binary injection of ripgrep onto remote hosts (considered, rejected — balloon-to-install is enough).
- Windows native `find`-equivalent beyond `Files.walk` fallback.
- Any disk-persisted index.
- Time-based cache invalidation / filesystem watchers. The refresh button + hybrid live-fallback cover staleness.
- Changes to existing SFTP, Hosts, Vault, Terminals SE tabs.
- Re-introduction of the old `plugins/search/` subsystem from branch `codex/search-subsystem`. That branch's code will not be referenced; this is a greenfield replacement.

## Commit ordering

The work should land in two commits to keep the build green at each step:

1. **SFTP pane hooks + new plugin.** Add `FileListCache` fields and `invalidateFileListCache()` wiring to `LocalFilePane` and `RemoteFilePane`. Add the new `plugins/search/` plugin in full (all five classes, BUILD.bazel, plugin.xml). At this point the plugin is a leaf consumer of the SFTP plugin; nothing else imports it. Everything compiles.

2. **Allowlist update.** Add `"TermLabFiles"` to `TermLabTabsCustomizationStrategy.ALLOWED_TAB_IDS`. One-line change; no code depends on it, it just unhides the tab once the contributor is registered.

A single-commit variant works too if the plan prefers; the split is just a convenience so the SE tab only appears after the contributor is fully wired.
