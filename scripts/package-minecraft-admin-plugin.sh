#!/usr/bin/env bash
# Package the Minecraft Admin plugin as an IntelliJ-installable zip.
# Called from the root Makefile's `minecraft-admin-plugin` target.
set -euo pipefail

WORKBENCH_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$WORKBENCH_DIR"

OUT_ROOT="out/minecraft-admin-plugin"
PLUGIN_DIR_NAME="Conch Minecraft Admin"
STAGE_DIR="$OUT_ROOT/$PLUGIN_DIR_NAME"
LIB_DIR="$STAGE_DIR/lib"
ZIP_PATH="out/minecraft-admin-plugin.zip"

INTELLIJ_ROOT_FILE="$WORKBENCH_DIR/.intellij-root"
if [ -f "$INTELLIJ_ROOT_FILE" ]; then
  INTELLIJ_ROOT="$(cat "$INTELLIJ_ROOT_FILE")"
else
  INTELLIJ_ROOT="$WORKBENCH_DIR/../intellij-community"
fi

echo "==> Building plugin jar via Bazel"
(cd "$INTELLIJ_ROOT" && bash bazel.cmd build //conch/plugins/minecraft-admin:minecraft-admin)

JAR_PATH="$INTELLIJ_ROOT/out/bazel-bin/conch/plugins/minecraft-admin/minecraft-admin.jar"
if [ ! -f "$JAR_PATH" ]; then
  echo "error: expected jar not found at $JAR_PATH" >&2
  exit 1
fi

echo "==> Staging plugin layout at $STAGE_DIR"
rm -rf "$OUT_ROOT" "$ZIP_PATH"
mkdir -p "$LIB_DIR"
cp "$JAR_PATH" "$LIB_DIR/minecraft-admin.jar"

echo "==> Zipping to $ZIP_PATH"
(cd "$OUT_ROOT" && zip -rq "../$(basename "$ZIP_PATH")" "$PLUGIN_DIR_NAME")

SIZE="$(wc -c < "$ZIP_PATH" | tr -d ' ')"
echo
echo "==> Done."
echo "    $ZIP_PATH  ($SIZE bytes)"
echo "    Install via Conch → Settings → Plugins → gear → Install Plugin from Disk…"
