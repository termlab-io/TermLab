# TermLab

A terminal-driven workstation built on top of the IntelliJ Platform — a stripped-down IDE that boots straight to terminals, with workspace persistence, CWD-aware file explorer, command palette, and a JediTerm-based emulator embedded in the editor area.

## What's in this repo

```
termlab_workbench/
├── BUILD.bazel              # Bazel target for the termlab product
├── Makefile                 # Build & run helpers (delegates to bazel in intellij root)
├── INTELLIJ_REF             # Pinned intellij-community SHA
├── setup.sh                 # One-time bootstrap (shallow-clones intellij + symlinks)
├── core/                    # Plugin code: terminal editor, workspace, palette, mini-window
├── customization/           # Branding, ApplicationInfo, plugin XML
├── sdk/                     # Plugin SDK interfaces (TerminalSessionProvider, etc.)
└── docs/                    # Design notes
```

This repo contains *only* TermLab source. It's built by checking it out alongside an [intellij-community](https://github.com/JetBrains/intellij-community) tree and symlinking it into `intellij-community/termlab/`. Bazel reads the source from disk and treats TermLab like any other in-tree IntelliJ module.

## The git landscape

TermLab follows the same model Google uses for Android Studio: **two independent repos, joined at the working tree**.

```
~/projects/
├── termlab_workbench/                 ← THIS repo (your code)
│   └── ...
└── intellij-community/              ← upstream from JetBrains, vendored
    ├── platform/                    ← intellij sources
    ├── plugins/                     ← intellij sources
    └── termlab  →  ../termlab_workbench  (symlink, created by setup.sh)
```

- **termlab_workbench** is your code. Push it, branch it, version it normally.
- **intellij-community** is treated as a vendored snapshot. You don't push to it. You shallow-clone it once at a known-good SHA (the one stored in `INTELLIJ_REF`) and only update when you decide to take a new "drop" from upstream.
- The **symlink** is the only thing connecting them. Bazel doesn't know or care that `intellij-community/termlab` is a symlink — it sees the files on disk.

### Why this layout?

- **Clean history.** This repo only contains commits that touched TermLab — no IntelliJ noise.
- **Small clones.** New contributors fetch ~1-2 GB (shallow intellij clone) instead of ~30 GB.
- **Updates on your schedule.** Bumping `INTELLIJ_REF` is a one-line change. Until you bump it, your build is reproducible against a fixed upstream.
- **No fork sprawl.** You're not maintaining a fork of intellij-community on GitHub. Upstream stays upstream.

This is conceptually identical to Android Studio's split between [`platform/tools/idea`](https://android.googlesource.com/platform/tools/idea/) (the vendored intellij) and [`platform/tools/adt/idea`](https://android.googlesource.com/platform/tools/adt/idea/) (the Android-specific code). They use Google's `repo` tool to glue them together; we use a single symlink.

## Quick start

Requirements: macOS or Linux, Bash, Git, ~5 GB free disk space.

```bash
# 1. Clone this repo wherever you keep projects.
git clone https://github.com/an0nn30/termlab_workbench ~/projects/termlab_workbench
cd ~/projects/termlab_workbench

# 2. Bootstrap: shallow-clones intellij-community next door and symlinks
#    this repo into intellij-community/termlab.
./setup.sh

# 3. Build and run.
make termlab
```

The first build takes a while (Bazel compiles a lot of intellij modules from scratch). Subsequent builds are incremental and fast.

### Custom intellij location

By default, `setup.sh` creates `intellij-community/` as a sibling of `termlab_workbench/`. To put it elsewhere:

```bash
./setup.sh ~/some/other/path/intellij-community
```

`make termlab` will then need `INTELLIJ_ROOT` set:

```bash
INTELLIJ_ROOT=~/some/other/path/intellij-community make termlab
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

# Verify TermLab still builds against the new ref
cd ../termlab_workbench
make termlab-build

# Commit the bump if it works
git add INTELLIJ_REF
git commit -m "chore: bump intellij-community to <ref>"
```

