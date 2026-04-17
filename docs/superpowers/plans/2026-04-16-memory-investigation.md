# TermLab Memory Investigation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **This plan is investigation-shaped, not feature-shaped.** The deliverable is a written report (`docs/memory-investigation/2026-04-16-report.md`) plus a heap-dump artifact directory. Most steps produce *evidence*, not code. Implementation of the recommendations is explicitly out of scope — that belongs in a follow-up plan.

**Goal:** Determine, with direct heap/profiling evidence, why TermLab starts at ~500 MB RSS and grows to 1–1.2 GB after use; classify every top retainer as TermLab-owned / IntelliJ-platform / bundled-plugin / JVM-native; and produce a ranked, actionable recommendation list to reach the budget of ≤200 MB cold-start and ≤500 MB under active use.

**Architecture:** A four-lens investigation. **(1) Heap snapshots** (jcmd `GC.heap_dump`) at four lifecycle points — cold-start, post-warmup, post-workload, long-idle — analyzed in Eclipse MAT for dominator-tree retainers. **(2) Allocation profiling** (JFR) across a scripted user workload to find where *growth* comes from. **(3) Native memory tracking** (JVM NMT detail) to separate heap, metaspace, code cache, thread stacks, and direct-buffer native overhead — important because RSS growth beyond `-Xmx` is native. **(4) Loaded-module audit** (idea.log + class histogram) to find IntelliJ platform services, extension points, and classes that are loaded but not used by TermLab.

**Tech Stack:** `jcmd`, `jfr`, Java Flight Recorder (JDK 25), Eclipse Memory Analyzer Tool (MAT) in batch mode, native IntelliJ `idea.log` diagnostics, IntelliJ platform sources at `~/projects/intellij-community`, TermLab BUILD.bazel for JVM flag injection, existing perf harness `scripts/perf/benchmark_idle.sh`.

**Spec / background:** `docs/plans/2026-04-14-conch-roadmap.md` §"Phase A.0 — Measure" sketches the approach; this plan makes it concretely executable.

---

## Context an executing agent needs

**Repo layout (both directories matter):**
- Workbench (this repo): `/Users/dustin/projects/conch_workbench` — product configuration, plugins, build properties.
- IntelliJ source (for root-cause lookups): `/Users/dustin/projects/intellij-community` — where all `com.intellij.*` classes live.
- IntelliJ treats TermLab as a symlinked subdir at `/Users/dustin/projects/intellij-community/termlab` (created by `setup.sh`).
- Runtime `system` dir (caches, logs): `/Users/dustin/projects/intellij-community/system/termlab/` — `log/idea.log` is the platform log.
- Runtime `config` dir: `/Users/dustin/projects/intellij-community/config/termlab/`.

**How TermLab runs in dev:** `make termlab` from the workbench root → `bazel run //termlab:termlab_run` from the intellij root. JVM flags for dev runs live in `/Users/dustin/projects/conch_workbench/BUILD.bazel` in the `termlab_run` target's `jvm_flags` list (already contains `-Xms128m`, `-Xmx1024m`, `-XX:ReservedCodeCacheSize=240m`, and product property flags). This is where diagnostic flags (`NativeMemoryTracking`, heap-dump-on-OOM, GC logging) must be added.

**Current baseline from user (to confirm with Task 2.1):** cold-start ≈ 500 MB RSS; after typical usage 1.0–1.2 GB RSS. With `-Xmx1024m`, the heap ceiling alone explains the post-usage ceiling — meaning the growth is heap-dominated, not a native leak, but this must be *verified*, not assumed.

**Targets (from user):** cold-start ≤ 200 MB RSS; post-usage ≤ 500 MB RSS.

**Authorized remediations (per user instruction):** stripping IntelliJ modules/plugins from the TermLab layout, patching TermLab code, and *forking IntelliJ source* are all on the table. Recommendations should explicitly name which of these each fix requires.

