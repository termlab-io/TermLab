#!/usr/bin/env bash
#
# Uploads every installer artifact this job produced to the draft
# release identified by TAG. Idempotent via gh release upload --clobber.
#
# Usage:
#   upload_artifacts.sh TAG
#
# Environment:
#   INTELLIJ_ROOT — set by CI bootstrap step
#   GH_TOKEN      — RELEASE_TOKEN, set in workflow

set -euo pipefail

TAG="${1:?usage: upload_artifacts.sh TAG}"
: "${INTELLIJ_ROOT:?INTELLIJ_ROOT must be set}"

ARTIFACT_DIR="$INTELLIJ_ROOT/out/termlab/artifacts"

if [ ! -d "$ARTIFACT_DIR" ]; then
  echo "::error::No artifact dir at $ARTIFACT_DIR — build target failed silently?" >&2
  exit 1
fi

# Portable read-loop (mapfile is bash 4+, macOS /bin/bash is 3.2).
FILES=()
while IFS= read -r f; do
  FILES+=("$f")
done < <(find "$ARTIFACT_DIR" -maxdepth 1 -type f \
  \( -name '*.dmg' -o -name '*.sit' -o -name '*.tar.gz' \
     -o -name '*.exe' -o -name '*.win.zip' -o -name '*.zip' \) | sort)

if [ ${#FILES[@]} -eq 0 ]; then
  echo "::error::Build target finished but produced no installer artifacts" >&2
  exit 1
fi

echo "Uploading ${#FILES[@]} artifact(s) to release $TAG:"
for f in "${FILES[@]}"; do
  echo "  $f"
done

gh release upload "$TAG" "${FILES[@]}" --clobber
echo "Upload complete."
