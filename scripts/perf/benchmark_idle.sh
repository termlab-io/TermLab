#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  benchmark_idle.sh [options]

Required options:
  --out <dir>                 Output directory for benchmark artifacts.

Optional options:
  --intellij-root <path>      Path to intellij-community checkout.
                              Defaults to .intellij-root, then ../intellij-community.
  --workspace <path>          Workspace path passed to make conch (default: $HOME).
  --warmup-sec <n>            Warmup seconds after IDE process appears (default: 90).
  --sample-sec <n>            Sample interval seconds (default: 5).
  --duration-sec <n>          Total sampling duration seconds (default: 300).
  --baseline <env-file>       Baseline summary env file for delta comparison.
  --strict                    Exit non-zero if Stage A gate fails.

Behavior:
  Launches `make conch`, discovers the Conch JVM pid, samples RSS and CPU while idle,
  captures jcmd snapshots, writes CSV + JSON + env summaries, and prints pass/fail.
USAGE
}

require_num() {
  local name="$1"
  local value="$2"
  if ! [[ "$value" =~ ^[0-9]+$ ]]; then
    echo "ERROR: $name must be an integer, got '$value'" >&2
    exit 1
  fi
}

num_le() {
  local a="$1"
  local b="$2"
  awk -v a="$a" -v b="$b" 'BEGIN { exit !(a <= b) }'
}

float_abs() {
  local v="$1"
  awk -v v="$v" 'BEGIN { if (v < 0) v = -v; printf "%.3f", v }'
}

parse_env_value() {
  local key="$1"
  local file="$2"
  awk -F= -v key="$key" '$1 == key {print $2; exit}' "$file"
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INTELLIJ_ROOT=""
WORKSPACE="${HOME}"
WARMUP_SEC=90
SAMPLE_SEC=5
DURATION_SEC=300
OUT_DIR=""
BASELINE_FILE=""
STRICT=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --intellij-root)
      INTELLIJ_ROOT="$2"
      shift 2
      ;;
    --workspace)
      WORKSPACE="$2"
      shift 2
      ;;
    --warmup-sec)
      WARMUP_SEC="$2"
      shift 2
      ;;
    --sample-sec)
      SAMPLE_SEC="$2"
      shift 2
      ;;
    --duration-sec)
      DURATION_SEC="$2"
      shift 2
      ;;
    --out)
      OUT_DIR="$2"
      shift 2
      ;;
    --baseline)
      BASELINE_FILE="$2"
      shift 2
      ;;
    --strict)
      STRICT=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument '$1'" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$OUT_DIR" ]]; then
  echo "ERROR: --out is required" >&2
  usage
  exit 1
fi

if [[ -z "$INTELLIJ_ROOT" ]]; then
  if [[ -f "$REPO_ROOT/.intellij-root" ]]; then
    INTELLIJ_ROOT="$(tr -d '[:space:]' < "$REPO_ROOT/.intellij-root")"
  else
    INTELLIJ_ROOT="$REPO_ROOT/../intellij-community"
  fi
fi

require_num "warmup-sec" "$WARMUP_SEC"
require_num "sample-sec" "$SAMPLE_SEC"
require_num "duration-sec" "$DURATION_SEC"

if [[ "$SAMPLE_SEC" -le 0 || "$DURATION_SEC" -le 0 ]]; then
  echo "ERROR: sample-sec and duration-sec must be > 0" >&2
  exit 1
fi

SAMPLE_COUNT=$((DURATION_SEC / SAMPLE_SEC))
if [[ "$SAMPLE_COUNT" -lt 1 ]]; then
  echo "ERROR: duration-sec must be >= sample-sec" >&2
  exit 1
fi

mkdir -p "$WORKSPACE"

mkdir -p "$OUT_DIR"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
RUN_DIR="$OUT_DIR/$RUN_ID"
mkdir -p "$RUN_DIR"

