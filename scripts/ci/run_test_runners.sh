#!/usr/bin/env bash
#
# Iterates per-module TermLab TestRunner main classes via jps-bootstrap.
# Each module's TestRunner is a JUnit5-launcher entry point that runs
# that module's test suite. Failure in one module does NOT short-circuit;
# all modules run and the script exits non-zero only if any failed.
#
# Reads INTELLIJ_ROOT from environment (set by CI bootstrap step).

set -uo pipefail

INTELLIJ_ROOT="${INTELLIJ_ROOT:?INTELLIJ_ROOT must be set}"

JPS_BOOTSTRAP="$INTELLIJ_ROOT/platform/jps-bootstrap/jps-bootstrap.sh"
if [ ! -x "$JPS_BOOTSTRAP" ]; then
  echo "ERROR: jps-bootstrap.sh not found or not executable at $JPS_BOOTSTRAP" >&2
  exit 2
fi

# Each entry: <human_name>:<iml_module_name>:<main_class_fqcn>
TEST_MODULES=(
  "core:intellij.termlab.core:com.termlab.core.TestRunner"
  "vault:intellij.termlab.vault:com.termlab.vault.TestRunner"
  "ssh:intellij.termlab.ssh:com.termlab.ssh.TestRunner"
  "sftp:intellij.termlab.sftp:com.termlab.sftp.TestRunner"
  "share:intellij.termlab.share:com.termlab.share.TestRunner"
  "tunnels:intellij.termlab.tunnels:com.termlab.tunnels.TestRunner"
  "editor:intellij.termlab.editor:com.termlab.editor.TestRunner"
  "search:intellij.termlab.search:com.termlab.search.TestRunner"
  "sysinfo:intellij.termlab.sysinfo:com.termlab.sysinfo.TestRunner"
  "runner:intellij.termlab.runner:com.termlab.runner.TestRunner"
  "proxmox:intellij.termlab.proxmox:com.termlab.proxmox.TestRunner"
)

failures=()
for entry in "${TEST_MODULES[@]}"; do
  IFS=: read -r name module main_class <<<"$entry"
  echo "::group::Testing $name"
  if ! "$JPS_BOOTSTRAP" "$INTELLIJ_ROOT" "$module" "$main_class"; then
    failures+=("$name")
  fi
  echo "::endgroup::"
done

if [ ${#failures[@]} -gt 0 ]; then
  echo "::error::Test failures in modules: ${failures[*]}"
  exit 1
fi

echo "OK — all ${#TEST_MODULES[@]} module test runners passed."
