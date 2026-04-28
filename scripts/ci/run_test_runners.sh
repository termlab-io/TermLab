#!/usr/bin/env bash
#
# Runs the full TermLab test suite in a single JVM via jps-bootstrap.
#
# TermLabRunTestsBuildTarget delegates to intellij-community's TestingTasks
# infrastructure, which constructs a proper test classpath (including test/
# source-folder outputs from intellij.termlab.tests' transitive deps) and
# launches JUnit. This is the same pattern intellij-community uses to run
# its own tests via CommunityRunTestsBuildTarget.
#
# Reads INTELLIJ_ROOT from environment (set by CI bootstrap step).

set -euo pipefail

INTELLIJ_ROOT="${INTELLIJ_ROOT:?INTELLIJ_ROOT must be set}"

JPS_BOOTSTRAP="$INTELLIJ_ROOT/platform/jps-bootstrap/jps-bootstrap.sh"
if [ ! -x "$JPS_BOOTSTRAP" ]; then
  echo "ERROR: jps-bootstrap.sh not found or not executable at $JPS_BOOTSTRAP" >&2
  exit 2
fi

exec "$JPS_BOOTSTRAP" \
  "$INTELLIJ_ROOT" \
  intellij.termlab.build \
  TermLabRunTestsBuildTarget