CSV_FILE="$RUN_DIR/metrics.csv"
SUMMARY_JSON="$RUN_DIR/summary.json"
SUMMARY_ENV="$RUN_DIR/summary.env"
RUN_LOG="$RUN_DIR/conch.log"
NMT_FILE="$RUN_DIR/jcmd-native-memory.txt"
HEAP_FILE="$RUN_DIR/jcmd-heap-info.txt"
FLAGS_FILE="$RUN_DIR/jcmd-vm-flags.txt"
THREAD_FILE="$RUN_DIR/jcmd-thread-print.txt"
CLASS_HISTO_FILE="$RUN_DIR/jcmd-class-histogram.txt"

CONCH_PID=""
LAUNCHER_PID=""

cleanup() {
  if [[ -n "$CONCH_PID" ]] && kill -0 "$CONCH_PID" >/dev/null 2>&1; then
    kill "$CONCH_PID" >/dev/null 2>&1 || true
    sleep 2
    if kill -0 "$CONCH_PID" >/dev/null 2>&1; then
      kill -9 "$CONCH_PID" >/dev/null 2>&1 || true
    fi
  fi

  if [[ -n "$LAUNCHER_PID" ]] && kill -0 "$LAUNCHER_PID" >/dev/null 2>&1; then
    kill "$LAUNCHER_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

SYSTEM_PATH="$INTELLIJ_ROOT/system/conch"

find_conch_pid() {
  ps -axo pid=,command= \
    | awk -v system_path="$SYSTEM_PATH" '
        $0 ~ /com\.intellij\.idea\.Main/ &&
        $0 ~ /idea\.platform\.prefix=Conch/ &&
        index($0, system_path) > 0 {
          print $1
        }
      ' \
    | tail -n 1
}

echo "==> Launching Conch for idle benchmark"
echo "    repo:       $REPO_ROOT"
echo "    intellij:   $INTELLIJ_ROOT"
echo "    workspace:  $WORKSPACE"
echo "    output:     $RUN_DIR"

INTELLIJ_ROOT="$INTELLIJ_ROOT" CONCH_WORKSPACE="$WORKSPACE" make -C "$REPO_ROOT" conch >"$RUN_LOG" 2>&1 &
LAUNCHER_PID=$!

STARTUP_TIMEOUT=1800
START_WAIT=0
while [[ "$START_WAIT" -lt "$STARTUP_TIMEOUT" ]]; do
  if ! kill -0 "$LAUNCHER_PID" >/dev/null 2>&1; then
    echo "ERROR: make conch exited before Conch JVM was discovered." >&2
    echo "See log: $RUN_LOG" >&2
    exit 1
  fi

  CANDIDATE_PID="$(find_conch_pid || true)"
  if [[ -n "$CANDIDATE_PID" ]]; then
    CONCH_PID="$CANDIDATE_PID"
    break
  fi

  sleep 2
  START_WAIT=$((START_WAIT + 2))
done

if [[ -z "$CONCH_PID" ]]; then
  echo "ERROR: timed out waiting for Conch JVM pid discovery." >&2
  echo "See log: $RUN_LOG" >&2
  exit 1
fi

echo "==> Conch JVM pid: $CONCH_PID"
echo "==> Warmup: ${WARMUP_SEC}s"
sleep "$WARMUP_SEC"

if ! kill -0 "$CONCH_PID" >/dev/null 2>&1; then
  echo "ERROR: Conch JVM exited during warmup." >&2
  echo "See log: $RUN_LOG" >&2
  exit 1
fi

echo "timestamp,elapsed_sec,rss_kb,cpu_pct,vsz_kb" > "$CSV_FILE"

CPU_SUM="0"
RSS_SUM=0
MAX_RSS_KB=0
MAX_CPU="0"
FIRST_TS="$(date +%s)"

for ((i = 1; i <= SAMPLE_COUNT; i++)); do
  if ! kill -0 "$CONCH_PID" >/dev/null 2>&1; then
    echo "ERROR: Conch JVM exited during sampling." >&2
    echo "See log: $RUN_LOG" >&2
    exit 1
  fi

  LINE="$(ps -p "$CONCH_PID" -o rss=,%cpu=,vsz= | awk '{$1=$1; print}')"
  if [[ -z "$LINE" ]]; then
    echo "ERROR: failed to sample pid $CONCH_PID." >&2
    exit 1
  fi

  RSS_KB="$(awk '{print $1}' <<< "$LINE")"
  CPU="$(awk '{print $2}' <<< "$LINE")"
  VSZ_KB="$(awk '{print $3}' <<< "$LINE")"
  CPU="${CPU//%/}"

  NOW_TS="$(date +%s)"
  ELAPSED=$((NOW_TS - FIRST_TS))
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ),$ELAPSED,$RSS_KB,$CPU,$VSZ_KB" >> "$CSV_FILE"

  RSS_SUM=$((RSS_SUM + RSS_KB))
  CPU_SUM="$(awk -v a="$CPU_SUM" -v b="$CPU" 'BEGIN { printf "%.6f", a + b }')"

  if [[ "$RSS_KB" -gt "$MAX_RSS_KB" ]]; then
    MAX_RSS_KB="$RSS_KB"
  fi
  if awk -v a="$CPU" -v b="$MAX_CPU" 'BEGIN { exit !(a > b) }'; then
    MAX_CPU="$CPU"
  fi

  if [[ "$i" -lt "$SAMPLE_COUNT" ]]; then
    sleep "$SAMPLE_SEC"
  fi
