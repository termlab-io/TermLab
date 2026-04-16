# Conch Workbench — Three-Track Roadmap

Three large tracks running in parallel, each planned separately but
sharing a small set of dependencies. Goal is to take Conch from
"works on my machine, lives in the intellij-community tree" to
"shippable, distributable, signed, sub-250MB idle terminal-first
product with its own plugin ecosystem".

- **Track A — Resource footprint.** Get idle memory from
  500–700 MB down to 100–200 MB, and reduce cold-start CPU.
- **Track B — IntelliJ de-branding.** Remove every IDE-ism —
  user-visible strings, menus, settings, tool windows, vocabulary
  ("project" → "workspace"), icons, welcome screens, keymap
  conflicts. Make Conch *feel* like a terminal product, not a
  stripped IDE.
- **Track C — Release pipeline.** App-bundle signing, auto-update
  system, plugin SDK + registry, distribution via GitHub Releases.

The three tracks are largely independent but touch in a few
places; dependencies are called out explicitly.

---

## Track A — Resource footprint

### Target metrics

| Scenario | Current | Target |
|---|---|---|
| Idle, no sessions | 500–700 MB | ≤ 200 MB |
| 1 SSH session | ? | ≤ 250 MB |
| 3 SSH sessions | ? | ≤ 350 MB |
| Cold start to usable UI | ? | < 2s on M-series |
| Background CPU when idle | ? | < 0.5% |

The first thing we must do is **measure**. The current numbers are
estimates; we don't yet know the breakdown between heap, metaspace,
classloader metadata, direct buffers, and thread stacks.

### Phase A.0 — Measure (half day)

**What:** Enable Native Memory Tracking
(`-XX:NativeMemoryTracking=summary`) in `conch_run`'s `jvm_flags`,
relaunch, idle 30 seconds, capture diagnostics.

**Artifacts:**

- `jcmd GC.run` then `GC.class_histogram` — top 100 classes by
  retained bytes.
- `jcmd VM.native_memory summary scale=MB` — RSS broken into heap
  / metaspace / class / code / thread / GC / internal / direct
  buffer buckets.
- `jcmd Thread.print` — live thread count and names. IntelliJ
  platform products routinely run 60–100 threads even when idle;
  each costs ~0.5–1 MB of stack.
- Full `GC.heap_dump -all conch.hprof` — ~400 MB file for offline
  analysis in VisualVM or Eclipse MAT. **Dominator tree by
  retained size** is the source of truth for "what's keeping
  what alive".

**Exit:** We have a ranked list of the top 20 memory retainers by
retained size and the top 10 background threads by count.
Without this, every subsequent phase is guesswork.

### Phase A.1 — Low-hanging bundled plugins (1 day)

**Hypothesis:** `intellij.yaml`, `intellij.toml`,
`intellij.textmate.plugin`, `intellij.sh.plugin`, `intellij.json`
are still in `productLayout.bundledPluginModules` in
`ConchProperties.kt`. None of them serve a terminal product —
Conch has no files to syntax-highlight, no project tree to enrich.

**What:** Remove them one at a time from `bundledPluginModules`.
Remove the corresponding `<essential-plugin>` entries from
`ConchApplicationInfo.xml` where present. Rebuild. Verify Conch
still boots and no plugin registration errors fire.

**Expected win:** 30–80 MB of class metadata + loaded plugin
descriptors, plus faster startup (fewer extension points to scan).

**Risks:** Cross-module dependencies we're not aware of. Some
bundled plugin may be pulled in transitively by a core platform
module we can't drop. Mitigation: drop one, rebuild, test; if a
dependency breaks, roll back that one plugin and move on.

### Phase A.2 — Product content descriptor pruning (1 day)

**What:** `ConchProperties.getProductContentDescriptor()` currently
pulls in ~20 platform modules (`platform.backend`,
`platform.frontend`, `platform.monolith`, `platform.editor`,
`platform.searchEverywhere.*`, `platform.inline.completion`, etc).
At least half of these are unused by a product with no editor, no
inline completion, no project backend. Audit each one, remove
whichever we can prove aren't load-bearing.

**Method:** Comment out a module, rebuild, launch, exercise all
Conch features (terminal, SSH, SFTP, tunnels, vault, search
everywhere). If nothing breaks after a few minutes, the module
is dead weight.