If the build breaks because of an upstream API change, fix it in this repo (the same way you'd patch any other dependency upgrade), commit the fix and the bump together.

## Make targets

| Target            | What it does                                                |
| ----------------- | ----------------------------------------------------------- |
| `make termlab`      | Build and run TermLab (opens `$HOME` as workspace root by default). |
| `make termlab-build`| Build only — useful for CI or quick "does it compile" runs. |
| `make termlab-clean`| `bazel clean` in the intellij tree.                         |
| `make termlab-perf-benchmark` | Launch TermLab, sample idle RSS/CPU in a dedicated local perf workspace, and write CSV+JSON+env artifacts under `perf-results/`. |
| `make termlab-perf-budget` | Read the latest benchmark summary and print Stage A/Stage B budget status. |

All targets resolve `INTELLIJ_ROOT` from this Makefile's location (`..`). Override the env var if your layout differs.
You can also override the workspace root explicitly with `TERMLAB_WORKSPACE=/path make termlab`.

### Performance benchmarking loop

TermLab includes a reproducible idle-footprint harness for the dev path (`make termlab`):

```bash
make termlab-perf-benchmark
make termlab-perf-budget
```

The benchmark target:

- launches TermLab through `make termlab`
- uses `$(WORKBENCH_DIR)/.perf-workspace` as the default benchmark workspace (override with `TERMLAB_PERF_WORKSPACE=/path`)
- waits for warmup, then samples process RSS/CPU at a fixed interval
- captures `jcmd` snapshots (`VM.native_memory`, `GC.heap_info`, `VM.flags`)
- writes artifacts to `perf-results/<timestamp>/`:
  - `metrics.csv`
  - `summary.json`
  - `summary.env`
  - `jcmd-thread-print.txt`
  - `jcmd-class-histogram.txt`
  - `termlab.log`

It also updates:

- `perf-results/latest_summary.json`
- `perf-results/latest_summary.env`
- `perf-results/latest_metrics.csv`

Default gate policy:

- Stage A: `<=200MB` average RSS and `<=1%` average CPU
- Stage B (stretch): `<=100MB` average RSS and `<=1%` average CPU

You can tune the run without editing scripts:

```bash
make termlab-perf-benchmark \
  TERMLAB_PERF_WORKSPACE=/tmp/termlab-bench-workspace \
  TERMLAB_PERF_WARMUP_SEC=120 \
  TERMLAB_PERF_SAMPLE_SEC=5 \
  TERMLAB_PERF_DURATION_SEC=600 \
  TERMLAB_PERF_OUT=/tmp/termlab-perf
```

## Running and debugging from IntelliJ IDEA

`setup.sh` automatically wires TermLab into the IntelliJ project that lives in `intellij-community/.idea/`, so you can run and debug from the IDE with breakpoints in termlab sources.

It does two things:

1. Drops `TermLab.xml` into `intellij-community/.idea/runConfigurations/` — an `Application` run config that boots `com.intellij.idea.Main` with `-Didea.platform.prefix=TermLab` and the same JVM flags `make termlab` uses.
2. Patches `intellij-community/.idea/modules.xml` to register the four termlab `.iml` files (`intellij.termlab.{main,core,sdk,customization}`) so IntelliJ can resolve their dependencies and compile them.

To use it:

```bash
# Open the intellij-community tree as a project (NOT the termlab_workbench dir).
# IntelliJ IDEA → Open → ~/projects/intellij-community
```

After indexing, the **TermLab** run configuration appears in the run dropdown. ▶ runs it; 🐞 debugs it. Set breakpoints anywhere under `termlab/` (the symlinked tree) and they'll fire.

> **Note about `intellij-community/.idea/modules.xml`:** the patch shows up as a "modified" tracked file in the upstream tree. That's expected — your local intellij-community is a vendored snapshot and never gets pushed anywhere, so the local diff is harmless. If you want to hide it from `git status`, run `git -C ~/projects/intellij-community update-index --skip-worktree .idea/modules.xml`. Note that this also blocks future upstream changes to that file from being pulled cleanly; just toggle it back with `--no-skip-worktree` before running `git pull`.

If you ever need to re-install the IDE config without re-running full setup:

```bash
./scripts/install-idea-config.sh
```

It's idempotent.

## License

TBD.