done

AVG_RSS_KB=$((RSS_SUM / SAMPLE_COUNT))
AVG_RSS_MB="$(awk -v kb="$AVG_RSS_KB" 'BEGIN { printf "%.3f", kb / 1024.0 }')"
MAX_RSS_MB="$(awk -v kb="$MAX_RSS_KB" 'BEGIN { printf "%.3f", kb / 1024.0 }')"
AVG_CPU="$(awk -v sum="$CPU_SUM" -v n="$SAMPLE_COUNT" 'BEGIN { printf "%.3f", sum / n }')"

jcmd "$CONCH_PID" VM.native_memory summary > "$NMT_FILE" 2>&1 || true
jcmd "$CONCH_PID" GC.heap_info > "$HEAP_FILE" 2>&1 || true
jcmd "$CONCH_PID" VM.flags > "$FLAGS_FILE" 2>&1 || true
jcmd "$CONCH_PID" Thread.print -l > "$THREAD_FILE" 2>&1 || true
jcmd "$CONCH_PID" GC.class_histogram > "$CLASS_HISTO_FILE" 2>&1 || true

STAGE_A_PASS=false
STAGE_B_PASS=false
if num_le "$AVG_RSS_MB" "200" && num_le "$AVG_CPU" "1.0"; then
  STAGE_A_PASS=true
fi
if num_le "$AVG_RSS_MB" "100" && num_le "$AVG_CPU" "1.0"; then
  STAGE_B_PASS=true
fi

cat > "$SUMMARY_ENV" <<ENV
run_id=$RUN_ID
run_dir=$RUN_DIR
pid=$CONCH_PID
sample_count=$SAMPLE_COUNT
avg_rss_kb=$AVG_RSS_KB
avg_rss_mb=$AVG_RSS_MB
max_rss_kb=$MAX_RSS_KB
max_rss_mb=$MAX_RSS_MB
avg_cpu_pct=$AVG_CPU
max_cpu_pct=$MAX_CPU
stage_a_pass=$STAGE_A_PASS
stage_b_pass=$STAGE_B_PASS
ENV

DELTA_AVG_RSS_MB=""
DELTA_AVG_CPU=""
DELTA_BASELINE=""
if [[ -z "$BASELINE_FILE" ]]; then
  BASELINE_FILE="$OUT_DIR/latest_summary.env"