**Candidates to attempt dropping:**

- `platform.inline.completion` (certainly unused — no editor)
- `platform.searchEverywhere.backend` / `.frontend` (Conch uses
  SE but via the contributor EP; the backend split may be
  removable)
- `platform.lang.impl.backend` (no language support)
- `platform.editor.backend` (no editor)
- `platform.project.backend` (no project system)

**Expected win:** 40–100 MB of metaspace + fewer background
services.

### Phase A.3 — Stub out the heavy platform infrastructure (2–3 days)

This is the big one. Four platform subsystems always load and
always consume memory, even when Conch has disabled their
behavior via system properties:

1. **`PersistentFSImpl` / `FSRecordsImpl`** — the platform's
   persistent virtual filesystem. Eats ~100 MB at startup for
   a product with no project files. Log line on every launch:
   `VFS initialized: Nms, 0 failed attempts, 0 error(s) were recovered`.
2. **`FileBasedIndexImpl` + `StubIndexImpl`** — file indexing
   infrastructure. `idea.indexes.pretendNoFiles=true` disables
   the *work* but not the *loading*. ~50 MB.
3. **`WorkspaceModelImpl`** — JPS workspace model, lives to
   represent "modules" and "libraries" that Conch doesn't have.
   ~30 MB.
4. **`FileTypeDetectionService`** — scans for file types on
   startup even when there are no files.

**What:** Register service overrides that return no-op / empty
implementations of each of these. The pattern is already in use
for other strippers — see `ConchSearchEverywhereCustomizer`.

**Method:**

- For each target service, write a minimal subclass that
  overrides all public methods to return empty / null / no-op.
- Register via `<applicationService serviceInterface="..."
  serviceImplementation="..."/>` with `overrides="true"` in
  `core/resources/META-INF/plugin.xml`.
- Launch, exercise every feature, check nothing downstream
  assumes the real implementations exist.

**Risks:** HIGH. These services have hundreds of call sites and
any of them may blow up on a no-op. Expect a week of whack-a-mole
finding `NullPointerException` sites. Mitigation: stub
aggressively at first, then walk back individual methods to
return real-but-minimal values when a caller breaks. E.g.
`PersistentFSImpl.findFileById(id)` might need to return a
synthetic root instead of null.

**Expected win:** 150–250 MB if all four are successfully
stubbed. This alone could hit the target memory budget.

### Phase A.4 — Thread reduction (1 day)

**What:** Inspect `jcmd Thread.print` output, identify platform
thread pools that run even with no work. Candidates:

- `ApplicationImpl pooled thread` pool — sized for a multi-module
  IDE, probably too big for Conch.
- `VFS Refresh` — can die with VFS stubbed.
- `IndexingQueue` — can die with indexing stubbed.
- `DumbService` queue — can die.
- `FileDocumentManager` autosave timer — unused.
- Various coroutine dispatchers — audit which are reachable.

**Expected win:** 20–40 threads removed at ~0.5–1 MB stack each
= 20–40 MB, plus less context-switching churn. Also helps cold
start.

### Phase A.5 — Heap + GC tuning (half day)

**What:** With Conch's actual working set known, reduce
`-Xmx`, tune G1 for small heaps (`-XX:+UseG1GC -XX:MaxGCPauseMillis=50
-XX:G1HeapRegionSize=1M`), reduce code cache reservation, use
shenandoah or z-gc if latency matters more than footprint.

**Current flags:** `-Xms128m -Xmx1024m -XX:ReservedCodeCacheSize=240m`.

**Target flags:** `-Xms64m -Xmx384m -XX:ReservedCodeCacheSize=96m`.

**Expected win:** Direct: -150 MB code cache reservation (not all
returned to OS until steady-state, but meaningful). Indirect:
smaller heap means faster GC cycles, less RSS creep.

### Phase A.6 — Measure, iterate, exit criteria

Re-run Phase A.0 diagnostics, compare to baseline, decide whether
to stop or keep going.

**Exit criteria:**
- Idle RSS ≤ 200 MB on macOS 14 / M1 / Conch Dark / no projects
  open / no SSH sessions
- Cold start ≤ 2s to ready status bar
- Background CPU ≤ 0.5% over a 60s idle window

### Dependencies & prerequisites

