#!/usr/bin/env bash
# capture_mem_snapshot.sh — capture a full memory-checkpoint artifact set.
# Usage: capture_mem_snapshot.sh <checkpoint-label> [--gc-before]
# Produces: dumps/<NN>-<label>.hprof + jcmd/<label>-*.txt files.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKBENCH_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
INTELLIJ_ROOT_FILE="$WORKBENCH_DIR/.intellij-root"

LABEL="${1:?usage: capture_mem_snapshot.sh <label> [--gc-before]}"
GC_BEFORE=0
if [[ "${2:-}" == "--gc-before" ]]; then
  GC_BEFORE=1
fi

ART_DIR="$WORKBENCH_DIR/docs/memory-investigation/2026-04-16"
DUMP_DIR="$ART_DIR/dumps"
JCMD_DIR="$ART_DIR/jcmd"
mkdir -p "$DUMP_DIR" "$JCMD_DIR"

if [[ -f "$INTELLIJ_ROOT_FILE" ]]; then
  INTELLIJ_ROOT="$(<"$INTELLIJ_ROOT_FILE")"
else
  INTELLIJ_ROOT="$WORKBENCH_DIR/../intellij-community"
fi

# Find the TermLab JVM pid (same pattern as benchmark_idle.sh).
SYSTEM_PATH="$INTELLIJ_ROOT/system/termlab"
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
RSS_KB="$(ps -p "$PID" -o rss= | awk '{$1=$1; print}')"
RSS_MB=$((RSS_KB / 1024))
echo "==> RSS at capture: ${RSS_MB} MB"

# Dump number = next integer prefix in dumps/
N="$(find "$DUMP_DIR" -maxdepth 1 -name '*.hprof' | wc -l | awk '{print $1 + 1}')"
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
