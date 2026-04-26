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
RUN_CONFIG_SRC_DIR="$WORKBENCH_DIR/scripts/idea"
RUN_CONFIG_INSTALLERS_DIR="$RUN_CONFIG_SRC_DIR/installers"
TERMLAB_RUN_CONFIG_SRC="$RUN_CONFIG_SRC_DIR/TermLab.run.xml"
TERMLAB_RUN_CONFIG_DEST="$RUN_CONFIG_DIR/TermLab.xml"

# --- Manifest helpers --------------------------------------------------------
#
# Reads scripts/intellij-managed-paths.txt and emits paths from the requested
# section, one per line. Comments and blank lines are skipped.

MANIFEST_FILE="$WORKBENCH_DIR/scripts/intellij-managed-paths.txt"

read_manifest_section() {
  local section="$1"
  python3 - "$MANIFEST_FILE" "$section" <<'PYEOF'
import sys
manifest, target = sys.argv[1], sys.argv[2]
section = None
with open(manifest) as f:
    for raw in f:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1]
            continue
        if section == target:
            print(line)
PYEOF
}

if [ ! -f "$MANIFEST_FILE" ]; then
  echo "ERROR: manifest not found: $MANIFEST_FILE" >&2
  exit 1
fi

# --- Patcher functions -------------------------------------------------------
#
# Copy:
#   1) The TermLab dev-run config (scripts/idea/TermLab.run.xml).
#   2) Each installer template under scripts/idea/installers/*.run.xml.
#
# Destination filenames mirror IDEA's own writer: derived from the run
# config's name= attribute, with every non-alphanumeric character
# replaced by an underscore (e.g. "TermLab Installer · macOS aarch64
# · dev" -> "TermLab_Installer___macOS_aarch64___dev.xml"). That keeps
# the on-disk file name aligned with what IDEA shows in its dropdown.

mkdir -p "$RUN_CONFIG_DIR"

install_run_config() {
  local src="$1"
  local dest="$2"
  if [ -f "$dest" ] && cmp -s "$src" "$dest"; then
    echo "==> Run configuration already installed: $dest"
  else
    cp "$src" "$dest"
    echo "==> Installed run configuration: $dest"
  fi
}

# Patcher: copy scripts/idea/TermLab.run.xml → .idea/runConfigurations/TermLab.xml
patch_termlab_run_config() {
  install_run_config "$TERMLAB_RUN_CONFIG_SRC" "$TERMLAB_RUN_CONFIG_DEST"
}

# Patcher: copy each scripts/idea/installers/*.run.xml to .idea/runConfigurations/
# with the destination name derived from the run config's name= attribute.
patch_installer_run_configs() {
  if [ ! -d "$RUN_CONFIG_INSTALLERS_DIR" ]; then
    return 0
  fi
  for src in "$RUN_CONFIG_INSTALLERS_DIR"/*.run.xml; do
    [ -f "$src" ] || continue
    local config_name sanitized
    config_name="$(python3 - "$src" <<'PYEOF'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r'<configuration[^>]*\bname="([^"]+)"', text)
if not m:
    sys.exit(f"no name= attribute found in {sys.argv[1]}")
print(m.group(1))
PYEOF
)"
    sanitized="$(python3 - "$config_name" <<'PYEOF'
import re, sys
print(re.sub(r'[^A-Za-z0-9]', '_', sys.argv[1]))
PYEOF
)"
    install_run_config "$src" "$RUN_CONFIG_DIR/${sanitized}.xml"
  done
}

# Patcher: sync TermLab .iml entries into .idea/modules.xml at a known anchor.
#
# Keep the registered TermLab modules aligned with the current checkout rather
# than only appending new ones. That avoids stale module references when a tag
# predates a newer .iml file or when the module set changes over time.
patch_modules_xml() {
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
}

# --- Dispatch ----------------------------------------------------------------
#
# For each path in the manifest's [managed] section, run the appropriate
# patcher. Unknown paths are a hard error so manifest drift surfaces loudly.

apply_managed_patch() {
  local p="$1"
  case "$p" in
    .idea/modules.xml)
      patch_modules_xml
      ;;
    .idea/runConfigurations/TermLab.xml)
      patch_termlab_run_config
      ;;
    .idea/runConfigurations/TermLab_Installer___*.xml)
      patch_installer_run_configs
      ;;
    termlab)
      # Symlink — created by setup.sh; install-idea-config.sh has nothing to do.
      ;;
    .idea/vcs.xml | build/dev-build.json | platform/build-scripts/tools/mac/scripts/makedmg.sh)
      # Managed by other scripts (setup.sh / build pipeline); nothing to do here.
      ;;
    *)
      echo "ERROR: install-idea-config.sh has no patcher for managed path: $p" >&2
      exit 1
      ;;
  esac
}

# Dispatch each managed path to its patcher. Each patcher is idempotent,
# so re-invocations are no-ops; no need to dedupe.
while IFS= read -r p; do
  apply_managed_patch "$p"
done < <(read_manifest_section "managed")

echo
echo "✓ IDEA config installed."
echo

echo "Open intellij-community in IntelliJ IDEA, wait for it to index, then pick"
echo "the 'TermLab' run configuration from the run dropdown. ▶ runs it; 🐞 debugs"
echo "with breakpoints in the termlab sources."
