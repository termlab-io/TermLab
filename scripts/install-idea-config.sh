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
  local ANCHOR SYNC_RESULT
  local -a TERMLAB_MODULES

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

# Patcher: insert the TermLab product entry into intellij-community/build/dev-build.json
patch_dev_build_json() {
  local target="$INTELLIJ_DIR/build/dev-build.json"
  if [ ! -f "$target" ]; then
    echo "ERROR: $target not found; intellij-community checkout incomplete?" >&2
    exit 1
  fi
  python3 - "$target" <<'PYEOF'
import json, sys
path = sys.argv[1]
with open(path) as f:
    data = json.load(f)
products = data.setdefault("products", {})
desired = {
    "modules": [
        "intellij.termlab.build",
        "intellij.idea.community.build.dependencies",
        "intellij.platform.buildScripts",
    ],
    "class": "org.jetbrains.intellij.build.termlab.TermLabProperties",
}
if products.get("TermLab") == desired:
    print("==> dev-build.json: TermLab product entry already present")
    sys.exit(0)
products["TermLab"] = desired
with open(path, "w") as f:
    json.dump(data, f, indent=2)
    f.write("\n")
print(f"==> dev-build.json: TermLab product entry written ({path})")
PYEOF
}

# Patcher: register the termlab/ symlinked checkout as a Git VCS mapping in
# intellij-community/.idea/vcs.xml so IDEA tracks it as a separate repo.
patch_vcs_xml() {
  local target="$INTELLIJ_DIR/.idea/vcs.xml"
  if [ ! -f "$target" ]; then
    echo "ERROR: $target not found; intellij-community checkout incomplete?" >&2
    exit 1
  fi
  python3 - "$target" <<'PYEOF'
import re, sys
path = sys.argv[1]
text = open(path).read()
if 'directory="$PROJECT_DIR$/termlab"' in text:
    print(f"==> vcs.xml: termlab mapping already present")
    sys.exit(0)
mapping = '    <mapping directory="$PROJECT_DIR$/termlab" vcs="Git" />\n'
new_text, n = re.subn(
    r'(\s*</component>\s*</project>\s*\Z)',
    f'{mapping}\\1',
    text,
    count=1,
)
if n != 1:
    sys.exit(f"vcs.xml shape changed; can't insert mapping (no </component></project> match)")
open(path, "w").write(new_text)
print(f"==> vcs.xml: termlab mapping written ({path})")
PYEOF
}

# Patcher: apply TermLab's local DMG-creation workaround to upstream's
# makedmg.sh. Idempotent via a marker comment in the patched section.
# The same logic was previously applied at runtime by TermLabInstallersBuild
# Target.patchMacDmgScript(); that runtime call is removed in the next task.
patch_makedmg_sh() {
  local target="$INTELLIJ_DIR/platform/build-scripts/tools/mac/scripts/makedmg.sh"
  if [ ! -f "$target" ]; then
    echo "ERROR: $target not found; intellij-community checkout incomplete?" >&2
    exit 1
  fi
  python3 - "$target" <<'PYEOF'
import re, sys
path = sys.argv[1]
MARKER = "# TermLab local DMG create workaround"
text = open(path).read()
if MARKER in text:
    print(f"==> makedmg.sh: TermLab patch already present")
    sys.exit(0)

ORIGINAL_LINE = (
    'hdiutil create -srcfolder "${EXPLODED}" -volname "$VOLNAME" -anyowners '
    '-nospotlight -fs HFS+ -fsargs "-c c=64,a=16,e=16" -format UDRW "$TEMP_DMG"'
)
REPLACEMENT = (
    f"{MARKER}: hdiutil create -srcfolder can fail with\n"
    "# \"could not access /Volumes/<volume>/<app>.app - Operation not permitted\" on\n"
    "# local macOS release builds. Create an empty image instead, then copy the\n"
    "# exploded app into the mounted image after attach.\n"
    'DMG_SIZE_MB=$(du -sm "$EXPLODED" | awk \'{print int($1 * 14 / 10 + 256)}\')\n'
    'hdiutil create -size "${DMG_SIZE_MB}m" -volname "$VOLNAME" -anyowners '
    '-nospotlight -fs HFS+ -fsargs "-c c=64,a=16,e=16" "$TEMP_DMG"'
)
if ORIGINAL_LINE not in text:
    sys.exit("makedmg.sh shape changed; can't patch (hdiutil create line not found)")
text = text.replace(ORIGINAL_LINE, REPLACEMENT, 1)

DITTO_INSERT = (
    'log "Copying exploded app contents into mounted disk image..."\n'
    'ditto "$EXPLODED/" "$MOUNT_POINT/"\n'
)
new_text, n = re.subn(
    r'(log "Mounted as \$device"\nsleep 10\n)',
    f'\\1{DITTO_INSERT}',
    text,
    count=1,
)
if n != 1:
    sys.exit("makedmg.sh shape changed; can't insert ditto step (mount marker not found)")
open(path, "w").write(new_text)
print(f"==> makedmg.sh: TermLab patch applied")
PYEOF
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
    .idea/vcs.xml)
      patch_vcs_xml
      ;;
    build/dev-build.json)
      patch_dev_build_json
      ;;
    platform/build-scripts/tools/mac/scripts/makedmg.sh)
      patch_makedmg_sh
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
