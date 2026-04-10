#!/usr/bin/env bash
#
# setup.sh — bootstrap a Conch dev environment
#
# Conch builds inside an intellij-community checkout. This script:
#   1. Shallow-clones intellij-community at the SHA pinned in INTELLIJ_REF
#      (~1-2 GB instead of the full ~30 GB history).
#   2. Symlinks this conch_workbench checkout into intellij-community/conch
#      so Bazel can find the conch source where it expects it.
#
# Usage:
#   ./setup.sh                  # default: ../intellij-community
#   ./setup.sh ~/projects/conch # custom location for the intellij checkout
#
# Re-running is safe — existing intellij checkouts and symlinks are left alone.

set -euo pipefail

WORKBENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INTELLIJ_DIR="${1:-$(dirname "$WORKBENCH_DIR")/intellij-community}"
INTELLIJ_REPO="https://github.com/JetBrains/intellij-community.git"

if [ ! -f "$WORKBENCH_DIR/INTELLIJ_REF" ]; then
  echo "ERROR: INTELLIJ_REF not found at $WORKBENCH_DIR/INTELLIJ_REF" >&2
  exit 1
fi
INTELLIJ_REF="$(tr -d '[:space:]' < "$WORKBENCH_DIR/INTELLIJ_REF")"

echo "==> Conch workbench: $WORKBENCH_DIR"
echo "==> intellij-community target: $INTELLIJ_DIR"
echo "==> Pinned upstream ref: $INTELLIJ_REF"
echo

if [ -d "$INTELLIJ_DIR/.git" ]; then
  echo "==> intellij-community already exists at $INTELLIJ_DIR"
  echo "    skipping clone (delete the directory to re-clone)"
else
  echo "==> Shallow-cloning intellij-community at $INTELLIJ_REF"
  echo "    (fetches ~1-2 GB; full clone would be ~30 GB)"
  mkdir -p "$INTELLIJ_DIR"
  git -C "$INTELLIJ_DIR" init --quiet
  git -C "$INTELLIJ_DIR" remote add origin "$INTELLIJ_REPO"
  git -C "$INTELLIJ_DIR" fetch --depth 1 origin "$INTELLIJ_REF"
  git -C "$INTELLIJ_DIR" checkout --quiet FETCH_HEAD
  echo "    done"
fi

echo

CONCH_LINK="$INTELLIJ_DIR/conch"
if [ -L "$CONCH_LINK" ]; then
  CURRENT_TARGET="$(readlink "$CONCH_LINK")"
  if [ "$CURRENT_TARGET" = "$WORKBENCH_DIR" ]; then
    echo "==> Symlink $CONCH_LINK → $WORKBENCH_DIR already in place"
  else
    echo "==> Symlink $CONCH_LINK exists but points elsewhere ($CURRENT_TARGET)"
    echo "    Replacing it with → $WORKBENCH_DIR"
    rm "$CONCH_LINK"
    ln -s "$WORKBENCH_DIR" "$CONCH_LINK"
  fi
elif [ -e "$CONCH_LINK" ]; then
  echo "ERROR: $CONCH_LINK exists and is not a symlink." >&2
  echo "       Move it aside and re-run setup." >&2
  exit 1
else
  echo "==> Symlinking conch_workbench into intellij-community"
  ln -s "$WORKBENCH_DIR" "$CONCH_LINK"
fi

echo
# Record the resolved intellij-community path so the Makefile can find it
# whether you run 'make' from the workbench dir or from the symlink.
echo "$INTELLIJ_DIR" > "$WORKBENCH_DIR/.intellij-root"
echo "==> Wrote $WORKBENCH_DIR/.intellij-root"

echo
# Wire up the IntelliJ run configuration and module registrations so the user
# can run/debug Conch from inside IntelliJ. Idempotent.
"$WORKBENCH_DIR/scripts/install-idea-config.sh" "$INTELLIJ_DIR"

echo
echo "✓ Setup complete."
echo
echo "  intellij-community: $INTELLIJ_DIR"
echo "  conch_workbench:    $WORKBENCH_DIR"
echo "  symlink:            $CONCH_LINK → $WORKBENCH_DIR"
echo
echo "Build and run Conch:"
echo "  cd $WORKBENCH_DIR && make conch"
