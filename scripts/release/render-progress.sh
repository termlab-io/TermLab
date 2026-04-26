#!/usr/bin/env bash
#
# render-progress.sh — render TermLab release build progress.
#
# Polls $LOGS_DIR/*.state and $LOGS_DIR/*.log to render a status block.
# In TTY mode, redraws in place every 2s. In non-TTY mode, prints one
# line per state change. Killed by parent (build-installers.sh) when
# builds are complete; exit-trap restores the cursor.
#
# Usage:
#   render-progress.sh LOGS_DIR

set -u

LOGS_DIR="${1:?usage: render-progress.sh LOGS_DIR}"
START_EPOCH="$(date +%s)"

MATRIX=(
  "mac-aarch64"
  "mac-x64"
  "linux-aarch64"
  "linux-x64"
  "windows-aarch64"
  "windows-x64"
)

if [ -t 1 ]; then
  TTY_MODE=1
else
  TTY_MODE=0
fi

declare -A prev_state
for p in "${MATRIX[@]}"; do
  prev_state["$p"]=""
done

cleanup() {
  if [ "$TTY_MODE" -eq 1 ]; then
    tput cnorm 2>/dev/null || true
  fi
}
trap cleanup EXIT

on_term() {
  if [ "$TTY_MODE" -eq 1 ]; then
    render_tty
  else
    render_non_tty
  fi
  exit 0
}
trap on_term TERM

elapsed_str() {
  local now elapsed
  now="$(date +%s)"
  elapsed=$((now - START_EPOCH))
  printf '%dm%02ds' $((elapsed / 60)) $((elapsed % 60))
}

stage_for() {
  local platform="$1"
  local log="$LOGS_DIR/${platform}.log"
  if [ ! -f "$log" ]; then
    echo "starting"
    return
  fi
  # Last [INFO] line, stripped, truncated to 60 chars.
  local line
  line="$(grep '^\[INFO\] ' "$log" 2>/dev/null | tail -n1 | sed 's/^\[INFO\] //')"
  if [ -z "$line" ]; then
    echo "starting"
  else
    echo "${line:0:60}"
  fi
}

render_tty() {
  # Block is 8 lines: header + blank + 6 platform rows.
  printf '\033[8A\033[J'   # cursor up 8, clear to end of screen
  printf 'TermLab Release Build  —  %s elapsed\n\n' "$(elapsed_str)"
  for p in "${MATRIX[@]}"; do
    local state mark stage
    state="$(cat "$LOGS_DIR/${p}.state" 2>/dev/null || echo '')"
    case "$state" in
      done)    mark='✓'; stage='complete' ;;
      failed)  mark='✗'; stage='FAILED'   ;;
      running) mark='⌛'; stage="$(stage_for "$p")" ;;
      *)       mark=' '; stage='queued'   ;;
    esac
    printf '  %s %-18s %s\n' "$mark" "$p" "$stage"
  done
}

render_non_tty() {
  for p in "${MATRIX[@]}"; do
    local state
    state="$(cat "$LOGS_DIR/${p}.state" 2>/dev/null || echo '')"
    if [ "$state" != "${prev_state[$p]}" ]; then
      printf '[%s] %s: %s\n' "$(elapsed_str)" "$p" "${state:-pending}"
      prev_state["$p"]="$state"
    fi
  done
}

if [ "$TTY_MODE" -eq 1 ]; then
  tput civis 2>/dev/null || true
  # Reserve 8 lines for the block.
  for _ in 1 2 3 4 5 6 7 8; do printf '\n'; done
fi

while true; do
  if [ "$TTY_MODE" -eq 1 ]; then
    render_tty
  else
    render_non_tty
  fi
  sleep 2
done
