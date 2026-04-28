#!/usr/bin/env bash
#
# Decodes APPLE_API_KEY_P8 base64 secret into a multiline env var
# APPLE_PRIVATE_KEY (the .p8 file content). TermLabInstallersBuildTarget
# reads APPLE_PRIVATE_KEY at runtime to authenticate notarization.
#
# Environment:
#   APPLE_API_KEY_P8 — base64-encoded .p8 contents
#   GITHUB_ENV       — path to GHA env file (set by runner)

set -euo pipefail

: "${APPLE_API_KEY_P8:?APPLE_API_KEY_P8 must be set}"
: "${GITHUB_ENV:?GITHUB_ENV must be set}"
: "${RUNNER_TEMP:?RUNNER_TEMP must be set}"

P8_PATH="$RUNNER_TEMP/AuthKey.p8"
echo "$APPLE_API_KEY_P8" | base64 -d > "$P8_PATH"

{
  echo "APPLE_PRIVATE_KEY<<TERMLAB_EOF"
  cat "$P8_PATH"
  # GITHUB_ENV's heredoc parser requires the closing delimiter on its own
  # line. .p8 files don't always end with a newline, so cat would emit
  # ...END PRIVATE KEY-----TERMLAB_EOF on one line. Force the boundary.
  echo
  echo "TERMLAB_EOF"
} >> "$GITHUB_ENV"

echo "APPLE_PRIVATE_KEY exported to GITHUB_ENV (APPLE_PRIVATE_KEY_FILE=$P8_PATH)"
