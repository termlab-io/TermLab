#!/usr/bin/env bash
#
# Provisions an ephemeral keychain on the runner with the Developer ID
# Application certificate and private key from the APPLE_DEVELOPER_ID_P12
# secret. The keychain is added to the user search list so codesign
# finds it. Cleanup is the caller's responsibility (always() step in
# the workflow YAML).
#
# Environment:
#   P12_BASE64       — base64-encoded .p12 from APPLE_DEVELOPER_ID_P12 secret
#   P12_PASSWORD     — password for the .p12 (APPLE_DEVELOPER_ID_P12_PASSWORD)
#   RUNNER_TEMP      — set by GitHub Actions runner

set -euo pipefail

: "${P12_BASE64:?P12_BASE64 must be set}"
: "${P12_PASSWORD:?P12_PASSWORD must be set}"
: "${RUNNER_TEMP:?RUNNER_TEMP must be set}"

KEYCHAIN_PASSWORD="$(openssl rand -hex 32)"
KEYCHAIN_PATH="$RUNNER_TEMP/build.keychain"
CERT_PATH="$RUNNER_TEMP/cert.p12"

security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"
security set-keychain-settings -lut 21600 "$KEYCHAIN_PATH"   # 6h timeout
security unlock-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"

echo "$P12_BASE64" | base64 -d > "$CERT_PATH"
security import "$CERT_PATH" -P "$P12_PASSWORD" -A -t cert -f pkcs12 -k "$KEYCHAIN_PATH"
security set-key-partition-list -S apple-tool:,apple: -s -k "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH" >/dev/null

# Add to user keychain search list so codesign finds the identity.
existing="$(security list-keychains -d user | tr -d '"' | xargs)"
security list-keychains -d user -s "$KEYCHAIN_PATH" $existing

rm -f "$CERT_PATH"
echo "Signing keychain provisioned at $KEYCHAIN_PATH"
