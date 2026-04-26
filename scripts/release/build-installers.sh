#!/usr/bin/env bash
#
# build-installers.sh — orchestrate per-platform installer builds via
# jps-bootstrap + TermLabInstallersBuildTarget.
#
# Usage:
#   build-installers.sh INTELLIJ_ROOT [--jobs N] [--no-warmup]
#
# Reads:
#   - Apple/TermLab signing env vars from caller's env (passed through to
#     each child process unmodified).
#
# Writes:
#   - Per-platform stdout/stderr to out/release-logs/<os>-<arch>.log
#     (relative to caller's CWD, which release.sh sets to WORKBENCH_DIR).
#   - State files at out/release-logs/<os>-<arch>.state (one of: running,
#     done, failed).
#
# Exit codes:
#   0 — all 6 platforms built successfully
#   1 — any platform failed
#   2 — invocation error (missing args, missing jps-bootstrap, etc.)

set -euo pipefail

# --- Bash version check ------------------------------------------------------
if [ "${BASH_VERSINFO[0]}" -lt 4 ] || \
   { [ "${BASH_VERSINFO[0]}" -eq 4 ] && [ "${BASH_VERSINFO[1]}" -lt 3 ]; }; then
  echo "ERROR: build-installers.sh requires Bash 4.3+ (found $BASH_VERSION)" >&2
  exit 2
fi

# --- Args --------------------------------------------------------------------
INTELLIJ_ROOT="${1:-}"
shift || true
JOBS=3
NO_WARMUP=0

while [ $# -gt 0 ]; do
  case "$1" in
    --jobs|-j)
      JOBS="$2"
      shift 2
      ;;
    --no-warmup)
      NO_WARMUP=1
      shift
      ;;
    *)
      echo "ERROR: unknown arg: $1" >&2
      exit 2
      ;;
  esac
done

if [ -z "$INTELLIJ_ROOT" ]; then
  echo "ERROR: usage: build-installers.sh INTELLIJ_ROOT [--jobs N] [--no-warmup]" >&2
  exit 2
fi

JPS_BOOTSTRAP="$INTELLIJ_ROOT/platform/jps-bootstrap/jps-bootstrap.sh"
if [ ! -x "$JPS_BOOTSTRAP" ]; then
  echo "ERROR: jps-bootstrap.sh not found or not executable at $JPS_BOOTSTRAP" >&2
  exit 2
fi

case "$JOBS" in
  ''|*[!0-9]*)
    echo "ERROR: --jobs must be a positive integer (got '$JOBS')" >&2
    exit 2
    ;;
esac
if [ "$JOBS" -lt 1 ] || [ "$JOBS" -gt 6 ]; then
  echo "ERROR: --jobs must be between 1 and 6 (got $JOBS)" >&2
  exit 2
fi

# --- Build matrix ------------------------------------------------------------
# (os, arch) pairs. The first one is also the warmup target.
MATRIX=(
  "mac aarch64"
  "mac x64"
  "linux aarch64"
  "linux x64"
  "windows aarch64"
  "windows x64"
)

LOGS_DIR="out/release-logs"
mkdir -p "$LOGS_DIR"
rm -f "$LOGS_DIR"/*.log "$LOGS_DIR"/*.state

# --- Build runner ------------------------------------------------------------
# Runs one platform via jps-bootstrap. Writes log + state files.
# Returns 0 on success, non-zero on failure.
build_one() {
  local os="$1" arch="$2" reuse="$3"
  local log="$LOGS_DIR/${os}-${arch}.log"
  local state="$LOGS_DIR/${os}-${arch}.state"

  : > "$log"            # touch — exists immediately for the renderer
  echo "running" > "$state"

  if TERMLAB_TARGET_OS="$os" \
     TERMLAB_TARGET_ARCH="$arch" \
     TERMLAB_BUILD_MODE=prod \
     TERMLAB_REUSE_COMPILED_CLASSES="$reuse" \
     "$JPS_BOOTSTRAP" \
       "$INTELLIJ_ROOT" \
       intellij.termlab.build \
       TermLabInstallersBuildTarget \
       >> "$log" 2>&1; then
    echo "done" > "$state"
    return 0
  else
    echo "failed" > "$state"
    return 1
  fi
}

# --- Phase 1: warmup ---------------------------------------------------------
if [ "$NO_WARMUP" -eq 1 ]; then
  echo "Skipping warmup phase (--no-warmup); fanning out all 6 platforms in parallel."
  PHASE_2_MATRIX=("${MATRIX[@]}")
else
  read -r WARMUP_OS WARMUP_ARCH <<< "${MATRIX[0]}"
  echo "Phase 1 (warmup): building ${WARMUP_OS} ${WARMUP_ARCH} serially"
  if ! build_one "$WARMUP_OS" "$WARMUP_ARCH" "false"; then
    echo "ERROR: warmup build failed; see $LOGS_DIR/${WARMUP_OS}-${WARMUP_ARCH}.log" >&2
    exit 1
  fi
  echo "Phase 1 complete."
  PHASE_2_MATRIX=("${MATRIX[@]:1}")
fi

# --- Phase 2: parallel fan-out -----------------------------------------------
echo "Phase 2: fanning out ${#PHASE_2_MATRIX[@]} platforms with concurrency limit $JOBS"

for combo in "${PHASE_2_MATRIX[@]}"; do
  read -r os arch <<< "$combo"
  while [ "$(jobs -r | wc -l)" -ge "$JOBS" ]; do
    wait -n || true
  done
  build_one "$os" "$arch" "true" &
done

# Drain remaining background jobs.
wait || true

# --- Aggregate result --------------------------------------------------------
FAILED=()
for combo in "${MATRIX[@]}"; do
  read -r os arch <<< "$combo"
  state="$(cat "$LOGS_DIR/${os}-${arch}.state" 2>/dev/null || echo missing)"
  if [ "$state" != "done" ]; then
    FAILED+=("${os}-${arch} ($state)")
  fi
done

if [ "${#FAILED[@]}" -gt 0 ]; then
  echo "" >&2
  echo "ERROR: ${#FAILED[@]} platform(s) did not complete successfully:" >&2
  for f in "${FAILED[@]}"; do
    echo "  - $f" >&2
  done
  echo "" >&2
  echo "See per-platform logs under $LOGS_DIR/" >&2
  exit 1
fi

echo ""
echo "All 6 platforms built successfully."
exit 0
