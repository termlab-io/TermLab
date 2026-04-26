#!/usr/bin/env bash
#
# release.sh — build TermLab installer artifacts locally and optionally
# upload them to an existing GitHub release.
#
# Usage:
#   ./scripts/release.sh                — interactive release flow
#   ./scripts/release.sh --dry-run|-n   — local pipeline test, no GitHub
#   ./scripts/release.sh --jobs N|-j N  — parallel build concurrency (1-6, default 3)
#   ./scripts/release.sh --no-warmup    — skip serial warmup, fan out all 6
#   ./scripts/release.sh --help|-h      — show this header
#
# Flow:
#   1. Pick a GitHub release (draft/prerelease/latest) interactively.
#   2. Choose one mode:
#      - build and upload
#      - build only
#      - upload existing artifacts only
#   3. For build modes only:
#      - require a clean git tree (offer to stash if dirty)
#      - check out the release's tag
#      - sync intellij-community/android to the pinned SHAs
#      - run `make termlab-installers`
#      - generate updates.xml from the produced product-info.json files
#      - restore the original branch/commit and any auto-stash
#   4. Show the artifact list.
#   5. If upload was requested, `gh release upload` everything.
#
# Dry-run mode (--dry-run): skips steps 1, 2, the tag checkout in 3, and the
# upload in 5. Stays on the current branch, synthesizes a tag like
# `dryrun-<branch>-<sha>` for use in updates.xml URLs, allows a dirty
# workbench tree, runs the bootstrap + build + updates.xml generation, then
# stops. Use this to verify the full pipeline (including updates.xml shape)
# locally before cutting a real release on GitHub.

set -euo pipefail

WORKBENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$WORKBENCH_DIR"

BUILD_INSTALLERS_SH="$WORKBENCH_DIR/scripts/release/build-installers.sh"
if [ ! -x "$BUILD_INSTALLERS_SH" ]; then
  echo "ERROR: missing or non-executable: $BUILD_INSTALLERS_SH" >&2
  exit 1
fi

bold() { printf '\033[1m%s\033[0m' "$*"; }
red()  { printf '\033[31m%s\033[0m' "$*"; }
dim()  { printf '\033[2m%s\033[0m' "$*"; }

sync_termlab_idea_config() {
  local intellij_dir="$1"
  local modules_xml="$intellij_dir/.idea/modules.xml"
  local run_config_dir="$intellij_dir/.idea/runConfigurations"
  local run_config_src="$WORKBENCH_DIR/scripts/idea/TermLab.run.xml"
  local run_config_dest="$run_config_dir/TermLab.xml"
  local anchor='intellij.compose.ide.plugin.shared.tests.iml'

  mkdir -p "$run_config_dir"
  cp "$run_config_src" "$run_config_dest"

  if [ ! -f "$modules_xml" ]; then
    echo "$(red "Missing IntelliJ modules.xml at $modules_xml")"
    exit 1
  fi

  mapfile -t termlab_modules < <(python3 - "$WORKBENCH_DIR" <<'PYEOF'
import os
import sys

root = sys.argv[1]
modules = []

for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = [d for d in dirnames if d not in {".git", ".idea", "out"}]
    for filename in filenames:
        if not filename.startswith("intellij.termlab") or not filename.endswith(".iml"):
            continue
        rel_path = os.path.relpath(os.path.join(dirpath, filename), root).replace(os.sep, "/")
        modules.append(f"termlab/{rel_path}")

for module in sorted(modules):
    print(module)
PYEOF
  )

  python3 - "$modules_xml" "$anchor" "${termlab_modules[@]}" <<'PYEOF'
import sys

path, anchor, *modules = sys.argv[1:]
desired_lines = [
    f'      <module fileurl="file://$PROJECT_DIR$/{module}" filepath="$PROJECT_DIR$/{module}" />\n'
    for module in modules
]

with open(path, encoding="utf-8") as fh:
    lines = fh.readlines()

filtered = []
anchor_index = None
for line in lines:
    if 'fileurl="file://$PROJECT_DIR$/termlab/' in line:
        continue
    filtered.append(line)
    if anchor in line and anchor_index is None:
        anchor_index = len(filtered)

if anchor_index is None:
    sys.exit("anchor not found")

updated = filtered[:anchor_index] + desired_lines + filtered[anchor_index:]
with open(path, "w", encoding="utf-8") as fh:
    fh.writelines(updated)
PYEOF
}

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

# --- 0. Argument parsing -----------------------------------------------------