**Prior reduction work already done** (don't re-recommend these):
- `intellij.indexes.pretendNoFiles=true` — file indexing already disabled.
- `intellij.filewatcher.disabled=true` — fsnotifier already disabled.
- `ide.experimental.ui=false` — classic UI by default.
- Telemetry metric exporters zeroed out.
- `ide.open.project.view.on.startup=false`.

**Executing-agent self-awareness note:** Some steps require a human to perform UI actions in TermLab (e.g., "open an SSH session, browse SFTP, type in the terminal"). The agent cannot drive the UI. These steps are marked `[human-in-loop]` and the agent must explicitly prompt the user to perform them, wait for confirmation ("done"), then capture the dump. Do not simulate or skip these steps — the post-workload dump is the most important artifact in the investigation.

---

## Artifact Layout

All evidence goes under `docs/memory-investigation/2026-04-16/` relative to `/Users/dustin/projects/conch_workbench`:

```
docs/memory-investigation/2026-04-16/
├── README.md                     # index of what's in this dir, produced in Task 1.4
├── dumps/
│   ├── 01-cold-start.hprof       # heap dump t ≈ 5s after JVM up
│   ├── 02-post-warmup.hprof      # t = 90s, idle, forced GC first
│   ├── 03-post-workload.hprof    # after human-in-loop workload, forced GC
│   ├── 04-long-idle.hprof        # t = 30 min, idle, forced GC (leak sniff)
│   └── mat-reports/              # Eclipse MAT batch output (HTML zips)
├── jcmd/
│   ├── <checkpoint>-nmt.txt      # VM.native_memory detail scale=MB
│   ├── <checkpoint>-classhisto.txt
│   ├── <checkpoint>-threads.txt  # Thread.print -l
│   ├── <checkpoint>-classloaders.txt  # VM.classloader_stats
│   └── <checkpoint>-vmflags.txt
├── jfr/
│   └── workload.jfr              # 5-min profile recording spanning workload
├── gc/
│   └── gc.log                    # -Xlog:gc output for the whole run
├── logs/
│   └── idea.log                  # copied from system/termlab/log at end of run
└── report.md                     # final synthesis (Task 9.1)
```

---

## Phase 1 — Tooling setup

### Task 1.1: Install Eclipse MAT (batch mode) and confirm JDK tooling

**Why:** MAT's dominator tree is the only practical way to rank retainers by *retained* (not shallow) bytes across a 500+ MB heap. Batch mode lets the agent run it headless and parse the generated HTML.

**Files:** None modified. Installs to `~/tools/mat/` (user-local, not committed).

- [ ] **Step 1: Confirm JDK tooling is on PATH**

Run:
```bash
which jcmd jfr java
java -version
```
Expected: all three paths print; `java -version` shows JDK ≥ 17 (TermLab currently uses OpenJDK 25 per SDKMAN). If any is missing, stop and report — the rest of the plan can't proceed without them.

- [ ] **Step 2: Download Eclipse MAT if not already present**

Run:
```bash
mkdir -p ~/tools
test -x ~/tools/mat/MemoryAnalyzer || {
  cd /tmp
  curl -L -o mat.zip "https://eclipse.org/downloads/download.php?file=/mat/1.15.0/rcp/MemoryAnalyzer-1.15.0.20231206-macosx.cocoa.aarch64.zip&r=1&mirror_id=1"
  unzip -q mat.zip -d ~/tools/
  mv ~/tools/mat.app ~/tools/mat 2>/dev/null || true
}
ls ~/tools/mat/Contents/MacOS/ 2>/dev/null || ls ~/tools/mat/
```
Expected: a directory containing `MemoryAnalyzer` and the `ParseHeapDump.sh` script (in `mat.app/Contents/Eclipse/` on macOS, or directly in the mat dir on Linux). If the download URL 404s (Eclipse rotates mirrors), tell the user to manually download MAT 1.15+ for their platform from https://eclipse.dev/mat/downloads.php and extract to `~/tools/mat/`. Re-run the check after they confirm.

- [ ] **Step 3: Locate ParseHeapDump.sh and smoke-test**

Run:
```bash
find ~/tools/mat -name 'ParseHeapDump.sh' -type f
```
Expected: one match (typical path on macOS: `~/tools/mat/mat.app/Contents/Eclipse/ParseHeapDump.sh`). Export this as `MAT_PARSE` in the session: `export MAT_PARSE=<that-path>`. Record the path in the investigation report's "Tools" section for reproducibility.

- [ ] **Step 4: Create the artifact directory**

Run:
```bash
mkdir -p /Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/{dumps/mat-reports,jcmd,jfr,gc,logs}
```
Expected: no errors. Confirm with `ls -la`.

### Task 1.2: Add diagnostic JVM flags to BUILD.bazel

**Why:** NMT (Native Memory Tracking) can only be enabled at startup, not via jcmd after the fact. GC logging requires `-Xlog:gc*` at startup. On-OOM heap dump requires `-XX:HeapDumpPath`. These need to land in the dev-run jvm_flags so every subsequent capture has the same instrumentation baseline. Leave these in place for the duration of the investigation; a follow-up task removes them.

**Files:**
- Modify: `/Users/dustin/projects/conch_workbench/BUILD.bazel` — the `termlab_run` target's `jvm_flags` list (currently ends around line 200).

- [ ] **Step 1: Read the current jvm_flags list to find the insertion point**

Run:
```bash
grep -n 'jvm_flags' /Users/dustin/projects/conch_workbench/BUILD.bazel
```
Expected: one hit at the start of the list inside `termlab_run`. Then open the file and scroll to just before the closing `]` of that list — that's where we'll append.

- [ ] **Step 2: Append the diagnostic flags**

Insert the following block as the last entries of `termlab_run`'s `jvm_flags` (keep the trailing comma on the previous entry):

```python
        # --- Memory investigation (2026-04-16). Remove after investigation. ---
        # Native Memory Tracking. `detail` gives per-call-site attribution of
        # native allocations; required to separate heap vs. code cache vs.
        # direct buffers vs. thread stacks in NMT summaries.
        "-XX:NativeMemoryTracking=detail",
        # Dump heap on OOM so we catch runaway growth in the artifact dir
        # rather than losing it to process kill.
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=$${BUILD_WORKSPACE_DIRECTORY}/../conch_workbench/docs/memory-investigation/2026-04-16/dumps/",
        # GC log: tagged, time-stamped, rotated once at 50 MB. Read
        # post-hoc to distinguish "heap genuinely grew" from "GC was lazy".
        "-Xlog:gc*,gc+heap=debug,gc+metaspace=debug:file=$${BUILD_WORKSPACE_DIRECTORY}/../conch_workbench/docs/memory-investigation/2026-04-16/gc/gc.log:time,uptime,tags:filecount=1,filesize=50M",
        # Allow JFR to be started via jcmd without restarting (already on
        # in JDK 25 by default, but set explicitly for reproducibility).
        "-XX:+FlightRecorder",
        # Keep the current -Xmx1024m cap so "1-1.2 GB" growth reproduces.
        # Do NOT raise the heap cap during investigation — we want the
        # known bad behavior.
        # --- end memory investigation ---
```

**Important:** Do not touch `-Xmx1024m` — we want the existing growth behavior to reproduce. Do not add `-XX:+UnlockDiagnosticVMOptions -XX:+AlwaysPreTouch`; pretouch inflates cold-start RSS and would pollute the Task 2.1 reading.

- [ ] **Step 3: Rebuild to confirm the flags parse**

Run:
```bash
cd /Users/dustin/projects/conch_workbench && make termlab-build 2>&1 | tail -30
```
Expected: a successful Bazel build line ("Build completed successfully"). If the `$${BUILD_WORKSPACE_DIRECTORY}/../conch_workbench/...` path substitution fails because the conch_workbench dir is not a sibling of the build workspace, fall back to an absolute path: `/Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/dumps/` etc. Record whichever form was used in the report.

- [ ] **Step 4: Commit**

```bash
cd /Users/dustin/projects/conch_workbench
git add BUILD.bazel docs/memory-investigation/2026-04-16/
git commit -m "chore(memory): enable NMT+GC-log+OOM-dump for investigation"
```

### Task 1.3: Write the capture helper script

**Why:** Every checkpoint (cold-start, post-warmup, post-workload, long-idle) captures the same set of jcmd snapshots. Factoring this into one script keeps the evidence consistent across checkpoints and avoids transcription errors.

**Files:**
- Create: `/Users/dustin/projects/conch_workbench/scripts/perf/capture_mem_snapshot.sh`

- [ ] **Step 1: Create the helper script**

Write the file with this content:

```bash
#!/usr/bin/env bash
# capture_mem_snapshot.sh — capture a full memory-checkpoint artifact set.
# Usage: capture_mem_snapshot.sh <checkpoint-label> [--gc-before]
# Produces: dumps/<NN>-<label>.hprof + jcmd/<label>-*.txt files.
set -euo pipefail

LABEL="${1:?usage: capture_mem_snapshot.sh <label> [--gc-before]}"
GC_BEFORE=0
if [[ "${2:-}" == "--gc-before" ]]; then GC_BEFORE=1; fi

ART_DIR="/Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16"
DUMP_DIR="$ART_DIR/dumps"
JCMD_DIR="$ART_DIR/jcmd"
mkdir -p "$DUMP_DIR" "$JCMD_DIR"

# Find the TermLab JVM pid (same pattern as benchmark_idle.sh).
SYSTEM_PATH="/Users/dustin/projects/intellij-community/system/termlab"
PID="$(ps -axo pid=,command= \
  | awk -v p="$SYSTEM_PATH" '
      $0 ~ /com\.intellij\.idea\.Main/ && \
      $0 ~ /idea\.platform\.prefix=TermLab/ && \
      index($0,p)>0 {print $1}' \
  | tail -n 1)"

if [[ -z "$PID" ]]; then
  echo "ERROR: TermLab JVM not found. Is 'make termlab' running?" >&2
  exit 1
fi
echo "==> TermLab PID: $PID  label: $LABEL  gc_before: $GC_BEFORE"

if [[ "$GC_BEFORE" == "1" ]]; then
  echo "==> Forcing GC (System.gc) so the dump reflects surviving reachable objects"
  jcmd "$PID" GC.run >/dev/null
  sleep 2
fi

# RSS at capture time
RSS_KB="$(ps -p "$PID" -o rss= | awk '{$1=$1;print}')"
RSS_MB=$((RSS_KB/1024))
echo "==> RSS at capture: ${RSS_MB} MB"

# Dump number = next integer prefix in dumps/
N="$(ls "$DUMP_DIR"/*.hprof 2>/dev/null | wc -l | awk '{print $1+1}')"
NN="$(printf '%02d' "$N")"
HPROF="$DUMP_DIR/$NN-$LABEL.hprof"

echo "==> jcmd VM.native_memory detail scale=MB"
jcmd "$PID" VM.native_memory detail scale=MB > "$JCMD_DIR/$LABEL-nmt.txt" 2>&1 || true

echo "==> jcmd GC.class_histogram"
jcmd "$PID" GC.class_histogram > "$JCMD_DIR/$LABEL-classhisto.txt" 2>&1 || true

echo "==> jcmd Thread.print -l"
jcmd "$PID" Thread.print -l > "$JCMD_DIR/$LABEL-threads.txt" 2>&1 || true

echo "==> jcmd VM.classloader_stats"
jcmd "$PID" VM.classloader_stats > "$JCMD_DIR/$LABEL-classloaders.txt" 2>&1 || true

echo "==> jcmd VM.flags"
jcmd "$PID" VM.flags > "$JCMD_DIR/$LABEL-vmflags.txt" 2>&1 || true

echo "==> jcmd GC.heap_info"
jcmd "$PID" GC.heap_info > "$JCMD_DIR/$LABEL-heapinfo.txt" 2>&1 || true

echo "==> jcmd GC.heap_dump $HPROF (this can take 10-60s)"
jcmd "$PID" GC.heap_dump -all "$HPROF"
ls -lh "$HPROF"

# Record a one-line index entry.
INDEX="$ART_DIR/dumps/index.txt"
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ)  $NN  $LABEL  pid=$PID  rss=${RSS_MB}MB  $HPROF" >> "$INDEX"
echo "==> Done. Artifact index: $INDEX"
```

- [ ] **Step 2: Make it executable and smoke-parse**

```bash
chmod +x /Users/dustin/projects/conch_workbench/scripts/perf/capture_mem_snapshot.sh
bash -n /Users/dustin/projects/conch_workbench/scripts/perf/capture_mem_snapshot.sh
```
Expected: no output from `bash -n` (syntax OK).

- [ ] **Step 3: Commit**

```bash
cd /Users/dustin/projects/conch_workbench
git add scripts/perf/capture_mem_snapshot.sh
git commit -m "chore(perf): add capture_mem_snapshot helper for memory investigation"
```

### Task 1.4: Write the artifact directory README

**Why:** Anyone reading this in 6 months (including the original user) needs to know which dump corresponds to which state without re-reading this plan.

**Files:**
- Create: `/Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/README.md`

- [ ] **Step 1: Write the README**

```markdown
# TermLab Memory Investigation — 2026-04-16

Artifacts for the investigation planned in `docs/superpowers/plans/2026-04-16-memory-investigation.md`.

## Checkpoints

| # | Label           | When                                        | GC forced first? |
|---|-----------------|---------------------------------------------|------------------|
| 1 | cold-start      | ~5s after JVM up, splash visible            | No               |
| 2 | post-warmup     | t = 90s, welcome screen idle                | Yes              |
| 3 | post-workload   | after human-in-loop scripted workload       | Yes              |
| 4 | long-idle       | t = 30 min, idle after post-workload        | Yes              |

## Directories

- `dumps/`       — `.hprof` heap dumps + `index.txt` + MAT batch output under `mat-reports/`
- `jcmd/`        — per-checkpoint NMT / class histogram / thread / classloader / heap-info snapshots
- `jfr/`         — JFR recording(s), analyze with `jfr print` or JDK Mission Control
- `gc/gc.log`    — GC log for the whole run (rolled at 50 MB)
- `logs/idea.log` — copy of TermLab platform log taken at end of run
- `report.md`    — investigation synthesis + recommendations (the deliverable)

## Reproducing

1. Ensure the diagnostic JVM flags are active in `BUILD.bazel` (see Task 1.2 of the plan).
2. `make termlab` in a terminal that can stay open.
3. At each checkpoint, run: `scripts/perf/capture_mem_snapshot.sh <label> [--gc-before]`.
4. For checkpoint 3 (post-workload): perform the workload checklist in `report.md` §"Workload", then capture.
```

- [ ] **Step 2: Commit**

```bash
cd /Users/dustin/projects/conch_workbench
git add docs/memory-investigation/2026-04-16/README.md
git commit -m "docs(memory): add memory-investigation artifact README"
```

---

## Phase 2 — Baseline captures (the four checkpoints)

All four checkpoints run in the same TermLab session. Do **not** restart TermLab between checkpoints 2, 3, and 4 — that would reset the heap and we'd lose the growth signal.

### Task 2.1: Checkpoint 1 — cold-start

**Why:** Establishes the floor. The user reports ~500 MB RSS at cold-start. This dump tells us *what's already in the heap* by the time the splash is dismissed — almost entirely loaded platform classes, services, eager ApplicationComponents, and extension-point registrations.

- [ ] **Step 1: Start TermLab**

Run in one terminal (leave open for the rest of Phase 2):
```bash
cd /Users/dustin/projects/conch_workbench && make termlab
```
Watch the log and wait for the splash to disappear and the welcome/empty frame to appear. Do **not** open anything yet.

- [ ] **Step 2: Capture cold-start snapshot (no forced GC)**

In a second terminal:
```bash
cd /Users/dustin/projects/conch_workbench
scripts/perf/capture_mem_snapshot.sh cold-start
```
Expected: prints `TermLab PID: <n>`, writes `dumps/01-cold-start.hprof` plus five `jcmd/cold-start-*.txt` files. Dump size should be roughly 250–500 MB. If the script reports "TermLab JVM not found", the pid-detection pattern didn't match — check `ps -axo command= | grep TermLab` manually and either wait longer for startup or adjust the pattern.

- [ ] **Step 3: Note the starting RSS**

Record the RSS printed by the capture script in `docs/memory-investigation/2026-04-16/report.md` under `## Observations / Checkpoint 1`. (The report file will be filled in by Task 9.1; for now just append a `- cold-start RSS: <N> MB` bullet so the number isn't lost.)

### Task 2.2: Checkpoint 2 — post-warmup idle (GC-first)

**Why:** 90 seconds of idle is enough for lazy application components to finish initializing and for the first few GC cycles to run. We force a GC so the dump shows *surviving reachable* objects, not transient allocations waiting to be collected. Delta from checkpoint 1 reveals "what loads after splash".

- [ ] **Step 1: Wait 90s, then capture with forced GC**

```bash
# Wait from the moment TermLab became visible (approximate)
sleep 90
cd /Users/dustin/projects/conch_workbench
scripts/perf/capture_mem_snapshot.sh post-warmup --gc-before
```
Expected: `dumps/02-post-warmup.hprof` written. RSS may *drop* slightly from cold-start because of GC; that's expected.

### Task 2.3: Checkpoint 3 — post-workload (human-in-loop)

**Why:** This is the most important dump. It captures the state after realistic usage that caused the 1.0–1.2 GB growth the user reported. The dominator-tree diff between this dump and checkpoint 2 is the single strongest signal for "what grows in TermLab".

- [ ] **Step 1: `[human-in-loop]` Ask the user to perform the workload**

Post this exact message to the user and block until they reply "done":

> I need you to exercise TermLab while it's running so we can capture a post-workload heap dump. Please do the following (takes ~5 minutes). Do not close the window when finished.
>
> 1. Open an SSH session to any host (real or localhost). Keep it open.
> 2. Run a few commands in the terminal (`ls -la`, `cat /etc/hosts`, scroll some output). Scroll up and down in the terminal history.
> 3. Open the SFTP dual-pane view. Navigate a couple of directories on both sides. Open one remote file in the editor (preview is fine).
> 4. Open the command palette (⌘⇧A or ⌘K), type a few queries, and cancel.
> 5. Open Settings, click through two or three pages, close.
> 6. Open the Vault tool window and view one stored credential.
> 7. Reply "done" here when finished.

Wait for "done". Do not proceed until the user confirms.

- [ ] **Step 2: Capture post-workload snapshot with forced GC**

```bash
cd /Users/dustin/projects/conch_workbench
scripts/perf/capture_mem_snapshot.sh post-workload --gc-before
```
Expected: `dumps/03-post-workload.hprof` written. RSS should now be close to the 1.0–1.2 GB the user reported (within ±100 MB depending on GC timing).

### Task 2.4: Checkpoint 4 — long-idle (leak sniff)

**Why:** If heap is still growing 30 minutes *after* the user stopped interacting, that indicates a background leak (accumulating listeners, not-disposed sessions, growing caches). If heap *shrinks* or holds steady relative to checkpoint 3, growth is workload-driven, not leaky.

- [ ] **Step 1: `[human-in-loop]` Ask the user to leave TermLab alone for 30 minutes**

Post this message:

> Please leave TermLab idle (window can be backgrounded, but don't close it) for 30 minutes. Don't click in it, don't open anything. I'll capture a long-idle snapshot automatically after the timer. Reply "ok" to acknowledge, and I'll start the timer.

Wait for "ok". Then:

```bash
sleep 1800
cd /Users/dustin/projects/conch_workbench
scripts/perf/capture_mem_snapshot.sh long-idle --gc-before
```
Expected: `dumps/04-long-idle.hprof` written.

- [ ] **Step 2: Copy idea.log before shutting down**

```bash
cp /Users/dustin/projects/intellij-community/system/termlab/log/idea.log \
   /Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/logs/idea.log
```

- [ ] **Step 3: Shut down TermLab**

Tell the user: "Captures complete — you can close TermLab now." Wait for confirmation. The GC log at `gc/gc.log` will flush on JVM exit.

- [ ] **Step 4: Commit the raw artifacts**

**Important — size check first.** Heap dumps can be 300–1200 MB each. Do NOT commit `.hprof` files directly; they'll blow up the repo. Add a `.gitignore` rule and commit only the `jcmd/`, `gc/`, `logs/`, and `dumps/index.txt` artifacts. The hprof files stay local.

```bash
cd /Users/dustin/projects/conch_workbench
cat >> docs/memory-investigation/2026-04-16/.gitignore <<'EOF'
dumps/*.hprof
dumps/*.hprof.zip
dumps/mat-reports/
jfr/*.jfr
EOF
git add docs/memory-investigation/2026-04-16/
git commit -m "evidence(memory): phase-2 baseline captures (jcmd + logs + gc)"
```

---

## Phase 3 — Heap dump analysis (Eclipse MAT)

### Task 3.1: Batch-analyze each dump with MAT

**Why:** `ParseHeapDump.sh` with the three standard reports produces HTML zips that include (a) the top dominator suspects, (b) a retained-size overview, and (c) component drill-downs. Running this headless avoids the MAT GUI and keeps everything scriptable.

**Files:** None modified. Writes into `dumps/mat-reports/`.

- [ ] **Step 1: Run MAT on all four dumps**

```bash
cd /Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16
for H in dumps/*.hprof; do
  echo "==> Parsing $H"
  "$MAT_PARSE" -vmargs -Xmx4g "$H" \
    org.eclipse.mat.api:suspects \
    org.eclipse.mat.api:overview \
    org.eclipse.mat.api:top_components
done
mkdir -p dumps/mat-reports
mv dumps/*_Leak_Suspects.zip dumps/*_System_Overview.zip dumps/*_Top_Components.zip dumps/mat-reports/ 2>/dev/null || true
ls dumps/mat-reports/
```
Expected: three `.zip` files per dump (12 total). Each step takes 1–5 minutes per dump on an M-series Mac; the first parse of each `.hprof` is slower because MAT builds an index alongside the file. If `-Xmx4g` is still too low, bump to `-Xmx8g` — the indexing process itself can need more heap than the target dump.

- [ ] **Step 2: Extract and skim the leak-suspects HTML for each checkpoint**

```bash
cd /Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/dumps/mat-reports
for Z in *Leak_Suspects.zip; do
  unzip -q -o "$Z" -d "${Z%.zip}"
done
ls */index.html
```
Expected: four `index.html` files (one per checkpoint). Open each in a browser to skim (or `grep -A 50 'Problem Suspect' */index.html`). Each suspect lists a dominator object, the class, the retained size, and a description.

### Task 3.2: Extract the top-20 retainers per checkpoint

**Why:** The suspects report usually calls out 3–5 problems, but we want the broader top-20 so we don't miss smaller retainers that *collectively* dominate. The overview report contains the Dominator Tree serialized as a table.

- [ ] **Step 1: Pull the top retainer list from each overview**

The `*_System_Overview.zip` contains a `pages/` directory with HTML tables and a raw `dominator_tree.csv` (MAT 1.14+). Extract and read it:

```bash
cd /Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/dumps/mat-reports
for Z in *System_Overview.zip; do
  LBL="${Z%_System_Overview.zip}"
  unzip -q -o "$Z" -d "$LBL-overview"
  # Dominator tree CSV location varies by MAT version; find it:
  find "$LBL-overview" -name 'dominator*.csv' -o -name 'dominator*.html' | head -5
done
```

- [ ] **Step 2: For each checkpoint, record the top 20 by retained size**

In `docs/memory-investigation/2026-04-16/report.md`, under each checkpoint heading, paste a table with columns: Rank | Class | Shallow | Retained (MB) | % of heap. If the CSV exists, parse it; otherwise grep the retained-size tables out of the HTML (`grep -oE 'class="[^"]*"[^<]*<[^>]*>[0-9,]+ \([0-9.]+%\)' dominator_tree.html` is a starting point — adjust to whatever the MAT HTML structure is).

**No placeholder.** Record the actual 20 rows for each dump, verbatim from the MAT output. This is the bedrock evidence for the whole investigation.

### Task 3.3: Diff analysis — what grew between checkpoints 2 and 3

**Why:** Static top-20s tell you what *is* retained. The *growth* between post-warmup (idle steady-state) and post-workload (after usage) is what we need to reduce to hit the 500 MB ceiling. MAT does not produce a native diff; we do it by hand because the shape of the top-20 is small.

- [ ] **Step 1: Build a growth table in the report**

For each class/dominator appearing in *either* the checkpoint-2 or checkpoint-3 top-20, record:

| Class / dominator | C2 retained (MB) | C3 retained (MB) | Delta (MB) | Notes |

Sort by delta descending. The top 5 rows of this table are the *primary targets* of the investigation — these are where the 500→1100 MB growth comes from.

- [ ] **Step 2: Long-idle drift check**

Compute the same delta between checkpoint 3 and checkpoint 4. If any row grew (delta > 10 MB) without user interaction in that window, that's a **leak** — flag it with `LEAK:` in the notes column. If nothing grew, annotate "no background growth observed; growth is workload-driven".

### Task 3.4: Classify each top suspect

**Why:** Knowing *that* something retains 80 MB is not actionable. We need to know whether it's TermLab code, IntelliJ platform, a bundled plugin, or a JVM/framework thing — because the remediation differs for each (edit our code / fork intellij-community / drop a bundled plugin / change JVM flag).

**For each** of the top-10 growth suspects from Task 3.3:

- [ ] **Step 1: Identify the class's origin**

For a class like `com.intellij.openapi.fileTypes.impl.FileTypeManagerImpl`, locate it:

```bash
# Is it an IntelliJ platform class?
find /Users/dustin/projects/intellij-community -name 'FileTypeManagerImpl.java' -o -name 'FileTypeManagerImpl.kt' 2>/dev/null | head -5

# Or a TermLab class?
find /Users/dustin/projects/conch_workbench -name '*.java' -o -name '*.kt' | xargs grep -l 'class FileTypeManagerImpl' 2>/dev/null | head -5
```

Expected: exactly one origin. Record in the report under the suspect: **Origin:** `<repo-root>:<relative-path>:<line>`.

- [ ] **Step 2: Read the class to understand what it retains**

Read the source file (start at the top of the class). Look especially for:
- `Map` / `List` / `Set` / `Cache<>` fields that grow per-project, per-file, or per-session.
- `@State` annotations (PersistentStateComponent) — these often retain large XML state blobs.
- `MessageBus`/`Disposable` registrations — orphans leak their listeners.
- Extension point declarations — `ExtensionPointName.create(...)` that accumulate registrations.

Summarize in the report: "**Retains:** `<field-name>: <Type>` — one-sentence description of what populates it and when."

- [ ] **Step 3: Assign the classification label**

Exactly one of:
- `TERMLAB_CODE` — lives under `/Users/dustin/projects/conch_workbench/{core,plugins}/**`.
- `TERMLAB_CONFIG` — a platform class but retention is driven by TermLab's bundled-plugin or module list (easy to fix by removing the plugin/module from the layout).
- `PLATFORM_FORK` — `com.intellij.*` under intellij-community, retention is structural to the platform, and removing it cleanly is only possible by patching the IntelliJ source.
- `BUNDLED_PLUGIN` — the class ships inside a bundled IntelliJ plugin that TermLab currently includes (e.g., `intellij.yaml`, `intellij.toml`, `intellij.sh.plugin`, `intellij.textmate.plugin`). These can usually be removed from `TermLabProperties.kt` `bundledPluginModules`.
- `JVM_NATIVE` — the retention shows up in NMT but not in the heap dump (code cache, metaspace, direct buffers, thread stacks).

- [ ] **Step 4: Commit the classification table**

```bash
cd /Users/dustin/projects/conch_workbench
git add docs/memory-investigation/2026-04-16/report.md
git commit -m "evidence(memory): phase-3 dominator-tree analysis + classification"
```

---

## Phase 4 — Class histogram delta (fast cross-check)

### Task 4.1: Diff the class histograms across checkpoints

**Why:** The dominator tree shows retainers; the class histogram shows *instances*. A 10× instance-count growth in one class between checkpoints 2 and 3 — even if it's not a top-20 dominator — signals churn (allocations not being reclaimed) that JFR will want to confirm.

- [ ] **Step 1: Normalize the histograms and diff**

```bash
cd /Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/jcmd
# GC.class_histogram format: rank | #instances | #bytes | class
for F in *-classhisto.txt; do
  awk 'NR>3 && NF>=4 {print $2"\t"$3"\t"$4}' "$F" | sort -k4 > "${F%.txt}.sorted.tsv"
done
join -1 4 -2 4 -t$'\t' \
  post-warmup-classhisto.sorted.tsv post-workload-classhisto.sorted.tsv \
  | awk -F'\t' '{
      # cols: class, inst_C2, bytes_C2, inst_C3, bytes_C3
      d_inst = $4 - $2; d_bytes = $5 - $3;
      if (d_bytes > 1000000) printf "%-80s  +%12d bytes  +%8d inst\n", $1, d_bytes, d_inst
    }' \
  | sort -k2 -n -r | head -50 > histo-diff-C2-to-C3.txt
cat histo-diff-C2-to-C3.txt | head -50
```

Expected: a list of classes with retained-bytes delta > 1 MB between post-warmup and post-workload. Paste into the report under §"Class histogram delta".

### Task 4.2: Cross-reference with the dominator top-20

**Why:** If a class appears large in the histogram but *not* in the dominator top-20, it's a lot of small objects held by many different owners — a different kind of problem than a single fat cache. Call out both shapes in the report.

- [ ] **Step 1: Note mismatches**

For each class in the histogram diff top-20, mark:
- ✓ — also appears in dominator top-20 (same story).
- ⚠ — appears ONLY in histogram (many small owners; investigate via MAT OQL or just leave as "distributed allocation" in the report).

---

## Phase 5 — Allocation profiling (JFR)

### Task 5.1: Record a JFR over a repeat of the workload

**Why:** Heap dumps tell us what *survived*. JFR tells us what was *allocated and then collected*. The ratio matters: if TermLab allocates 2 GB worth of transient objects during a 5-minute workload, the GC is spending a lot of CPU (and those transients briefly inflate RSS). Allocation profiling surfaces hot allocation sites we can attack to reduce churn even if the retained set is fine.

**Note:** this requires a fresh TermLab run. Start it up again with the same diagnostic flags in place.

- [ ] **Step 1: Start TermLab and begin JFR**

```bash
cd /Users/dustin/projects/conch_workbench && make termlab
```

In a second terminal, wait for the JVM and start a 5-minute profile recording:
```bash
# Same pid-discovery as the capture script
PID=$(ps -axo pid=,command= \
  | awk '$0~/com\.intellij\.idea\.Main/ && $0~/idea\.platform\.prefix=TermLab/ {print $1}' \
  | tail -1)
echo "TermLab PID: $PID"
sleep 60  # warmup
jcmd "$PID" JFR.start name=termlab-workload \
  filename=/Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/jfr/workload.jfr \
  duration=300s settings=profile maxsize=500M
```
Expected: `Started recording <n>. The result will be written to .../workload.jfr`. The recording is now live; we have 5 minutes to exercise the app.

- [ ] **Step 2: `[human-in-loop]` Re-run the workload**

Ask the user to repeat the Phase 2.3 workload while the JFR is recording (roughly 5 minutes of activity). Message template:

> Please repeat the same workload you did for checkpoint 3 (open SSH, run commands, navigate SFTP, command palette, settings, vault) over the next ~5 minutes. Reply "done" when you've gone through it once. The profiler stops automatically at 5 minutes.

- [ ] **Step 3: Wait for JFR to complete**

```bash
# JFR.dump can be used if we want it earlier; otherwise it writes on duration elapse
sleep 300
jcmd "$PID" JFR.check
ls -lh /Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/jfr/workload.jfr
```
Expected: the JFR file exists and is 50–500 MB.

### Task 5.2: Extract top allocators from the JFR

**Why:** JDK 25 ships `jfr print` which is enough for a text summary. (For a graphical flamegraph, JDK Mission Control is nicer but optional — the text output is sufficient to identify the top call sites.)

- [ ] **Step 1: Summarize allocation events**

```bash
cd /Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/jfr
jfr summary workload.jfr | head -60 > summary.txt
# Top allocations outside TLAB (big single allocations — usually the interesting ones):
jfr print --events jdk.ObjectAllocationOutsideTLAB workload.jfr \
  | awk '/^jdk.ObjectAllocationOutsideTLAB/{
      while (getline line) {
        if (line ~ /^$/) break
        if (line ~ /objectClass/) cls=line
        if (line ~ /allocationSize/) {sub(/.*= /,"",line); sz=line}
        if (line ~ /stackTrace/) {getline s; print sz"\t"cls"\t"s}
      }}' \
  | sort -n -r | head -50 > top-outside-tlab.txt
head -50 top-outside-tlab.txt
```

Expected: a ranked list of `size / class / top-frame` for large single allocations. These are often the easiest wins.

- [ ] **Step 2: Record findings**

Paste the top 15 allocation sites into the report under §"Allocation profiling (JFR)". For each, note the top stack frame and classify (same labels as Task 3.4).

---

## Phase 6 — Native memory tracking (RSS vs. heap gap)

### Task 6.1: Parse the NMT summaries

**Why:** RSS includes: Java heap, metaspace (class metadata), code cache (JIT), thread stacks, GC structures, symbol tables, direct/mapped buffers, and native library allocations (Skiko, JCEF, JNA, etc.). If RSS is 1.1 GB and Java heap is 900 MB, the other 200 MB is explained by NMT. If RSS is 1.1 GB and heap is 400 MB, there's a 700 MB *native* footprint we'd otherwise miss entirely.

- [ ] **Step 1: Build the category table from each NMT file**

NMT output format for each category:
```
-  <Category> (reserved=<N>KB, committed=<M>KB)
```

Parse into a per-checkpoint table (Java heap / Class / Thread / Code / GC / Symbol / Native Memory Tracking / Internal / Other / mmap) of *committed* KB:

```bash
cd /Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/jcmd
for F in *-nmt.txt; do
  echo "=== $F ==="
  awk '
    /^-  [A-Za-z]/ {cat=$0; sub(/^-  /,"",cat); sub(/ \(reserved.*/,"",cat)}
    /committed=/ {sub(/.*committed=/,"",$0); sub(/,.*| \)/,"",$0);
                  print cat"\t"$0}
  ' "$F" | head -20
done
```

Record per-category committed MB at each checkpoint in the report's §"NMT by category".

- [ ] **Step 2: Compute "RSS unexplained by NMT"**

For each checkpoint: `unexplained_MB = RSS_MB - sum_of_committed_categories_MB`. A big gap (>100 MB) points to native allocations NMT doesn't track (JNA, Skiko, JCEF surfaces — these live in anonymous mmap blocks). If unexplained is large, note it as a finding; mitigation requires OS-level pmap analysis which is out of scope for this plan but must be called out.

- [ ] **Step 3: Identify top NMT call sites (detail mode)**

Because we set `NativeMemoryTracking=detail` at startup, the NMT file contains call-stack-attributed allocations. Grep the biggest ones:

```bash
cd /Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/jcmd
for F in *-nmt.txt; do
  echo "=== $F ==="
  grep -E '\(malloc=|reserved=' "$F" \
    | awk '{
        for (i=1;i<=NF;i++) if ($i ~ /malloc=[0-9]+/){
          n=$i; sub(/.*=/,"",n); sub(/KB.*/,"",n);
          if (n+0 > 10240) print $0
        }
      }' | head -20
done
```

Record the top 10 native call sites per checkpoint in the report.

---

## Phase 7 — Loaded-module / extension-point audit

### Task 7.1: Extract loaded plugin & service counts from idea.log

**Why:** TermLab has 8 first-party plugins + 5 IntelliJ-bundled plugins + 50+ platform modules declared in `TermLabProperties.kt#getProductContentDescriptor`. Many platform services, extension points, and listeners still register even when we don't use them — and every registration costs class loading, a few instances, and (for services) sometimes a `@State` persistence blob.

- [ ] **Step 1: Count loaded plugins**

```bash
grep -E 'Loaded plugins|loaded.*plugin' \
  /Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/logs/idea.log \
  | head -20
grep -cE 'loaded custom plugin|loaded bundled plugin' \
  /Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/logs/idea.log
```

Record the list of loaded plugins in the report under §"Loaded plugins at runtime". Compare against the list declared in `TermLabProperties.kt` `bundledPluginModules` — any plugin loaded that is *not* listed there is a surprise worth calling out.

### Task 7.2: Audit classloader stats

**Why:** `VM.classloader_stats` from the capture snapshots tells us how many classes each plugin classloader loaded. A plugin with 5000 loaded classes but zero usage in TermLab is a prime candidate for removal.

- [ ] **Step 1: Rank classloaders by loaded-class count**

```bash
cd /Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/jcmd
awk 'NR>2 && NF>=5 {print $3"\t"$NF}' post-warmup-classloaders.txt \
  | sort -n -r | head -30
```
Expected: rank of classloader / loaded-class-count (30 rows). The top loaders are typically platform core, then large bundled plugins (textmate, yaml, sh). Record top 20 in the report under §"Classloader rank".

- [ ] **Step 2: Cross-reference with TermLab's bundle list**

For each of the top 10 loaded classloaders, mark:
- `REQUIRED` — referenced as `essential-plugin` in `TermLabApplicationInfo.xml` or needed by a first-party plugin.
- `BUNDLED_OPTIONAL` — bundled for convenience (json, yaml, toml, textmate, sh) but not required for TermLab functionality.
- `PLATFORM_CORE` — part of `CoreModuleSets.essentialMinimal()` expansion in `TermLabProperties.kt`.

Optional bundles with high class counts are the highest-ROI removal candidates.

---

## Phase 8 — Source-level root cause + recommendation drafting

### Task 8.1: For each top-10 growth suspect (from Task 3.3), write a recommendation block

**Why:** This is where investigation becomes actionable. Each block is the seed of a future PR or follow-up plan.

**Recommendation block template** (paste one per suspect into `report.md` §"Recommendations"):

```markdown
### R<N>: <short title>

- **Suspect:** `<fully.qualified.ClassName>`
- **Evidence:** <which dump/histogram/JFR/NMT row; exact MB and checkpoint>
- **Origin:** `<repo>:<path>:<line>` (from Task 3.4 Step 1)
- **What retains:** <field + mechanism, from Task 3.4 Step 2>
- **Classification:** TERMLAB_CODE | TERMLAB_CONFIG | PLATFORM_FORK | BUNDLED_PLUGIN | JVM_NATIVE
- **Estimated saving:** <MB — from retained size in the dump; be conservative>
- **Recommended action:** <one of:>
   - **Config change:** remove `<module/plugin>` from `TermLabProperties.kt` `bundledPluginModules` / `productModules`.
   - **TermLab code fix:** <file:line → what to change; e.g., "dispose `xyzListener` in `ProjectCloseListener#projectClosed`">.
   - **Platform fork:** patch `<intellij-community path>` — <what>. Keep the patch in `termlab/patches/` and apply via the build.
   - **JVM flag:** set `<-XX:...>` (document what it does).
   - **Accept:** <cost seems structural; document why and don't fix>.
- **Risk:** <what could break; what smoke test verifies the fix>.
- **Priority:** P0 (≥100 MB) / P1 (30–100 MB) / P2 (<30 MB).
```

- [ ] **Step 1: Fill out one block per suspect**

Do NOT abbreviate — write the full block for each of the top 10 suspects. If a suspect has no obvious fix, still write the block with **Recommended action:** `Accept — document constraint` plus the reason (e.g., "required for TermLab's SSH terminal rendering; removing would break core functionality").

### Task 8.2: High-signal cross-cutting checks

**Why:** Certain IntelliJ features are common outsized retainers regardless of what showed up in the dominator tree. Check each and record in the report even if the dominator tree didn't flag them.

- [ ] **Step 1: Check each of these specifically**

For each of the following, run the listed probe and record the answer in §"Cross-cutting checks":

- **VFS (`com.intellij.openapi.vfs.newvfs.persistent.PersistentFSImpl`)**: is it loaded? (search the class histograms.) TermLab already sets `idea.indexes.pretendNoFiles=true` but VFS may still be instantiated. If present with >20 MB retained, that's a fork candidate to make VFS optional.
- **Indexing infrastructure** (`FileBasedIndexImpl`, `StubIndexImpl`): should be disabled by `pretendNoFiles`, but verify by grep.
- **Welcome screen / recent projects** (`RecentProjectsManagerBase`): retained beyond the welcome screen? (Open the post-workload dump's dominator tree.)
- **Search Everywhere** index structures (`SearchEverywhereManagerImpl`, `SearchEverywhereContributorReplacementService`): how many contributors are registered? TermLab has custom contributors; verify none leak.
- **Editor + Document history** (`DocumentImpl`, `EditorHistoryManagerImpl`): grow unboundedly if there's no eviction.
- **MessageBusImpl subscriber maps**: size of subscriber collection per topic — if any single topic has >1000 subscribers, that's a disposer leak.
- **`com.intellij.openapi.util.Disposer`** tree root: total descendants? (MAT OQL: `select count(*) from com.intellij.openapi.util.Disposer$ObjectNode`.) A very large count (>5000) is itself a warning sign.
- **JCEF / Chromium** embeds (via `com.intellij.ui.jcef.JBCefBrowser`): TermLab has no JCEF usage planned, but some platform tabs or notifications use it. Verify not loaded.
- **Skiko / native rendering**: appears in NMT rather than heap. If NMT "Other mmap" is >150 MB, investigate.
- **Statistic collectors / FUS** (`com.intellij.internal.statistic.*`): TermLab disables collection but registration may still happen. Check classloader stats.

Record each check as a one-line finding: `CHECK_NAME: <loaded? y/n> <retained? MB> <follow-up needed? y/n>`.

### Task 8.3: Prior-work verification

**Why:** The existing roadmap (`docs/plans/2026-04-14-conch-roadmap.md` §"Phase A.1") hypothesizes that `intellij.yaml`, `intellij.toml`, `intellij.textmate.plugin`, `intellij.sh.plugin`, `intellij.json` are unjustified bundles. With the evidence collected, *confirm or deny each* by locating their classloaders in Task 7.2 output and their classes in the Task 4 histogram diffs.

- [ ] **Step 1: For each plugin listed above, record**

| Plugin | Classes loaded | Retained (MB, post-workload) | Justification (any TermLab dep?) | Recommendation |

Cross-reference `TermLabProperties.kt` `bundledPluginModules` for how each got in. The `editor` plugin is the most likely to legitimately depend on textmate/yaml for syntax highlighting — verify by reading `plugins/editor/resources/META-INF/plugin.xml` for `<depends>` entries.

---

## Phase 9 — Write the recommendations report

### Task 9.1: Synthesize the report

**Files:**
- Write: `/Users/dustin/projects/conch_workbench/docs/memory-investigation/2026-04-16/report.md`

**Why:** Everything up to this point has fed a running report. This task finalizes it as a standalone document. The header + Executive Summary are written last so they reflect the actual evidence, not initial hypotheses.

- [ ] **Step 1: Ensure the report has this structure**

Any section already populated in earlier tasks stays; any missing section gets filled in.

```markdown
# TermLab Memory Investigation Report — 2026-04-16

## Executive Summary
- Starting RSS: <N> MB (target 200 MB → gap <G> MB).
- Post-workload RSS: <N> MB (target 500 MB → gap <G> MB).
- Long-idle delta: <N> MB/30min — <leaking | stable>.
- Top 3 growth drivers: 1) <...>, 2) <...>, 3) <...>.
- Headline recommendation: <the single highest-impact action, one sentence>.

