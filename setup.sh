#!/usr/bin/env bash
#
# setup.sh — bootstrap a TermLab dev environment
#
# TermLab builds inside an intellij-community checkout. This script:
#   1. Shallow-clones intellij-community at the SHA pinned in INTELLIJ_REF
#      (~1-2 GB instead of the full ~30 GB history).
#   2. Symlinks this termlab_workbench checkout into intellij-community/termlab
#      so Bazel can find the termlab source where it expects it.
#
# Usage:
#   ./setup.sh                  # default: ../intellij-community
#   ./setup.sh ~/projects/termlab # custom location for the intellij checkout
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

echo "==> TermLab workbench: $WORKBENCH_DIR"
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

TERMLAB_LINK="$INTELLIJ_DIR/termlab"
if [ -L "$TERMLAB_LINK" ]; then
  CURRENT_TARGET="$(readlink "$TERMLAB_LINK")"
  if [ "$CURRENT_TARGET" = "$WORKBENCH_DIR" ]; then
    echo "==> Symlink $TERMLAB_LINK → $WORKBENCH_DIR already in place"
  else
    echo "==> Symlink $TERMLAB_LINK exists but points elsewhere ($CURRENT_TARGET)"
    echo "    Replacing it with → $WORKBENCH_DIR"
    rm "$TERMLAB_LINK"
    ln -s "$WORKBENCH_DIR" "$TERMLAB_LINK"
  fi
elif [ -e "$TERMLAB_LINK" ]; then
  echo "ERROR: $TERMLAB_LINK exists and is not a symlink." >&2
  echo "       Move it aside and re-run setup." >&2
  exit 1
else
  echo "==> Symlinking termlab_workbench into intellij-community"
  ln -s "$WORKBENCH_DIR" "$TERMLAB_LINK"
fi

echo
# Record the resolved intellij-community path so the Makefile can find it
# whether you run 'make' from the workbench dir or from the symlink.
echo "$INTELLIJ_DIR" > "$WORKBENCH_DIR/.intellij-root"
echo "==> Wrote $WORKBENCH_DIR/.intellij-root"

echo
# Wire up the IntelliJ run configuration and module registrations so the user
# can run/debug TermLab from inside IntelliJ. Idempotent.
"$WORKBENCH_DIR/scripts/install-idea-config.sh" "$INTELLIJ_DIR"

echo
echo "✓ Setup complete."
echo
echo "  intellij-community: $INTELLIJ_DIR"
echo "  termlab_workbench:    $WORKBENCH_DIR"
echo "  symlink:            $TERMLAB_LINK → $WORKBENCH_DIR"
echo
echo "Build and run TermLab:"
echo "  cd $WORKBENCH_DIR && make termlab"