- Phase A.0 blocks all subsequent phases.
- Phase A.3 depends on deep understanding of where each platform
  service is called from — likely to expose bugs that need per-
  caller analysis.
- No external dependencies; all work is in the Conch tree.

### Known risks

- **Upstream churn:** intellij-community platform updates may
  reintroduce services we stubbed. Every upstream bump becomes a
  risk surface.
- **Brittle stubs:** stubbing `PersistentFSImpl` may work 99% of
  the time but fail on an edge case during a rare code path.
- **Regressions hidden by the build:** we won't know something
  broke until the user hits it. Mitigation: add smoke tests
  covering terminal launch, SSH connect, SFTP browse, vault unlock.

---

## Track B — IntelliJ de-branding

### Goal

A user launching Conch for the first time should never see the
word "IDE", "IntelliJ", "project", "module", "refactor", "build",
"debug", or any other vocabulary that suggests they're using a
programming tool. Menus, settings, dialogs, status bars, and the
splash screen should all read like a terminal product.

This is less about stripping code (Track A owns that) and more
about **controlling what the user sees** — even if the
underlying machinery still exists.

### Phase B.0 — Audit (1–2 days, no code changes)

**What:** Launch Conch with a fresh config. Take screenshots of
every reachable UI surface. Build a spreadsheet of every string,
menu item, settings page, tool window, and dialog that mentions
IDE concepts.

**Coverage checklist:**

- Main menu bar: File, Edit, View, Navigate, Code, Refactor,
  Build, Run, Tools, VCS, Window, Help — enumerate every
  submenu
- Settings → Preferences: every configurable in the tree
- Welcome frame: "Create New Project", "Open", "Check out from
  Version Control" — should be a "Open Workspace" or similar
- About dialog: product name, version, copyright, build number,
  "Powered by IntelliJ Platform" strings
- Status bar widgets: memory indicator, problems widget,
  git-branch widget, line/column widget, encoding widget,
  line-separator widget — most are meaningless for a terminal
- Action toolbar: Run, Debug, Stop, Build, Profile — already
  partially stripped
- Search Everywhere: already customized
- Keymap dialog: references to "IntelliJ IDEA keymap",
  "Emacs keymap", "Visual Studio keymap" — all assume code
  editing
- Tool windows: Project, Structure, Database, Services,
  Problems, TODO, Bookmarks, Terminal (if the JB one exists),
  Run, Debug, Profiler, Coverage, Build
- Dialogs: New Project wizard, Import Project, Attach Project,
  Open File, File Structure popup, Recent Files, Tip of the Day
- Splash screen: IntelliJ logo, "Powered by JetBrains" strings
- Plugin dialog: "JetBrains Marketplace" tabs, "Featured",
  "Staff Picks"
- Notifications: "IntelliJ Updates Available"
- Error reporter: dialog references to JetBrains EAP
- Settings Sync dialog
- EDT thread dump dialog strings
- Help → Edit Custom Properties / Edit Custom VM Options
- Help → Change Memory Settings / Show Log in Finder wording

**Deliverable:** A spreadsheet / markdown table listing every
offender with:
- Location (menu path or class name)
- Current text / icon
- Desired state (remove / rename / replace / leave)
- Complexity (trivial / moderate / hard)

### Phase B.1 — Strip menus & action groups (1–2 days)

**What:** Extend the existing `ConchRefactoringStripper` pattern
to cover every menu and action group that doesn't belong in a
terminal product. Targets include:

- `MainMenu` → strip `Refactor`, `Build`, `Run`, `Tools`,
  `VCS`, `Code`, `Navigate`, `Analyze` submenus entirely
- `MainToolbar` → strip remaining run/debug/build buttons
- `EditorPopupMenu`, `ConsoleEditorPopupMenu` — probably
  unused but worth auditing
- `ProjectViewPopupMenu`, `ProjectViewPopupMenuRunGroup` —
  project-view menus
- `FileChooserToolbar` — if the file chooser is even reachable
- Right-click menus attached to code editors (if any survive)
- Generate action group (`alt+insert`)
- Introduce action group

**Method:** `ActionManager.unregisterAction(id)` in an
`AppLifecycleListener`, same as existing strippers. List of ids
maintained in a single constants class for auditability.

