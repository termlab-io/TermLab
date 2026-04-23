#!/usr/bin/env bash
#
# serve_updates_xml.sh — serve updates.xml from the latest installer-build
# artifact directory at http://localhost:<port>/updates.xml so a locally-
# built TermLab (with TermLabExternalProductResourceUrls.DEFAULT_UPDATE_URL
# pointed at localhost) can see updates against it.
#
# Usage:
#   ./scripts/serve_updates_xml.sh           # port 8000
#   ./scripts/serve_updates_xml.sh 8080      # custom port
#
# To trigger an "update available" notification, edit the served file's
# <build number=...> to a value higher than the running app's. The build
# number compares as a sequence of integers segment-by-segment.

set -euo pipefail

WORKBENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

# Resolve INTELLIJ_ROOT (matches Makefile / release.sh resolution order):
# env var → .intellij-root file → sibling intellij-community directory.
if [ -z "${INTELLIJ_ROOT:-}" ]; then
  if [ -f "$WORKBENCH_DIR/.intellij-root" ]; then
    INTELLIJ_ROOT="$(tr -d '[:space:]' < "$WORKBENCH_DIR/.intellij-root")"
  else
    INTELLIJ_ROOT="$(dirname "$WORKBENCH_DIR")/intellij-community"
  fi
fi

PORT="${1:-8000}"
ARTIFACT_DIR="$INTELLIJ_ROOT/out/termlab/artifacts"
UPDATES_XML="$ARTIFACT_DIR/updates.xml"

if [ ! -f "$UPDATES_XML" ]; then
  echo "ERROR: updates.xml not found at $UPDATES_XML" >&2
  echo "       Build artifacts first:" >&2
  echo "         make termlab-installers" >&2
  echo "       or:" >&2
  echo "         ./scripts/release.sh --dry-run" >&2
  exit 1
fi

# Pull <build number="..."> out for the banner so the user can see at a
# glance what build the served file is advertising — main thing they need
# to know when debugging "why isn't the update notification firing".
served_build="$(grep -oE '<build [^>]*number="[^"]+"' "$UPDATES_XML" \
                | head -1 \
                | sed -E 's/.*number="([^"]+)".*/\1/')"

printf 'Serving updates.xml on http://localhost:%s/updates.xml\n' "$PORT"
printf '  artifact dir : %s\n' "$ARTIFACT_DIR"
printf '  build number : %s\n' "${served_build:-(could not parse)}"
printf '  hint         : edit updates.xml to bump the build number above the running app to trigger a notification\n'
printf '  press Ctrl+C to stop\n\n'

cd "$ARTIFACT_DIR"
# Bind to loopback only so we don't accidentally expose the artifact dir
# to anyone else on the local network.
exec python3 -m http.server --bind 127.0.0.1 "$PORT"
