#!/usr/bin/env bash

set -euo pipefail

WORKBENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
INTELLIJ_DIR="${1:-$(dirname "$WORKBENCH_DIR")/intellij-community}"
INTELLIJ_REPO="https://github.com/JetBrains/intellij-community.git"
ANDROID_DIR="$INTELLIJ_DIR/android"
ANDROID_REPO="git://git.jetbrains.org/idea/android.git"

if [ ! -f "$WORKBENCH_DIR/INTELLIJ_REF" ]; then
  echo "ERROR: INTELLIJ_REF not found at $WORKBENCH_DIR/INTELLIJ_REF" >&2
  exit 1
fi

INTELLIJ_REF="$(tr -d '[:space:]' < "$WORKBENCH_DIR/INTELLIJ_REF")"

if [ -z "${ANDROID_REF:-}" ]; then
  if [ -f "$WORKBENCH_DIR/ANDROID_REF" ]; then
    ANDROID_REF="$(tr -d '[:space:]' < "$WORKBENCH_DIR/ANDROID_REF")"
  else
    ANDROID_REF="master"
  fi
fi

git_enable_longpaths() {
  if [[ "${OS:-}" == "Windows_NT" ]]; then
    git config --global core.longpaths true
  fi
}

checkout_git_ref() {
  local repo_dir="$1"
  local ref="$2"

  git -C "$repo_dir" fetch --depth 1 origin "$ref"
  git -C "$repo_dir" checkout --quiet FETCH_HEAD
}

git_enable_longpaths

if [ -d "$INTELLIJ_DIR/.git" ]; then
  git -C "$INTELLIJ_DIR" config core.longpaths true
  CURRENT_HEAD="$(git -C "$INTELLIJ_DIR" rev-parse HEAD)"
  if [ "$CURRENT_HEAD" != "$INTELLIJ_REF" ]; then
    echo "==> Resetting existing intellij-community checkout to $INTELLIJ_REF"
    checkout_git_ref "$INTELLIJ_DIR" "$INTELLIJ_REF"
  else
    echo "==> Reusing existing intellij-community checkout at $INTELLIJ_REF"
  fi
else
  echo "==> Shallow-cloning intellij-community at $INTELLIJ_REF"
  mkdir -p "$INTELLIJ_DIR"
  git -C "$INTELLIJ_DIR" init --quiet
  git -C "$INTELLIJ_DIR" config core.longpaths true
  git -C "$INTELLIJ_DIR" remote add origin "$INTELLIJ_REPO"
  checkout_git_ref "$INTELLIJ_DIR" "$INTELLIJ_REF"
fi

if [ -d "$ANDROID_DIR/.git" ]; then
  git -C "$ANDROID_DIR" config core.longpaths true
  CURRENT_ANDROID_HEAD="$(git -C "$ANDROID_DIR" rev-parse HEAD)"
  TARGET_ANDROID_HEAD="$(git -C "$ANDROID_DIR" rev-parse --verify --quiet "origin/$ANDROID_REF" || true)"
  if [ -z "$TARGET_ANDROID_HEAD" ] || [ "$CURRENT_ANDROID_HEAD" != "$TARGET_ANDROID_HEAD" ]; then
    echo "==> Resetting Android modules checkout to $ANDROID_REF"
    checkout_git_ref "$ANDROID_DIR" "$ANDROID_REF"
  else
    echo "==> Reusing existing Android modules checkout at $CURRENT_ANDROID_HEAD"
  fi
elif [ -e "$ANDROID_DIR" ]; then
  echo "ERROR: $ANDROID_DIR exists and is not an Android git checkout" >&2
  exit 1
else
  echo "==> Shallow-cloning Android modules at $ANDROID_REF"
  mkdir -p "$ANDROID_DIR"
  git -C "$ANDROID_DIR" init --quiet
  git -C "$ANDROID_DIR" config core.longpaths true
  git -C "$ANDROID_DIR" remote add origin "$ANDROID_REPO"
  checkout_git_ref "$ANDROID_DIR" "$ANDROID_REF"
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
"$WORKBENCH_DIR/scripts/install-idea-config.sh" "$INTELLIJ_DIR"
echo "==> IntelliJ root ready at $INTELLIJ_DIR"
