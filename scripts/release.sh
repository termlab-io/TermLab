#!/usr/bin/env bash
#
# release.sh — build TermLab installer artifacts locally and optionally
# upload them to an existing GitHub release.
#
# Flow:
#   1. Require a clean git tree (offer to stash if dirty).
#   2. Pick a GitHub release (draft/prerelease/latest) interactively.
#   3. Ask up front whether to upload artifacts after the build.
#   4. Check out the release's tag.
#   5. Run `make termlab-installers` (builds .dmg/.sit for both Mac archs,
#      .tar.gz for Linux, .exe/.win.zip for both Windows archs).
#   6. Show the artifact list.
#   7. If upload was requested, `gh release upload` everything.
#   8. Restore the original branch/commit and, if a stash was created,
#      prompt to pop it.

set -euo pipefail

WORKBENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$WORKBENCH_DIR"

bold() { printf '\033[1m%s\033[0m' "$*"; }
red()  { printf '\033[31m%s\033[0m' "$*"; }
dim()  { printf '\033[2m%s\033[0m' "$*"; }

ask() {
  local prompt="$1" ans
  while true; do
    read -r -p "$prompt [y/n] " ans
    case "${ans:-}" in
      y|Y|yes|YES) return 0 ;;
      n|N|no|NO)   return 1 ;;
      *) echo "Please answer y or n." ;;
    esac
  done
}

command -v gh >/dev/null 2>&1 || { echo "$(red "gh (GitHub CLI) not found on PATH.")"; exit 1; }

# --- 1. Require clean tree ---------------------------------------------------

STASHED=0
if ! git diff --quiet \
   || ! git diff --cached --quiet \
   || [ -n "$(git ls-files --others --exclude-standard)" ]; then
  echo "$(red "Working tree is not clean:")"
  git status --short
  echo ""
  if ask "Stash changes (including untracked) before continuing?"; then
    git stash push --include-untracked -m "release.sh auto-stash $(date +%s)" >/dev/null
    STASHED=1
    echo "$(dim "Stashed. Will prompt to pop at the end.")"
  else
    echo "Aborting."
    exit 1
  fi
fi

# Capture the ref we return to when we're done. Prefer branch name; fall
# back to the commit SHA for detached HEAD.
ORIGINAL_REF="$(git symbolic-ref -q --short HEAD || git rev-parse HEAD)"

cleanup() {
  local ec=$?
  echo ""
  echo "Restoring original ref: $(bold "$ORIGINAL_REF")"
  git checkout --quiet "$ORIGINAL_REF" 2>/dev/null || \
    echo "$(red "Could not restore to $ORIGINAL_REF — check 'git status'.")"
  if [ "$STASHED" -eq 1 ]; then
    echo ""
    if ask "Pop the auto-stash now?"; then
      git stash pop
    else
      echo "$(dim "Stash kept — list with: git stash list")"
    fi
  fi
  exit "$ec"
}
trap cleanup EXIT

# --- 2. Pick a release -------------------------------------------------------

echo ""
echo "$(bold "Fetching releases from GitHub...")"
mapfile -t RELEASES < <(gh release list --limit 30 \
  --json tagName,isDraft,isPrerelease,isLatest,publishedAt \
  --jq '.[] | [.tagName, (.isDraft|tostring), (.isPrerelease|tostring), (.isLatest|tostring), (.publishedAt // "-")] | @tsv')

if [ "${#RELEASES[@]}" -eq 0 ]; then
  echo "$(red "No releases found on origin.") Run the Prepare Release workflow first."
  exit 1
fi

echo ""
echo "$(bold "Available releases:")"
for i in "${!RELEASES[@]}"; do
  IFS=$'\t' read -r TAG IS_DRAFT IS_PRE IS_LATEST PUBLISHED <<< "${RELEASES[$i]}"
  flags=""
  [ "$IS_DRAFT" = "true" ]  && flags+=" [draft]"
  [ "$IS_PRE" = "true" ]    && flags+=" [pre]"
  [ "$IS_LATEST" = "true" ] && flags+=" [latest]"
  printf "  %2d) %-40s%s  %s\n" "$((i+1))" "$TAG" "$flags" "$(dim "${PUBLISHED%T*}")"
done

echo ""
read -r -p "Pick a release [1-${#RELEASES[@]}]: " CHOICE
if ! [[ "$CHOICE" =~ ^[0-9]+$ ]] || [ "$CHOICE" -lt 1 ] || [ "$CHOICE" -gt "${#RELEASES[@]}" ]; then
  echo "$(red "Invalid choice: $CHOICE")"
  exit 1
fi
IFS=$'\t' read -r TAG _ _ _ _ <<< "${RELEASES[$((CHOICE-1))]}"
echo "Selected: $(bold "$TAG")"

# --- 3. Ask about upload up front --------------------------------------------

echo ""
if ask "Upload artifacts to release $TAG after the build completes?"; then
  UPLOAD=1
else
  UPLOAD=0
fi

# --- 4. Check out the tag ----------------------------------------------------

echo ""
echo "$(bold "Fetching and checking out tag $TAG...")"
git fetch origin "refs/tags/$TAG:refs/tags/$TAG" 2>/dev/null || true
git checkout --quiet "refs/tags/$TAG"

# --- 5. Build ----------------------------------------------------------------

echo ""
echo "$(bold "Running 'make termlab-installers' — this takes a while on a cold cache.")"
echo ""
make termlab-installers

# --- 6. List artifacts -------------------------------------------------------

INTELLIJ_ROOT_FILE="$WORKBENCH_DIR/.intellij-root"
if [ -n "${INTELLIJ_ROOT:-}" ]; then
  :
elif [ -f "$INTELLIJ_ROOT_FILE" ]; then
  INTELLIJ_ROOT="$(tr -d '[:space:]' < "$INTELLIJ_ROOT_FILE")"
else
  INTELLIJ_ROOT="$(dirname "$WORKBENCH_DIR")/intellij-community"
fi
ARTIFACT_DIR="$INTELLIJ_ROOT/out/termlab/artifacts"

if [ ! -d "$ARTIFACT_DIR" ]; then
  echo "$(red "Artifact directory not found: $ARTIFACT_DIR")"
  exit 1
fi

mapfile -t FILES < <(find "$ARTIFACT_DIR" -maxdepth 1 -type f \
  \( -name '*.dmg' -o -name '*.sit' -o -name '*.tar.gz' \
     -o -name '*.exe' -o -name '*.zip' \) | sort)

echo ""
echo "$(bold "Artifacts in $ARTIFACT_DIR:")"
if [ "${#FILES[@]}" -eq 0 ]; then
  echo "  $(red "(none found)")"
  exit 1
fi
for f in "${FILES[@]}"; do
  size="$(du -h "$f" | awk '{print $1}')"
  printf "  %s  %s\n" "$(basename "$f")" "$(dim "($size)")"
done

# --- 7. Upload ---------------------------------------------------------------

if [ "$UPLOAD" -eq 1 ]; then
  echo ""
  echo "$(bold "Uploading ${#FILES[@]} artifact(s) to $TAG...")"
  gh release upload "$TAG" "${FILES[@]}" --clobber
  echo ""
  echo "$(bold "Upload complete.")"
  gh release view "$TAG" --web >/dev/null 2>&1 || true
else
  echo ""
  echo "$(dim "Skipping upload. Run 'gh release upload $TAG <files> --clobber' to publish later.")"
fi
