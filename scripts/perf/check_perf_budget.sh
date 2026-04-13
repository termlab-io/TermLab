#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  check_perf_budget.sh [options]

Options:
  --summary <file>   Summary env/json file (default: perf-results/latest_summary.env,
                     falls back to perf-results/latest_summary.json)
  --strict           Exit non-zero if Stage A gate is not met.
USAGE
}

parse_env_value() {
  local key="$1"
  local file="$2"
  awk -F= -v key="$key" '$1 == key {print $2; exit}' "$file"
}

parse_json_number() {
  local key="$1"
  local file="$2"
  sed -n -E "s/^[[:space:]]*\"${key}\"[[:space:]]*:[[:space:]]*([0-9.]+).*/\1/p" "$file" | head -n 1
}

parse_json_bool() {
  local key="$1"
  local file="$2"
  sed -n -E "s/^[[:space:]]*\"${key}\"[[:space:]]*:[[:space:]]*(true|false).*/\1/p" "$file" | head -n 1
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

SUMMARY_FILE=""
STRICT=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --summary)
      SUMMARY_FILE="$2"
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

if [[ -z "$SUMMARY_FILE" ]]; then
  if [[ -f "$REPO_ROOT/perf-results/latest_summary.env" ]]; then
    SUMMARY_FILE="$REPO_ROOT/perf-results/latest_summary.env"
  else
    SUMMARY_FILE="$REPO_ROOT/perf-results/latest_summary.json"
  fi
fi

if [[ ! -f "$SUMMARY_FILE" ]]; then
  echo "ERROR: summary file not found: $SUMMARY_FILE" >&2
  exit 1
fi

AVG_RSS_MB=""
AVG_CPU=""
STAGE_A_PASS=""
STAGE_B_PASS=""

if [[ "$SUMMARY_FILE" == *.env ]]; then
  AVG_RSS_MB="$(parse_env_value avg_rss_mb "$SUMMARY_FILE")"
  AVG_CPU="$(parse_env_value avg_cpu_pct "$SUMMARY_FILE")"
  STAGE_A_PASS="$(parse_env_value stage_a_pass "$SUMMARY_FILE")"
  STAGE_B_PASS="$(parse_env_value stage_b_pass "$SUMMARY_FILE")"
else
  AVG_RSS_MB="$(parse_json_number avg_rss_mb "$SUMMARY_FILE")"
  AVG_CPU="$(parse_json_number avg_cpu_pct "$SUMMARY_FILE")"
  STAGE_A_PASS="$(parse_json_bool pass "$SUMMARY_FILE" | sed -n '1p')"
  STAGE_B_PASS="$(parse_json_bool pass "$SUMMARY_FILE" | sed -n '2p')"
fi

if [[ -z "$AVG_RSS_MB" || -z "$AVG_CPU" || -z "$STAGE_A_PASS" ]]; then
  echo "ERROR: could not parse required metrics from $SUMMARY_FILE" >&2
  exit 1
fi

echo "==> Perf budget check"
echo "    source:        $SUMMARY_FILE"
echo "    avg RSS:       ${AVG_RSS_MB} MB"
echo "    avg CPU:       ${AVG_CPU}%"
echo "    Stage A pass:  ${STAGE_A_PASS} (<=200MB RSS and <=1% avg CPU)"
if [[ -n "$STAGE_B_PASS" ]]; then
  echo "    Stage B pass:  ${STAGE_B_PASS} (<=100MB RSS and <=1% avg CPU)"
fi

if [[ "$STRICT" -eq 1 && "$STAGE_A_PASS" != "true" ]]; then
  echo "ERROR: Stage A gate failed in strict mode." >&2
  exit 2
fi

exit 0