**Risk:** Silent `ActionNotFoundException` when downstream code
looks up an action id we removed. Mitigation: wrap strips in
try/catch, log at INFO, continue.

### Phase B.2 — Strip settings pages (1 day)

**What:** The Preferences window has ~50 Configurable pages, most
meaningless for a terminal. Audit every top-level configurable and
strip the ones that reference editor/code/debug/build/VCS concepts.

**Targets:**

- Appearance & Behavior → Menus and Toolbars (actually useful,
  may keep if customization still works)
- Appearance & Behavior → System Settings → autosave, recent
  projects (rename to "Recent Workspaces" or remove)
- Appearance & Behavior → Notifications (keep, rebrand)
- Keymap (keep, audit default bindings)
- Editor / Code Style / File Types / Inspections / Live Templates —
  strip all
- Plugins (keep, heavily customize — see Track C)
- Version Control — strip
- Build, Execution, Deployment — strip entirely
- Languages & Frameworks — strip entirely
- Tools → Terminal — rename / merge with Conch Terminal settings
- Tools → SSH Configurations — Conch has its own
- Advanced Settings — audit, keep the ones that matter

**Method:** Extend the existing `ConchEditorSettingsStripper`.
Register stripped configurables by id.

**Leftover:** A Conch Preferences window with these top-level
pages: `Appearance`, `Terminal`, `SSH Hosts`, `Vault`,
`Tunnels`, `SFTP`, `Keymap`, `Plugins`, `Notifications`,
`Updates`. That's it.

### Phase B.3 — Strip tool windows (half day)

**What:** The default tool window strip has `Project`,
`Structure`, `Services`, `Database`, `Problems`, `TODO`,
`Bookmarks`, `Terminal`, `Commit`, `Git`, `Debug`, `Run`,
`Profiler`, `Coverage`, `Build`, `Maven`, `Gradle`, `Messages`.

Keep: `Conch Terminal`, `Hosts`, `Tunnels`, `Vault`,
`Conch SFTP`.

Strip everything else via
`ToolWindowManager.unregisterToolWindow(id)` in a listener.
Some tool windows only register on project open — need to hook
`ProjectActivity` or similar.

### Phase B.4 — Welcome frame + splash + about (1 day)

**What:**

- **Welcome frame:** already partially handled by
  `ConchHomeDirNoProjectHandler`. Verify behavior: user launches
  Conch, lands on a workspace view with Hosts / Terminal tool
  windows visible, no "Create New Project" button anywhere.
- **Splash:** replace with Conch logo. `customization/resources/
  conch_logo.png` already exists. Verify it's picked up by
  `ApplicationInfoImpl.loadSplashUrl()`.
- **About dialog:** override the About action. Replace with a
  Conch-specific dialog: product name, version, build, SHA, Conch
  logo, link to GitHub. Strip "Powered by IntelliJ Platform",
  JetBrains copyright, plugin list. Plugin list may be worth
  keeping (as "Bundled plugins") if we're careful about branding.
- **Tip of the day:** already disabled via
  `-Dide.no.tips.dialog=true`. Verify.

### Phase B.5 — Status bar (half day)

**What:** Current status bar includes widgets for memory, problems,
indentation, encoding, line separator, read-only mode, cursor
position, Git branch, IDE inspection profile. Most are irrelevant.

Keep: clock / inactivity timer widget (vault lock), notification
indicator, update indicator, SSH session count (new widget).

Strip everything else via `StatusBar.removeWidget(id)` at startup.

### Phase B.6 — Vocabulary pass: project → workspace (1 day)

**What:** Every user-facing string that says "Project" should say
"Workspace" (or be removed). Conch already has a `WorkspaceManager`
service, so the model exists — it's just that 90% of the UI still
calls the underlying thing a Project because the platform class is
`Project`.

**Method:**
1. Grep user-visible `*.properties` and `*.xml` bundles for
   `Project`, `project`.
2. For each hit, determine if it's reachable in the stripped-down
   Conch UI.
3. Override via `bundle-xx.properties` replacements in Conch's
   `customization` module. IntelliJ supports resource bundle
   overrides per product.
4. For strings that aren't in bundles (hardcoded), we can't easily
   fix them. Either accept the leak or strip the containing feature.

**Deliverable:** Every user-reachable string says "workspace",
"terminal", "host", "session" — never "project", "module", "IDE".

