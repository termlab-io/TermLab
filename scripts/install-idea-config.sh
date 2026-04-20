#!/usr/bin/env bash
#
# install-idea-config.sh — wire termlab into intellij-community's IntelliJ project
#
# When you open intellij-community in IntelliJ IDEA, IntelliJ reads its project
# state from intellij-community/.idea/. For TermLab to be runnable/debuggable from
# the IDE, two things must live there:
#
#   1. A registration entry in .idea/modules.xml pointing at each termlab *.iml.
#   2. A run configuration at .idea/runConfigurations/TermLab.xml.
#
# Both of these are *local-only* additions — they patch a file (modules.xml)
# that intellij-community ships under git, so they show up as a 'modified' file
# in the upstream tree. That's expected; we don't push intellij-community
# anywhere, so the patch stays local.
#
# This script is idempotent — re-running it does nothing on top of an existing
# install.
#
# Usage:
#   ./scripts/install-idea-config.sh                          # uses .intellij-root
#   ./scripts/install-idea-config.sh /path/to/intellij-community

set -euo pipefail

WORKBENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ $# -ge 1 ]; then
  INTELLIJ_DIR="$1"
elif [ -f "$WORKBENCH_DIR/.intellij-root" ]; then
  INTELLIJ_DIR="$(tr -d '[:space:]' < "$WORKBENCH_DIR/.intellij-root")"
else
  echo "ERROR: no intellij-community location given." >&2
  echo "       Run setup.sh first, or pass the path explicitly." >&2
  exit 1
fi

if [ ! -f "$INTELLIJ_DIR/.idea/modules.xml" ]; then
  echo "ERROR: $INTELLIJ_DIR/.idea/modules.xml not found." >&2
  echo "       Is $INTELLIJ_DIR a real intellij-community checkout?" >&2
  exit 1
fi

MODULES_XML="$INTELLIJ_DIR/.idea/modules.xml"
RUN_CONFIG_DIR="$INTELLIJ_DIR/.idea/runConfigurations"
RUN_CONFIG_DEST="$RUN_CONFIG_DIR/TermLab.xml"
RUN_CONFIG_SRC="$WORKBENCH_DIR/scripts/idea/TermLab.run.xml"

# --- 1. Install the run configuration ----------------------------------------

mkdir -p "$RUN_CONFIG_DIR"
if [ -f "$RUN_CONFIG_DEST" ] && cmp -s "$RUN_CONFIG_SRC" "$RUN_CONFIG_DEST"; then
  echo "==> Run configuration already installed: $RUN_CONFIG_DEST"
else
  cp "$RUN_CONFIG_SRC" "$RUN_CONFIG_DEST"
  echo "==> Installed run configuration: $RUN_CONFIG_DEST"
fi

# --- 2. Sync TermLab modules in modules.xml -----------------------------------
#
# Keep the registered TermLab modules aligned with the current checkout rather
# than only appending new ones. That avoids stale module references when a tag
# predates a newer .iml file or when the module set changes over time.

mapfile -t TERMLAB_MODULES < <(python3 - "$WORKBENCH_DIR" <<'PYEOF'
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

ANCHOR='intellij.compose.ide.plugin.shared.tests.iml'
if ! grep -q "$ANCHOR" "$MODULES_XML"; then
  echo "ERROR: anchor line not found in $MODULES_XML." >&2
  echo "       The intellij-community version may have changed." >&2
  echo "       Manually add these lines to .idea/modules.xml inside <modules>:" >&2
  for path in "${TERMLAB_MODULES[@]}"; do
    echo "      <module fileurl=\"file://\$PROJECT_DIR\$/$path\" filepath=\"\$PROJECT_DIR\$/$path\" />" >&2
  done
  exit 1
fi

SYNC_RESULT="$(python3 - "$MODULES_XML" "$ANCHOR" "${TERMLAB_MODULES[@]}" <<'PYEOF'
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
if updated == lines:
    print("unchanged")
else:
    with open(path, "w", encoding="utf-8") as fh:
        fh.writelines(updated)
    print("updated")
PYEOF
)"

if [ "$SYNC_RESULT" = "unchanged" ]; then
  echo "==> TermLab modules already synchronized in $MODULES_XML"
else
  echo "==> Synchronized TermLab modules in $MODULES_XML"
fi

echo
echo "✓ IDEA config installed."
echo
echo "Open intellij-community in IntelliJ IDEA, wait for it to index, then pick"
echo "the 'TermLab' run configuration from the run dropdown. ▶ runs it; 🐞 debugs"
echo "with breakpoints in the termlab sources."
