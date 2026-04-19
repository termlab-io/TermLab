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

# --- 2. Register termlab modules in modules.xml --------------------------------
#
# We insert four <module .../> entries pointing at the symlinked termlab tree
# (intellij-community/termlab → termlab_workbench). They go just after the existing
# 'compose.ide.plugin.shared.tests.iml' entry, alphabetically near 'configurationScript'
# where 'termlab' belongs. The entries use $PROJECT_DIR$ so IntelliJ resolves them
# relative to intellij-community.

TERMLAB_MODULES=(
  "termlab/build/intellij.termlab.build.iml"
  "termlab/core/intellij.termlab.core.iml"
  "termlab/customization/intellij.termlab.customization.iml"
  "termlab/intellij.termlab.main.iml"
  "termlab/sdk/intellij.termlab.sdk.iml"
  "termlab/plugins/ssh/intellij.termlab.ssh.iml"
  "termlab/plugins/vault/intellij.termlab.vault.iml"
  "termlab/plugins/tunnels/intellij.termlab.tunnels.iml"
  "termlab/plugins/share/intellij.termlab.share.iml"
  "termlab/plugins/sftp/intellij.termlab.sftp.iml"
  "termlab/plugins/editor/intellij.termlab.editor.iml"
  "termlab/plugins/runner/intellij.termlab.runner.iml"
  "termlab/plugins/search/intellij.termlab.search.iml"
)

MISSING_MODULES=()
for path in "${TERMLAB_MODULES[@]}"; do
  if ! grep -Fq "$path" "$MODULES_XML"; then
    MISSING_MODULES+=("$path")
  fi
done

if [ ${#MISSING_MODULES[@]} -eq 0 ]; then
  echo "==> TermLab modules already registered in $MODULES_XML"
else
  echo "==> Registering missing TermLab modules in $MODULES_XML"
  for path in "${MISSING_MODULES[@]}"; do
    echo "    + $path"
  done

  # Find the anchor line and inject only the missing module entries
  # right after it.
  ANCHOR='intellij.compose.ide.plugin.shared.tests.iml'
  if ! grep -q "$ANCHOR" "$MODULES_XML"; then
    echo "ERROR: anchor line not found in $MODULES_XML." >&2
    echo "       The intellij-community version may have changed." >&2
    echo "       Manually add these lines to .idea/modules.xml inside <modules>:" >&2
    for path in "${MISSING_MODULES[@]}"; do
      echo "      <module fileurl=\"file://\$PROJECT_DIR\$/$path\" filepath=\"\$PROJECT_DIR\$/$path\" />" >&2
    done
    exit 1
  fi

  python3 - "$MODULES_XML" "$ANCHOR" "${MISSING_MODULES[@]}" <<'PYEOF'
import sys

path, anchor, *modules = sys.argv[1:]
insert = "".join(
    f'      <module fileurl="file://$PROJECT_DIR$/{m}" filepath="$PROJECT_DIR$/{m}" />\n'
    for m in modules
)

with open(path) as f:
    lines = f.readlines()

out = []
inserted = False
for line in lines:
    out.append(line)
    if not inserted and anchor in line:
        out.append(insert)
        inserted = True

if not inserted:
    sys.exit("anchor not found")

with open(path, "w") as f:
    f.writelines(out)
PYEOF
fi

echo
echo "✓ IDEA config installed."
echo
echo "Open intellij-community in IntelliJ IDEA, wait for it to index, then pick"
echo "the 'TermLab' run configuration from the run dropdown. ▶ runs it; 🐞 debugs"
echo "with breakpoints in the termlab sources."