fi
if [[ -f "$BASELINE_FILE" ]]; then
  BASE_RSS="$(parse_env_value avg_rss_mb "$BASELINE_FILE" || true)"
  BASE_CPU="$(parse_env_value avg_cpu_pct "$BASELINE_FILE" || true)"
  if [[ -n "$BASE_RSS" && -n "$BASE_CPU" ]]; then
    DELTA_BASELINE="$BASELINE_FILE"
    DELTA_AVG_RSS_MB="$(awk -v cur="$AVG_RSS_MB" -v base="$BASE_RSS" 'BEGIN { printf "%.3f", cur - base }')"
    DELTA_AVG_CPU="$(awk -v cur="$AVG_CPU" -v base="$BASE_CPU" 'BEGIN { printf "%.3f", cur - base }')"
  fi
fi

cat > "$SUMMARY_JSON" <<JSON
{
  "run_id": "$RUN_ID",
  "timestamp_utc": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "scenario": "launch + 1 terminal tab idle",
  "params": {
    "warmup_sec": $WARMUP_SEC,
    "sample_sec": $SAMPLE_SEC,
    "duration_sec": $DURATION_SEC,
    "sample_count": $SAMPLE_COUNT
  },
  "metrics": {
    "avg_rss_kb": $AVG_RSS_KB,
    "avg_rss_mb": $AVG_RSS_MB,
    "max_rss_kb": $MAX_RSS_KB,
    "max_rss_mb": $MAX_RSS_MB,
    "avg_cpu_pct": $AVG_CPU,
    "max_cpu_pct": $MAX_CPU
  },
  "gates": {
    "stage_a": {
      "target_avg_rss_mb_le": 200,
      "target_avg_cpu_pct_le": 1.0,
      "pass": $STAGE_A_PASS
    },
    "stage_b": {
      "target_avg_rss_mb_le": 100,
      "target_avg_cpu_pct_le": 1.0,
      "pass": $STAGE_B_PASS
    }
  },
  "baseline_delta": {
    "baseline_file": "${DELTA_BASELINE}",
    "delta_avg_rss_mb": ${DELTA_AVG_RSS_MB:-null},
    "delta_avg_cpu_pct": ${DELTA_AVG_CPU:-null}
  },
  "artifacts": {
    "metrics_csv": "$CSV_FILE",
    "native_memory": "$NMT_FILE",
    "heap_info": "$HEAP_FILE",
    "vm_flags": "$FLAGS_FILE",
    "thread_print": "$THREAD_FILE",
    "class_histogram": "$CLASS_HISTO_FILE",
    "conch_log": "$RUN_LOG"
  }
}
JSON

cp "$SUMMARY_ENV" "$OUT_DIR/latest_summary.env"
cp "$SUMMARY_JSON" "$OUT_DIR/latest_summary.json"
cp "$CSV_FILE" "$OUT_DIR/latest_metrics.csv"

echo
echo "==> Idle benchmark summary"
echo "    run:           $RUN_ID"
echo "    avg RSS:       ${AVG_RSS_MB} MB"
echo "    max RSS:       ${MAX_RSS_MB} MB"
echo "    avg CPU:       ${AVG_CPU}%"
echo "    max CPU:       ${MAX_CPU}%"
echo "    Stage A pass:  $STAGE_A_PASS"
echo "    Stage B pass:  $STAGE_B_PASS"

if [[ -n "$DELTA_BASELINE" ]]; then
  RSS_SIGN="decreased"
  CPU_SIGN="decreased"
  if ! num_le "$DELTA_AVG_RSS_MB" "0"; then RSS_SIGN="increased"; fi
  if ! num_le "$DELTA_AVG_CPU" "0"; then CPU_SIGN="increased"; fi
  echo "    delta RSS:     ${DELTA_AVG_RSS_MB} MB (${RSS_SIGN} vs baseline)"
  echo "    delta CPU:     ${DELTA_AVG_CPU}% (${CPU_SIGN} vs baseline)"
fi

echo "    summary json:  $SUMMARY_JSON"
echo "    metrics csv:   $CSV_FILE"

if [[ "$STRICT" -eq 1 && "$STAGE_A_PASS" != "true" ]]; then
  echo "ERROR: strict mode enabled and Stage A gate failed." >&2
  exit 2
fi

exit 0