DRY_RUN=0
JOBS=3
NO_WARMUP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run|-n)
      DRY_RUN=1
      shift
      ;;
    --jobs|-j)
      if [ $# -lt 2 ] || [[ "$2" =~ ^- ]]; then
        echo "$(red "$1 requires a numeric value (1-6).")"
        echo "Run with --help for usage."
        exit 1
      fi
      if ! [[ "$2" =~ ^[1-6]$ ]]; then
        echo "$(red "$1 must be an integer between 1 and 6, got: $2")"
        echo "Run with --help for usage."
        exit 1
      fi
      JOBS="$2"
      shift 2
      ;;
    --no-warmup)
      NO_WARMUP=1
      shift
      ;;
    -h|--help)
      sed -n '2,/^$/p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
    *)
      echo "$(red "Unknown argument: $1")"
      echo "Run with --help for usage."
      exit 1
      ;;
  esac
done

# Real-release mode needs gh for the release picker and upload. Dry-run
# bypasses both, so gh is only required when we're actually going to talk
# to GitHub.
if [ "$DRY_RUN" -eq 0 ]; then
  command -v gh >/dev/null 2>&1 || { echo "$(red "gh (GitHub CLI) not found on PATH.")"; exit 1; }
fi

FALLBACK_ANDROID_REF=""
if [ -f "$WORKBENCH_DIR/ANDROID_REF" ]; then
  FALLBACK_ANDROID_REF="$(tr -d '[:space:]' < "$WORKBENCH_DIR/ANDROID_REF")"
fi

if [ "$DRY_RUN" -eq 1 ]; then
  # --- 1+2. Dry-run: synthesize a tag from the current ref, force the
  # build-only path. The synthesized tag flows into updates.xml's <button>
  # URL so the produced file is shape-correct even though the URL points
  # at a release that doesn't exist.
  CURRENT_BRANCH="$(git -C "$WORKBENCH_DIR" symbolic-ref -q --short HEAD || echo detached)"
  CURRENT_SHA="$(git -C "$WORKBENCH_DIR" rev-parse --short HEAD)"
  TAG="dryrun-${CURRENT_BRANCH}-${CURRENT_SHA}"
  BUILD=1
  UPLOAD=0
  echo ""
  echo "$(bold "Dry-run mode") — building from $(bold "${CURRENT_BRANCH}@${CURRENT_SHA}")"
  echo "  Synthetic tag : $TAG"
  echo "  $(dim "(no GitHub release will be touched, no upload will happen)")"
else
  # --- 1. Pick a release -----------------------------------------------------

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

  # --- 2. Pick a mode --------------------------------------------------------

  echo ""
  echo "$(bold "What do you want to do with $TAG?")"
  echo "  1) Build fresh artifacts and upload them"
  echo "  2) Build fresh artifacts only"
  echo "  3) Upload existing artifacts only"
  echo ""
  read -r -p "Pick an action [1-3]: " ACTION
  case "${ACTION:-}" in
    1)
      BUILD=1
      UPLOAD=1
      ;;
    2)
      BUILD=1
      UPLOAD=0
      ;;
    3)
      BUILD=0
      UPLOAD=1
      ;;
    *)
      echo "$(red "Invalid choice: ${ACTION:-}")"
      exit 1
      ;;
  esac
fi

INTELLIJ_ROOT_FILE="$WORKBENCH_DIR/.intellij-root"
if [ -n "${INTELLIJ_ROOT:-}" ]; then
  :
elif [ -f "$INTELLIJ_ROOT_FILE" ]; then
  INTELLIJ_ROOT="$(tr -d '[:space:]' < "$INTELLIJ_ROOT_FILE")"
else
  INTELLIJ_ROOT="$(dirname "$WORKBENCH_DIR")/intellij-community"
fi

