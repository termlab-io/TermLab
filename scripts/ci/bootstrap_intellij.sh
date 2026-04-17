#!/usr/bin/env bash

set -euo pipefail

WORKBENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
INTELLIJ_DIR="${1:-$(dirname "$WORKBENCH_DIR")/intellij-community}"
INTELLIJ_REPO="https://github.com/JetBrains/intellij-community.git"

if [ ! -f "$WORKBENCH_DIR/INTELLIJ_REF" ]; then
  echo "ERROR: INTELLIJ_REF not found at $WORKBENCH_DIR/INTELLIJ_REF" >&2
  exit 1
fi

INTELLIJ_REF="$(tr -d '[:space:]' < "$WORKBENCH_DIR/INTELLIJ_REF")"

if [ -d "$INTELLIJ_DIR/.git" ]; then
  CURRENT_HEAD="$(git -C "$INTELLIJ_DIR" rev-parse HEAD)"
  if [ "$CURRENT_HEAD" != "$INTELLIJ_REF" ]; then
    echo "==> Resetting existing intellij-community checkout to $INTELLIJ_REF"
    git -C "$INTELLIJ_DIR" fetch --depth 1 origin "$INTELLIJ_REF"
    git -C "$INTELLIJ_DIR" checkout --quiet FETCH_HEAD
  else
    echo "==> Reusing existing intellij-community checkout at $INTELLIJ_REF"
  fi
else
  echo "==> Shallow-cloning intellij-community at $INTELLIJ_REF"
  mkdir -p "$INTELLIJ_DIR"
  git -C "$INTELLIJ_DIR" init --quiet
  git -C "$INTELLIJ_DIR" remote add origin "$INTELLIJ_REPO"
  git -C "$INTELLIJ_DIR" fetch --depth 1 origin "$INTELLIJ_REF"
  git -C "$INTELLIJ_DIR" checkout --quiet FETCH_HEAD
fi

TERMLAB_LINK="$INTELLIJ_DIR/termlab"
if [ -L "$TERMLAB_LINK" ]; then
  CURRENT_TARGET="$(readlink "$TERMLAB_LINK")"
  if [ "$CURRENT_TARGET" != "$WORKBENCH_DIR" ]; then
    rm "$TERMLAB_LINK"
    ln -s "$WORKBENCH_DIR" "$TERMLAB_LINK"
  fi
elif [ -e "$TERMLAB_LINK" ]; then
  echo "ERROR: $TERMLAB_LINK exists and is not a symlink" >&2
  exit 1
else
  ln -s "$WORKBENCH_DIR" "$TERMLAB_LINK"
fi

echo "$INTELLIJ_DIR" > "$WORKBENCH_DIR/.intellij-root"
echo "==> IntelliJ root ready at $INTELLIJ_DIR"