## Method
- Checkpoints captured (link to dumps/).
- Tools used (Eclipse MAT version, JFR settings, NMT mode).
- Workload definition (the exact human-in-loop checklist used).

## Observations
### Checkpoint 1 — cold-start
  - RSS / heap / metaspace numbers.
  - Top 20 dominators (table from Task 3.2).
### Checkpoint 2 — post-warmup
  - Same shape.
### Checkpoint 3 — post-workload
  - Same shape.
  - Growth table (Task 3.3 output).
### Checkpoint 4 — long-idle
  - Drift from C3 (Task 3.3 Step 2).
  - LEAK flags if any.

## Class histogram delta (Task 4)

## Allocation profiling (Task 5)

## NMT by category (Task 6)

## Loaded-plugin / classloader audit (Task 7)

## Cross-cutting checks (Task 8.2)

## Prior-work verification (Task 8.3)

## Recommendations
(All R<N> blocks from Task 8.1, sorted by Priority then Estimated saving.)

## Recommended execution order
(A short-form prioritization: which P0/P1 recommendations to pick up first,
what smoke tests to run between them, and which would warrant an IntelliJ fork.)

## Open questions / out-of-scope
(Anything the evidence didn't answer; things that need a follow-up plan.)
```

- [ ] **Step 2: Compute the headline numbers and fill in the Executive Summary**

This is the *last* thing filled in. Pull numbers from:
- Starting RSS: capture-script output from Task 2.1.
- Post-workload RSS: capture-script output from Task 2.3.
- Long-idle delta: Task 3.3 Step 2.
- Top-3 growth drivers: the top 3 of the Task 3.3 growth table.
- Headline recommendation: the single highest-priority P0 recommendation — typically the biggest MB saving with lowest implementation cost.

- [ ] **Step 3: Self-review the report**

Before committing, verify:
- Every recommendation has a concrete file path and a specific action (no "consider" or "investigate further").
- Every MB number cites an artifact (dump filename, jcmd file, histogram row).
- The Executive Summary's top-3 matches the ordered Recommendations table.
- Out-of-scope section explicitly names anything the user asked about that isn't answered.

- [ ] **Step 4: Commit the report**

```bash
cd /Users/dustin/projects/conch_workbench
git add docs/memory-investigation/2026-04-16/report.md
git commit -m "docs(memory): investigation report + prioritized recommendations"
```

---

## Phase 10 — Revert diagnostic flags + handoff

### Task 10.1: Remove the investigation JVM flags

**Why:** NMT detail has a small but nonzero runtime cost (~5% according to JDK docs); GC logging writes to disk continuously; the on-OOM dump path is fine to keep but the path hardcodes a specific date. Don't ship the diagnostic config.

**Files:**
- Modify: `/Users/dustin/projects/conch_workbench/BUILD.bazel`

- [ ] **Step 1: Delete the `--- Memory investigation (2026-04-16) ---` block** added in Task 1.2 Step 2. Keep the existing pre-investigation flags exactly as they were.

- [ ] **Step 2: Verify the build still passes**

```bash
cd /Users/dustin/projects/conch_workbench && make termlab-build 2>&1 | tail -15
```
Expected: "Build completed successfully".

- [ ] **Step 3: Commit**

```bash
cd /Users/dustin/projects/conch_workbench
git add BUILD.bazel
git commit -m "chore(memory): revert investigation JVM flags; report is in docs/memory-investigation/"
```

### Task 10.2: Present the report to the user

- [ ] **Step 1: Summarize for the user**

Post a short summary (≤150 words) of:
1. The three headline findings.
2. The single most-impactful recommended action.
3. A link to the full report: `docs/memory-investigation/2026-04-16/report.md`.
4. An explicit ask: "These are recommendations, not implementations. Do you want me to scope a follow-up plan around the P0 items?" — because implementation of fixes is *out of scope* for this investigation plan.

---

## Self-review checklist (for the plan author, done before handoff)

- [x] Every step names its file paths in absolute form and gives an exact command.
- [x] Human-in-loop steps are explicitly marked and instruct the agent to *wait* for confirmation rather than simulate.
- [x] Artifact directory is defined before any task writes into it.
- [x] Heap dumps are explicitly `.gitignore`'d so the agent doesn't try to commit a 1 GB hprof.
- [x] Classification labels are defined once and reused consistently (TERMLAB_CODE / TERMLAB_CONFIG / PLATFORM_FORK / BUNDLED_PLUGIN / JVM_NATIVE).
- [x] Diagnostic VM flags are explicitly reverted at the end — investigation does not ship.
- [x] The deliverable (report.md) has a concrete structure, not "write findings".
- [x] Prior disabled features (indexing, fsnotifier) are enumerated in Context so they don't get re-recommended.
- [x] Both repos (conch_workbench + intellij-community) are referenced by absolute path where source lookups happen.
- [x] Executor's limitation (can't drive UI) is acknowledged with human-in-loop pauses, not skipped.