if [ "$BUILD" -eq 1 ]; then
  if [ "$DRY_RUN" -eq 0 ]; then
    # --- 3. Require clean tree -----------------------------------------------

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
      git restore --source=HEAD --staged --worktree customization/resources/idea/TermLabApplicationInfo.xml 2>/dev/null || true
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

    # --- 4. Check out the tag ------------------------------------------------

    echo ""
    echo "$(bold "Fetching and checking out tag $TAG...")"
    git fetch --force origin "refs/tags/$TAG:refs/tags/$TAG"
    git checkout --quiet "refs/tags/$TAG"
  fi

  # --- 5. Sync intellij-community to the tag's pinned SHAs -------------------
  #
  # INTELLIJ_REF / ANDROID_REF live in this repo; checking out the tag
  # updated them to whatever SHAs the release expects. But the sibling
  # intellij-community clone is independent and still sitting on whichever
  # SHAs it had before. Run the same bootstrap CI used so the clone
  # matches the tag.

  PINNED_INTELLIJ="$(tr -d '[:space:]' < "$WORKBENCH_DIR/INTELLIJ_REF")"
  if [ -f "$WORKBENCH_DIR/ANDROID_REF" ]; then
    PINNED_ANDROID="$(tr -d '[:space:]' < "$WORKBENCH_DIR/ANDROID_REF")"
  elif [ -n "$FALLBACK_ANDROID_REF" ]; then
    PINNED_ANDROID="$FALLBACK_ANDROID_REF"
  else
    PINNED_ANDROID="master"
  fi

  echo ""
  echo "$(bold "Pinned SHAs for $TAG:")"
  echo "  intellij-community: $PINNED_INTELLIJ"
  echo "  android:            $PINNED_ANDROID"

  # Abort if the local intellij-community or android clones have
  # uncommitted work — bootstrap would blow it away otherwise.
  check_dirty() {
    local dir="$1" name="$2"
    [ -d "$dir/.git" ] || return 0
    local -a status_lines=()
    if [ "$name" = "intellij-community" ]; then
      mapfile -t status_lines < <(
        git -C "$dir" status --short --untracked-files=all \
          -- ':!/.idea/modules.xml' \
             ':!/.idea/runConfigurations/TermLab.xml' \
             ':!/termlab'
      )
    else
      mapfile -t status_lines < <(git -C "$dir" status --short --untracked-files=all)
    fi

    echo ""
    if [ "${#status_lines[@]}" -ne 0 ]; then
      echo "$(red "$name has uncommitted changes at $dir")"
      printf '%s\n' "${status_lines[@]:0:10}"
      echo ""
      echo "Commit, stash, or discard those changes, then re-run this script."
      exit 1
    fi
  }
  check_dirty "$INTELLIJ_ROOT" "intellij-community"
  check_dirty "$INTELLIJ_ROOT/android" "android"

  echo ""
  echo "$(bold "Syncing intellij-community + android to pinned SHAs...")"
  ANDROID_REF="$PINNED_ANDROID" bash "$WORKBENCH_DIR/scripts/ci/bootstrap_intellij.sh" "$INTELLIJ_ROOT"
  sync_termlab_idea_config "$INTELLIJ_ROOT"

  # --- 6. Build --------------------------------------------------------------

  echo ""
  echo "$(bold "Building installer artifacts via jps-bootstrap (jobs=$JOBS)...")"
  echo ""
  BUILD_INSTALLERS_ARGS=("$INTELLIJ_ROOT" --jobs "$JOBS")
  if [ "$NO_WARMUP" -eq 1 ]; then
    BUILD_INSTALLERS_ARGS+=(--no-warmup)
  fi
  "$BUILD_INSTALLERS_SH" "${BUILD_INSTALLERS_ARGS[@]}"

  # --- 6b. Generate updates.xml --------------------------------------------
  #
  # Reads buildNumber/versionSuffix from the product-info.json files the
  # build just wrote, calls gh for the release's publishedAt, and emits
  # updates.xml into the artifact dir. The next step's find glob picks it
  # up automatically so it's listed and uploaded along with the binaries.

  echo ""
  echo "$(bold "Generating updates.xml from product-info.json files...")"
  python3 "$WORKBENCH_DIR/scripts/generate_updates_xml.py" \
    --artifact-dir "$INTELLIJ_ROOT/out/termlab/artifacts" \
    --tag "$TAG"
fi

# --- 7. List artifacts -------------------------------------------------------

ARTIFACT_DIR="$INTELLIJ_ROOT/out/termlab/artifacts"

if [ ! -d "$ARTIFACT_DIR" ]; then
  echo "$(red "Artifact directory not found: $ARTIFACT_DIR")"
  exit 1
fi

mapfile -t FILES < <(find "$ARTIFACT_DIR" -maxdepth 1 -type f \
  \( -name '*.dmg' -o -name '*.sit' -o -name '*.tar.gz' \
     -o -name '*.exe' -o -name '*.win.zip' -o -name 'updates.xml' \) | sort)

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

# --- 8. Upload ---------------------------------------------------------------

if [ "$UPLOAD" -eq 1 ]; then
  echo ""
  echo "$(bold "Uploading ${#FILES[@]} artifact(s) to $TAG...")"
  gh release upload "$TAG" "${FILES[@]}" --clobber
  echo ""
  echo "$(bold "Upload complete.")"
  gh release view "$TAG" --web >/dev/null 2>&1 || true
elif [ "$DRY_RUN" -eq 1 ]; then
  echo ""
  echo "$(bold "Dry-run complete.") Pipeline ran end-to-end against synthetic tag $(bold "$TAG")."
  echo "$(dim "Inspect $ARTIFACT_DIR/updates.xml to verify the produced metadata.")"
else
  echo ""
  echo "$(dim "Skipping upload. Run 'gh release upload $TAG <files> --clobber' to publish later.")"
fi
