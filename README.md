# Conch Workbench

A terminal-driven workstation built on top of the IntelliJ Platform — a stripped-down IDE that boots straight to terminals, with workspace persistence, CWD-aware file explorer, command palette, and a JediTerm-based emulator embedded in the editor area.

## What's in this repo

```
conch_workbench/
├── BUILD.bazel              # Bazel target for the conch product
├── Makefile                 # Build & run helpers (delegates to bazel in intellij root)
├── INTELLIJ_REF             # Pinned intellij-community SHA
├── setup.sh                 # One-time bootstrap (shallow-clones intellij + symlinks)
├── core/                    # Plugin code: terminal editor, workspace, palette, mini-window
├── customization/           # Branding, ApplicationInfo, plugin XML
├── sdk/                     # Plugin SDK interfaces (TerminalSessionProvider, etc.)
└── docs/                    # Design notes
```

This repo contains *only* Conch source. It's built by checking it out alongside an [intellij-community](https://github.com/JetBrains/intellij-community) tree and symlinking it into `intellij-community/conch/`. Bazel reads the source from disk and treats Conch like any other in-tree IntelliJ module.

## The git landscape

Conch follows the same model Google uses for Android Studio: **two independent repos, joined at the working tree**.

```
~/projects/
├── conch_workbench/                 ← THIS repo (your code)
│   └── ...
└── intellij-community/              ← upstream from JetBrains, vendored
    ├── platform/                    ← intellij sources
    ├── plugins/                     ← intellij sources
    └── conch  →  ../conch_workbench  (symlink, created by setup.sh)
```

- **conch_workbench** is your code. Push it, branch it, version it normally.
- **intellij-community** is treated as a vendored snapshot. You don't push to it. You shallow-clone it once at a known-good SHA (the one stored in `INTELLIJ_REF`) and only update when you decide to take a new "drop" from upstream.
- The **symlink** is the only thing connecting them. Bazel doesn't know or care that `intellij-community/conch` is a symlink — it sees the files on disk.

### Why this layout?

- **Clean history.** This repo only contains commits that touched Conch — no IntelliJ noise.
- **Small clones.** New contributors fetch ~1-2 GB (shallow intellij clone) instead of ~30 GB.
- **Updates on your schedule.** Bumping `INTELLIJ_REF` is a one-line change. Until you bump it, your build is reproducible against a fixed upstream.
- **No fork sprawl.** You're not maintaining a fork of intellij-community on GitHub. Upstream stays upstream.

This is conceptually identical to Android Studio's split between [`platform/tools/idea`](https://android.googlesource.com/platform/tools/idea/) (the vendored intellij) and [`platform/tools/adt/idea`](https://android.googlesource.com/platform/tools/adt/idea/) (the Android-specific code). They use Google's `repo` tool to glue them together; we use a single symlink.

## Quick start

Requirements: macOS or Linux, Bash, Git, ~5 GB free disk space.

```bash
# 1. Clone this repo wherever you keep projects.
git clone https://github.com/an0nn30/conch_workbench ~/projects/conch_workbench
cd ~/projects/conch_workbench

# 2. Bootstrap: shallow-clones intellij-community next door and symlinks
#    this repo into intellij-community/conch.
./setup.sh

# 3. Build and run.
make conch
```

The first build takes a while (Bazel compiles a lot of intellij modules from scratch). Subsequent builds are incremental and fast.

### Custom intellij location

By default, `setup.sh` creates `intellij-community/` as a sibling of `conch_workbench/`. To put it elsewhere:

```bash
./setup.sh ~/some/other/path/intellij-community
```

`make conch` will then need `INTELLIJ_ROOT` set:

```bash
INTELLIJ_ROOT=~/some/other/path/intellij-community make conch
```

## Updating the pinned intellij-community ref

When JetBrains ships a new IntelliJ release that has fixes you want, bump `INTELLIJ_REF` and re-pull:

```bash
# Find the SHA you want — e.g. a release tag from
# https://github.com/JetBrains/intellij-community/tags
NEW_REF=<sha-or-tag>

echo "$NEW_REF" > INTELLIJ_REF

# Re-fetch in your existing intellij-community checkout
cd ../intellij-community
git fetch --depth 1 origin "$NEW_REF"
git checkout FETCH_HEAD

# Verify Conch still builds against the new ref
cd ../conch_workbench
make conch-build

# Commit the bump if it works
git add INTELLIJ_REF
git commit -m "chore: bump intellij-community to <ref>"
```

If the build breaks because of an upstream API change, fix it in this repo (the same way you'd patch any other dependency upgrade), commit the fix and the bump together.

## Make targets

| Target            | What it does                                                |
| ----------------- | ----------------------------------------------------------- |
| `make conch`      | Build and run Conch (opens `$HOME` as the workspace root).  |
| `make conch-build`| Build only — useful for CI or quick "does it compile" runs. |
| `make conch-clean`| `bazel clean` in the intellij tree.                         |

All targets resolve `INTELLIJ_ROOT` from this Makefile's location (`..`). Override the env var if your layout differs.

## License

TBD.