### Phase B.7 — Icon pass (half day)

**What:** IntelliJ `AllIcons` includes ~3000 icons, most with a
code / debugger / build aesthetic. Conch should use a smaller
curated set.

For tool window icons, action icons, and dialog icons already in
use, audit whether they "look like a terminal product" or "look
like an IDE". Replace with Conch-styled icons where jarring.

This is lower priority than the text pass — icons are more
forgivable because users don't read them the way they read
"Refactor this symbol".

### Phase B.8 — Keymap audit (1 day)

**What:** IntelliJ's default keymap binds dozens of shortcuts to
actions that no longer exist in Conch (`Cmd+B` to "Go to
Declaration", `Cmd+Shift+F` to "Find in Files", etc). After
Track B's action-stripping, these bindings point at nothing —
fine, but they *also* reserve the shortcut, preventing Conch
from binding to something else.

**Method:**
1. Dump the effective keymap: `KeymapManager.getActiveKeymap().getActionIds()`.
2. Cross-reference with actions still registered after stripping.
3. Unbind shortcuts that point at stripped actions.
4. Bind those shortcuts to Conch actions where useful.

**Examples of shortcuts to reclaim:**
- `Cmd+Shift+N` — Go to File → Conch: new terminal tab
- `Cmd+O` — Open Project → Conch: (free)
- `Cmd+Shift+A` — Find Action → keep, reframe for Conch action list

### Phase B.9 — Exit criteria

A fresh user on macOS launches Conch. They see:
- Conch splash
- A workspace window with Conch Terminal, Hosts, Tunnels, SFTP,
  Vault tool windows visible
- A main menu reading: `Conch Workbench | File | Edit | View |
  Window | Help` — nothing else
- File menu items: New Terminal, New SSH Session, Open Workspace,
  Close Workspace, Preferences, Lock Vault, Quit. Nothing else.
- Preferences shows ~10 top-level categories, all Conch-themed
- About dialog is Conch-branded, no JetBrains references
- Nowhere does the word "IntelliJ", "IDE", "project", "module",
  "refactor", "build", or "debug" appear in any user-visible
  string

### Dependencies & prerequisites

- **Depends on Track A measurement** if we're going to strip
  things that are also memory hogs (some menu actions live in
  heavy plugins).
- **Depends on Track C updater** for the version string in the
  About dialog.
- Phase B.0 audit is a hard blocker on every subsequent phase.

### Known risks

- **Upstream API churn in bundle keys:** when intellij-community
  renames a resource bundle key, our override silently stops
  working. Need a regression test that launches Conch and greps
  the rendered UI for banned words.
- **Platform assumes Project exists:** deleting
  "Create New Project" from the welcome frame may break code
  paths that expect `Project` to be non-null. Workspace overlay
  (`ConchHomeDirNoProjectHandler`) already handles this but may
  have gaps.

---

## Track C — Release pipeline

### Goal

From `make conch` (dev build) to "user downloads a signed DMG from
a GitHub release, installs, auto-updates, installs plugins from
the Conch plugin registry" — the full shippable flow.

### Phase C.0 — Prerequisites (external, not code)

Before any code: obtain the infrastructure required for signed
distribution.

- **Apple Developer account** ($99/yr) for macOS codesigning and
  notarization. Required for any Mac install that doesn't
  produce a scary Gatekeeper dialog.
- **Windows code-signing certificate** (~$80–$300/yr, e.g. from
  Sectigo or DigiCert). Required for any Windows install that
  doesn't warn "Unknown publisher". EV certs remove SmartScreen
  warnings; OV certs slowly build trust.
- **Ed25519 signing keypair**, generated offline, stored in a
  hardware token or at minimum a password-protected file outside
  the repo. Used for signing update manifests and plugin index.
- **Domain name** (e.g. `conch.dev`, `conchworkbench.io`). Can
  defer until static hosts become insufficient.

### Phase C.1 — App-bundle signing + notarization (2–3 days)

**What:** Extend `ConchInstallersBuildTarget` to sign the Mac DMG
with the Apple Developer ID cert and submit it to Apple's
notarization service. Sign the Windows EXE installer with the
Authenticode cert.

**macOS pipeline:**
1. `codesign --deep --force --options runtime --sign "Developer
   ID Application: <Name> (TEAMID)" Conch.app`
2. `ditto -c -k --keepParent Conch.app Conch.zip`
3. `xcrun notarytool submit Conch.zip --keychain-profile conch
   --wait`
4. `xcrun stapler staple Conch.app`
5. Build the DMG from the stapled app
6. Codesign the DMG itself

**Windows pipeline:**
1. `signtool sign /a /fd SHA256 /tr http://timestamp.digicert.com
   /td SHA256 conch-installer.exe`

**Deliverable:** Signed, notarized installers for macOS (DMG) and
Windows (EXE), plus signed archive for Linux (tar.gz,
unsigned — no equivalent PKI for Linux).

**Risks:** Apple notarization can take 5–60 minutes; CI needs to
handle the wait gracefully. Hardened runtime requires auditing
every JNI call we make — Conch uses pty4j and potentially other
native libs; each needs entitlement review.

### Phase C.2 — GitHub release automation (1–2 days)

**What:** A GitHub Actions workflow triggered on tag push
(`v*.*.*`) that:

1. Builds all platforms (Mac arm64, Mac x64, Linux x64, Windows x64)
2. Signs each
3. Creates a GitHub release for the tag
4. Uploads all installer artifacts as release assets
5. Generates a signed `release.json` manifest:
   ```json
   {
     "version": "0.2.0",
     "build": "262.30001",
     "releaseDate": "2026-04-14",
     "releaseNotes": "...",
     "assets": {
       "mac-arm64": {"url": "...", "sha256": "..."},
       "mac-x64": {"url": "...", "sha256": "..."},
       "linux-x64": {"url": "...", "sha256": "..."},
       "win-x64": {"url": "...", "sha256": "..."}
     },
     "signature": "<ed25519 sig of the above, base64>"
   }
   ```
6. Uploads `release.json` as an asset
7. Updates `latest.json` at `https://an0nn30.github.io/conch-updates/latest.json`
   to point at the new release

**Secrets the workflow needs:**
- `APPLE_ID`, `APPLE_APP_PASSWORD`, `APPLE_TEAM_ID`
- `APPLE_CERT_P12`, `APPLE_CERT_PASSWORD` (keychain import)
- `WINDOWS_CERT_PFX`, `WINDOWS_CERT_PASSWORD`
- `ED25519_SIGNING_KEY` (hex or base64)
- `GITHUB_TOKEN` (automatic)

### Phase C.3 — In-app updater (2–3 days)

**What:** The client-side half of the update system. Conch
periodically checks `https://an0nn30.github.io/conch-updates/latest.json`,
verifies the Ed25519 signature, compares to the installed build,
and shows a notification balloon on a match.

**Components:**
- `ConchUpdateChecker` application service that fetches and
  parses the manifest.
- `ConchUpdateManifest` record + Gson adapter.
- Ed25519 verifier using BouncyCastle (already vendored via the
  vault plugin).
- `ConchUpdateLifecycleListener` that runs the check at
  startup + every 6 hours.
- `ConchUpdateConfig` PersistentStateComponent for
  skipped-version and last-checked bookkeeping.
- Notification group `"Conch Updates"` registered in plugin.xml.
- "Download" action that opens the release page in the browser.
- Strip out the existing `UpdateCheckerService` and
  `MarketplaceRequests` background polling (they're hitting
  JetBrains servers today — visible in the current log as 403
  errors).

**v1 scope:** notification only. "Download" opens the browser.
User installs manually.

**v2 scope (later):** in-app download, background staging,
"Restart to update" prompt. Mac uses Sparkle-style app swap.
Linux uses AppImage update if we go that route. Windows uses
MSI differential update.

### Phase C.4 — Plugin registry infrastructure (1 day)

**What:** A `conch-plugins` GitHub repo with GitHub Pages enabled,
serving:

- `updatePlugins.xml` — the IntelliJ plugin host protocol file
- `updatePlugins.xml.sig` — Ed25519 signature over the XML
- `plugins/<id>/<version>/<id>-<version>.zip` — plugin binaries

A GitHub Actions workflow in the repo that regenerates
`updatePlugins.xml` from the contents of `plugins/` on push.

This phase is zero-code in the Conch repo; it's entirely infra.
Deliverable is a working registry URL.

### Phase C.5 — Plugin host wiring in Conch (1 day)

**What:** Point Conch at the new registry. Strip the existing
JetBrains marketplace tabs from the Plugins dialog so users don't
see "Staff Picks" / "Featured" / etc that hit JB servers and
return 403.

**Components:**
- JVM flag: `-Didea.plugin.hosts=https://an0nn30.github.io/conch-plugins/updatePlugins.xml`
- `ConchPluginHostCustomizer` listener that sets
  `UpdateSettings.getInstance().setStoredPluginHosts(...)` on
  startup.
- `ConchPluginMarketplaceStripper` listener that unregisters the
  marketplace contributors, so only "Installed" and "Available
  from host" tabs appear.

### Phase C.6 — Plugin SDK + stability (2–3 days)

**What:** Decide which Conch APIs third-party plugin authors can
depend on. Document them. Publish a plugin template repo.

**Stable API surface:**
- `com.conch.sdk.*` — interfaces already exist:
  `TerminalSessionProvider`, `CredentialProvider`, etc.
- `com.conch.core.workspace.WorkspaceManager` — for plugins that
  need workspace state.
- `com.conch.ssh.credentials.HostCredentialBundle` — for plugins
  that want SSH-style credential resolution.

**Internal API surface:**
- Everything else. Mark as `@ApiStatus.Internal`.

**Deliverable:**
- `docs/plugin-authors.md` — "How to write a Conch plugin",
  covering the bundle format, dependencies, versioning, signing,
  submission to the registry.
- `conch-plugin-template` repo — a minimal sample plugin with a
  `build.gradle.kts` or `BUILD.bazel`, example `plugin.xml`,
  example Conch SDK usage, CI workflow that builds + signs +
  publishes to the registry.

### Phase C.7 — Plugin signing + client verification (1–2 days)

**What:** Extend the Ed25519 signing infrastructure from Phase C.2
to cover plugins. Each plugin zip is signed by either (a) the
central Conch team's key, or (b) a plugin-author key that's
registered in a central trusted-keys file.

**v1:** central key only. Conch team signs every plugin before
publishing. Trusted-key list is one entry. Simplest possible.

**v2:** delegated signing. Each plugin author generates their
own keypair, publishes the public half in their plugin metadata,
and signs their zips. Conch verifies against a per-plugin pinned
key. Harder but decentralized.

**Client verification:**
- On plugin install, download the zip and the `.sig` alongside
  it.
- Verify signature against the registered trusted key.
- Reject install on mismatch with a scary dialog.

### Phase C.8 — Custom Plugins dialog (2–3 days) — DEFERRED

**What:** Replace IntelliJ's Plugins `Configurable` with a
Conch-themed one that only shows:
- Installed (with update available markers)
- Available from host (from `updatePlugins.xml`)

No "Marketplace", "Featured", "Staff Picks", JetBrains branding.

Defer this until after the first public release. The default
dialog is ugly but functional; stripping it is not on the
critical path.

### Phase C.9 — First public release (1 day)

**What:** Tag `v0.1.0`, push, watch the release pipeline run,
verify all artifacts are signed + notarized, smoke-test the
updater by installing `v0.1.0` then pushing a `v0.1.1` and
confirming the notification fires.

**Deliverable:**
- Public GitHub release with signed installers for mac-arm64 /
  mac-x64 / linux-x64 / win-x64
- Public `latest.json` served from GitHub Pages
- Public `updatePlugins.xml` served from the plugin registry
- README.md in the Conch repo with download instructions
- A landing page (even a single static HTML) at `conch.dev` or
  the github.io subdomain, with download buttons per platform
- Release notes for the first public version

### Dependencies & prerequisites

- **C.0 is a hard gate on everything else in this track.** Without
  signing certs, we can't ship anything a user would willingly
  install.
- **C.1 depends on Apple / Windows cert procurement**, which can
  take 1–3 weeks elapsed time (verification, DUNS number for
  Windows cert, etc). Start this first.
- **C.3 (updater) depends on C.2 (release pipeline) having
  produced at least one signed release** to test against.
- **C.4 and C.5 are independent of C.1/C.2** — the plugin
  registry can be built and tested without the app signing
  story being done.
- **C.6 depends on Track B** being far enough along that the
  stable API surface is stable. Publishing a plugin SDK before
  we know what "Conch plugin" means is premature.

### Known risks

- **Cert procurement delays:** Apple dev account approval can
  take days; Windows cert OV verification can take a week.
  Start early.
- **Notarization breaking changes:** Apple periodically tightens
  hardened runtime requirements. A future macOS release may
  reject artifacts that notarize today. Mitigation: maintain a
  notarization smoke test that runs weekly.
- **GitHub Pages limits:** 100 GB/month bandwidth, 10 builds/hr.
  Adequate for early release but not for scale. Upgrade path is
  Cloudflare R2 + custom domain.
- **Plugin signing key compromise:** if the Ed25519 private key
  leaks, every Conch install needs to rotate to a new pinned
  key, which requires shipping a Conch update. Mitigation: key
  lives in a hardware token (YubiKey) or at minimum a passphrase-
  protected file on an offline machine.

---

## Cross-track dependencies

| Dependency | From | To | Why |
|---|---|---|---|
| Track A measurement | A.0 | B.3 | Strip tool windows informed by what's costing memory |
| Stripped IDE surface | Track B | C.6 | Plugin SDK docs depend on knowing what APIs are public |
| Signed releases | C.2 | Track A regression tests | A CI job that launches Conch and measures RSS needs a reproducible artifact |
| Updater | C.3 | Public launch | Can't ship without a way to push fixes |
| Cert procurement | C.0 | C.1 | Physical blocker |

## Suggested sequencing

**Week 1:**
- A.0 (measure)
- C.0 (start Apple / Windows cert procurement — long-lead external)
- B.0 (audit — parallel, no blocker)

**Week 2:**
- A.1 (drop bundled language plugins)
- A.2 (product content descriptor pruning)
- B.1 (strip menus)
- B.2 (strip settings pages)
- C.4 (plugin registry infrastructure — can happen in parallel)

**Week 3:**
- A.3 (stub heavy platform services — biggest win, biggest risk)
- B.3, B.4, B.5, B.6 (tool windows, welcome, status bar, vocabulary)

**Week 4:**
- A.4 / A.5 / A.6 (measure, tune, iterate)
- C.1 (app signing — assuming certs arrived)
- C.2 (release pipeline)
- B.8 (keymap audit)

**Week 5:**
- C.3 (updater)
- C.5 (plugin host wiring)
- B.7 (icon pass)
- First smoke release to internal testing

**Week 6+:**
- C.6 (plugin SDK docs + template repo)
- C.7 (plugin signing)
- Bug fixing, pre-release polish
- **Public v0.1.0 release**

Total: ~6 weeks of focused solo work, assuming no unexpected
blockers. Cert procurement and notarization pipeline can easily
add 1–2 weeks of elapsed time not counted as work days.

---

## Open questions

Things to decide before starting:

1. **Linux distribution strategy.** AppImage, tar.gz, Flatpak,
   DEB/RPM, or Snap? Each has different update mechanisms and
   user expectations. AppImage has the nicest updater story
   (`AppImageUpdate`) but some users dislike it. tar.gz is the
   simplest but offers no auto-update path. **Recommendation:**
   ship tar.gz for v0.1.0 and defer the packaging debate.
2. **Windows installer format.** MSI, NSIS EXE, or MSIX? MSIX is
   the modern Microsoft choice but has sandboxing caveats that
   may conflict with pty4j / JNI. NSIS is ugly but works.
   **Recommendation:** NSIS for v0.1.0.
3. **Update channels:** stable vs. EAP vs. nightly. For v0.1.0,
   one channel (stable). Split later when there's enough release
   volume to matter.
4. **Anonymous telemetry.** Do we ever collect anything from
   users? JetBrains does (FUS). For Conch, the intentional
   stance right now is "no telemetry, ever". Document this in
   the privacy policy when C.9 lands a landing page.
5. **Plugin submission workflow.** Pull request against
   `conch-plugins` repo? Email to a maintainer? Auto-publish
   from per-plugin repos? **Recommendation:** PR review for
   v1, automate later if volume grows.
6. **Name.** "Conch Workbench" is the current product name. The
   executable is `conch`. The GitHub repo is `conch_workbench`.
   The domain could be `conch.dev` (taken?), `conchworkbench.io`,
   `conch.sh` (taken?), etc. **Recommendation:** lock in the
   name before the first public release; any later change is
   a migration.
