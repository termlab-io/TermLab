# SSH/Tunnels Share: Export & Import Design

**Status:** Design
**Date:** 2026-04-14
**Scope:** New `plugins/share` module for exporting and importing SSH hosts, tunnels, and optional vault credentials as a single encrypted bundle file that can be shared between machines or between people.

## Goals

- Let a user export selected SSH hosts and tunnels, optionally with the vault credentials they reference, into a single encrypted file.
- Let a user import such a bundle on another machine — including the first-ever machine, where the vault may not yet exist — in one seamless flow.
- Make sharing between people (not just between a single user's machines) a first-class use case. The tool must produce bundles that work on a clean install, not just round-trip a user's own state.
- Preserve the existing security posture of the vault: same crypto primitives, same unlock semantics, no new secrets stored at rest.

## Non-Goals (v1)

- No signing or authorship verification. Bundle password is the only trust mechanism.
- No partial vault export — standalone `VaultKey`s and `VaultAccount`s not referenced by a selected host are not exportable through this feature.
- No cross-file rollback during import. Each of `HostsFile`, `TunnelsFile`, `VaultFile` writes atomically, but if the second of three writes fails, we stop and report partial state.
- No cloud sync, no pairing, no auto-push. File-based only.
- No writing back to `~/.ssh/config`. We read it at export time; on the recipient's machine, converted aliases become internal SshHosts.
- No wildcards, `Match` blocks, or inheritance in `~/.ssh/config` parsing — direct alias matches only, with warnings for anything unsupported.
- No post-creation bundle editing. Re-export to change a bundle.
- No key rotation for shared credentials.
- No preview of bundle metadata without the password. Everything is inside the ciphertext.

## Architecture Overview

A new `plugins/share` module, Java, following the existing plugin layout. Module dependencies: `ssh`, `tunnels`, `vault`, plus the IntelliJ Platform.

Key classes:

- **`ShareBundle`** — in-memory representation of a bundle: schema version, metadata (created timestamp, source machine name, Conch version, `includesCredentials` flag), `List<SshHost>`, `List<SshTunnel>`, and a nested `BundledVault` with `List<VaultAccount>` + `List<VaultKey>`. Pure data; no I/O, no crypto.
- **`ShareBundleCodec`** — encodes and decodes a `ShareBundle` to/from the encrypted file format. Reuses `plugins/vault`'s `VaultCrypto` and `KeyDerivation` for AES-256-GCM + Argon2id — no crypto is reimplemented.
- **`ExportPlanner`** — given a user selection, resolves dependencies (tunnel → host, host → credential), applies `SshConfigHost` and `KeyFileAuth` conversions, and produces a `ShareBundle` ready to encode. Pure logic, fully unit-testable.
- **`ImportPlanner`** — given a decoded `ShareBundle` and the user's current state, builds a list of `ImportItem`s tagged with conflict status (`New`, `Same UUID exists`, `Label collision`, `Reference broken`). Pure logic.
- **`ImportExecutor`** — applies a resolved import plan. The only class that mutates user state. Writes via `HostsFile`, `TunnelsFile`, and `LockManager.withUnlocked(...)`.
- **`SshConfigReader`** — minimal `~/.ssh/config` parser scoped to direct alias matches and the fields Conch uses.
- **`KeyFileImporter`** — reads on-disk private keys, validates them, synthesizes `VaultKey` records.
- **`ExportDialog`** and **`ImportDialog`** — `DialogWrapper` subclasses matching the existing vault/ssh UI conventions.

**Export data flow:** tool window action → `ExportDialog` (picker + options) → `ExportPlanner` (resolve deps, run conversions) → password prompt → `ShareBundleCodec.encode` → file.

**Import data flow:** file picker → `ShareBundleCodec.decode` (prompts for bundle password; if vault does not exist, inline vault creation first) → `ImportPlanner` (build plan with conflicts) → `ImportDialog` (preview + per-conflict prompts) → `ImportExecutor`.

## Bundle Format

Single file, extension **`.conchshare`**, versioned envelope mirroring `VaultFileFormat` so we reuse `VaultCrypto` directly:

```
[ MAGIC (8 bytes, "CONCHSHR") ]
[ FORMAT_VERSION (uint32, = 1) ]
[ SALT (16 bytes) ]
[ NONCE (12 bytes) ]
[ CIPHERTEXT (AES-256-GCM, variable length) ]
```

The ciphertext, once decrypted, is a single JSON document — the serialized `ShareBundle`:

```json
{
  "schemaVersion": 1,
  "metadata": {
    "createdAt": "2026-04-14T10:32:00Z",
    "sourceHost": "dustin-mbp",
    "conchVersion": "0.14.2",
    "includesCredentials": true
  },
  "hosts":      [ /* SshHost objects, reusing SshGson format */ ],
  "tunnels":    [ /* SshTunnel objects, reusing TunnelGson format */ ],
  "vault": {
    "accounts": [ /* VaultAccount objects */ ],
    "keys":     [ /* VaultKey objects */ ]
  }
}
```

Notes:

- Reuses existing Gson adapters (`SshGson`, `TunnelGson`, `VaultGson`) — no parallel schema to maintain.
- `includesCredentials` is metadata only; the authoritative rule is "if `vault.accounts` and `vault.keys` are both empty, the bundle was credentials-off".
- `schemaVersion: 1`. Future versions that change `SshHost` / `SshTunnel` / `VaultAccount` field shapes bump this and dispatch to a migration. v1 rejects unknown versions cleanly ("This bundle was created by a newer version of Conch").
- Always encrypted, always requires a password at export and import time. No unencrypted path.
- Argon2id parameters match the vault (`KeyDerivation` defaults).
- No signing / no public-key crypto in v1.

## Export Flow

**Entry points:** "Export…" toolbar buttons in the SSH and Tunnels tool windows, plus `Tools → Conch → Export…` top-level action. All three open the same `ExportDialog`, pre-populated with the tool window's item type expanded.

### ExportDialog — single window, one page

1. **Selection tree** — two top-level sections, "SSH Hosts" and "Tunnels", each a checkbox tree of all items in the user's config, showing label and hostname. Multi-select. Nothing checked by default.
2. **Include credentials toggle** — single checkbox: *"Include saved credentials (recipient will receive passwords and keys)"*. When on, an info line appears: *"Credentials will be included for hosts that reference the vault. A password will protect the bundle."*
3. **Bundle password fields** — password + confirm, always required. Hint: *"Anyone with this password can read everything in the bundle."*
4. **Export button** — disabled until at least one item is checked and passwords match and are non-empty.

### ExportPlanner execution

When the user clicks Export:

1. **Auto-pull dependencies.** For each selected tunnel, if its `TunnelHost` is an `InternalHost(uuid)`, pull that `SshHost` into the bundle even if not explicitly checked. Record auto-pulls for the preview.
2. **Resolve `SshConfigHost` tunnels.** Parse `~/.ssh/config` via `SshConfigReader`, synthesize a fresh-UUID `SshHost` for each referenced alias, rewrite the tunnel's `TunnelHost` to `InternalHost(new UUID)` in the bundle only. Unsupported config (wildcards, `Match`, unknown directives) becomes a warning.
3. **Resolve `KeyFileAuth` hosts.** If credentials are included, read each key file via `KeyFileImporter`, synthesize a fresh-UUID `VaultAccount` with `AuthMethod = Key` (username taken from the `SshHost`'s existing `username` field), and rewrite the host's auth to `VaultAuth(new account UUID)`. If the underlying data model stores the key material as a standalone `VaultKey` referenced by the account, the planner creates that `VaultKey` first and the account references it. Passphrase-protected keys prompt for the passphrase during this step and bundle the passphrase alongside the key material. If credentials are off, leave auth as `KeyFileAuth` with the original path — recipient gets a broken host they can fix.
4. **Resolve `VaultAuth` hosts.** If credentials are included, unlock the vault via `LockManager` (prompting master password if locked) and copy each referenced `VaultAccount` into the bundle. If that `VaultAccount`'s `AuthMethod` references a standalone `VaultKey` by ID, pull the referenced key into the bundle too. If credentials are off, rewrite the host's auth to `PromptPasswordAuth()` so the host imports cleanly and prompts at connect time.
5. **Show conversion preview.** A second modal lists what will be converted and any warnings (e.g., *"Tunnel 'prod-db' references `~/.ssh/config` alias 'bastion', which uses Host wildcards — we'll convert the direct fields only"*). User confirms or cancels.
6. **Encode and write.** `ShareBundleCodec.encode(bundle, password)` → native file save dialog, default filename `conch-share-YYYY-MM-DD.conchshare`.

### Error paths

- Vault locked and credentials requested: master password prompt handled by `LockManager.withUnlocked`; cancel at that prompt cancels the whole export with no side effects.
- Key file missing or unreadable: `KeyFileImporter` returns a warning; the conversion preview surfaces it and the user can uncheck that host or cancel.
- Export interrupted mid-write: file save uses a temp-file + rename pattern, same as `HostsFile` — partial bundle files are never left behind.

## Import Flow

**Entry points:** "Import…" toolbar buttons in both tool windows and `Tools → Conch → Import…`. All route to `ImportDialog`, which starts with a native file chooser filtered to `*.conchshare`.

### Step 1 — File + password

A compact modal: selected file path, password field, Unlock button. `ShareBundleCodec.decode(file, password)` runs. Failure modes:

- Wrong password: AES-GCM tag check fails → "Incorrect password".
- Corrupt file or unknown magic: "Not a valid Conch share bundle".
- Unknown schema version: "This bundle was created by a newer version of Conch".

All three re-show the same dialog with an inline error.

### Step 2 — Inline vault creation (conditional)

After decode succeeds, if `bundle.metadata.includesCredentials == true`:

- **No local vault yet** (`VaultFile.exists() == false`): the dialog advances to a "Set up your vault" step embedded in the same window. Two fields (master password + confirm) and a short explanation: *"This bundle contains saved credentials. To store them, you need a vault on this machine. Pick a master password — you'll use it to unlock the vault from now on."* On submit, `LockManager.createVault(masterPassword)` runs.
- **Local vault exists but locked:** the step prompts for the existing master password and unlocks it via `LockManager.unlock(password)`.
- **Local vault already unlocked:** skip step 2 entirely.

One page, three variants, single window. If the bundle has no credentials, step 2 is skipped regardless of vault state.

### Step 3 — Preview and conflicts

`ImportPlanner` has already built an `ImportItem` list. The preview is a table with columns: *Type* (Host / Tunnel / Credential / Key), *Label*, *Status*, *Action*.

**Status values:**
- `New` — no conflict.
- `Same UUID exists` — an item with this UUID is already in the user's config or vault.
- `Label collision (different UUID)` — an unrelated item has the same label.
- `Reference broken` — a tunnel whose referenced `SshHost` is neither in the bundle nor in the user's existing data. Should not happen from our own exporter; handled defensively.

**Actions per row (dropdown):**
- `Import` (default for `New`)
- `Skip` (default for `Reference broken`; also offered for conflicts)
- `Replace` (only for `Same UUID exists`; overwrites the existing item)
- `Rename` (only for `Label collision`; prompts for a new label inline)

**Per-conflict prompt.** On the first conflict encountered, a modal pops with Skip / Replace / Rename buttons and an **"Apply to all remaining conflicts of this type"** checkbox. If checked, the decision persists for all rows with the same status for the rest of the preview.

Below the table: *"X new, Y replace, Z skip, N rename"* summary, and an Import button.

### Step 4 — Execution

`ImportExecutor` runs in a single pass:

1. If the bundle has credentials, wrap the whole operation in `LockManager.withUnlocked(lock → { ... })`. Inside, vault items are added in dependency order: standalone `VaultKey`s first, then `VaultAccount`s (which may reference those keys by ID). Per-row actions apply (replace overwrites by UUID; rename saves with adjusted `displayName`; skip is no-op). Vault saves once at the end.
2. New `SshHost`s are added to `HostsFile` with the same per-row semantics. Renames update labels only — UUIDs are never changed, because tunnel references within the same bundle depend on them. Replace overwrites by UUID.
3. New `SshTunnel`s added to `TunnelsFile` with the same rules. Tunnel `TunnelHost.InternalHost(uuid)` references are validated: if the referenced host UUID is neither already in the user's `HostsFile` nor being imported in this pass, the tunnel is skipped with a log line (defense against hand-edited bundles).
4. All three files use their existing atomic-write persistence (temp file + rename). No new persistence code.
5. Write failure during execution (disk full, permission denied): stop, report the error, accept partial state. Per-file atomicity makes "half-imported" rare and recoverable. Cross-file rollback is explicitly out of scope for v1.

### Result

Brief summary dialog: *"Imported 5 hosts, 3 tunnels, 4 credentials. 1 skipped."* Dismissing refreshes both tool windows.

## Conversion Helpers

### SshConfigReader

Reads `~/.ssh/config` and extracts a single alias. Scope for v1:

- **Supported directives:** `Host <exact-alias>`, `HostName`, `Port`, `User`, `IdentityFile`, `ProxyCommand`, `ProxyJump`. These map 1:1 to `SshHost` fields.
- **Partial support:** `Include` directives are followed recursively (max depth 5) so users with modular configs work. Tilde expansion is performed.
- **Unsupported, reported as warnings:** wildcard `Host *` blocks, `Match` blocks, inherited defaults from wildcard sections, any directive we don't recognize. If an alias is only defined under a wildcard, the reader returns a warning and the tunnel is excluded from the conversion set; user can override at the preview step.
- **Parser:** handwritten, line-based, roughly 150 lines. No dependency on OpenSSH.
- **Return type:** `Result<SshHost, ConversionWarning>` — either a synthesized `SshHost` (fresh UUID, label = alias name) or a warning explaining why.

### KeyFileImporter

Reads a private key from disk and synthesizes the vault records needed to replace a `KeyFileAuth`.

- **Input:** path (from `KeyFileAuth.path`), the SSH host's `username`, and an optional passphrase.
- **Detection:** file content markers — `-----BEGIN OPENSSH PRIVATE KEY-----`, `-----BEGIN RSA PRIVATE KEY-----`, etc. Encrypted keys (OpenSSH encrypted block or PEM `Proc-Type: 4,ENCRYPTED`) require a passphrase at this step; if not provided, return a warning and skip.
- **Output:** a `VaultAccount` with `AuthMethod = Key`, using the host's `username` for the account's `username` field. The key material is stored according to whatever shape the existing vault model uses — either embedded directly in the `AuthMethod.Key` variant, or as a standalone `VaultKey` that the account references by ID. The exporter uses whichever the vault already does; no schema change. Label is derived from the file basename (`id_ed25519` → `"id_ed25519 (imported from key file)"`). Passphrase is stored alongside the key material.
- **Errors:** file missing → warning "Key file not found: `<path>`"; permission denied → warning "Can't read key file: `<path>`"; unrecognized format → warning "File doesn't look like a private key". Host falls back to export-as-`KeyFileAuth` or is skipped, handled at the preview step.

Both helpers surface warnings through the export preview modal so the user sees everything in one place before committing.

## Testing Strategy

### Unit tests (`plugins/share/test/`, JUnit 5)

- **`ShareBundleCodecTest`** — round-trip with a known password; wrong password fails cleanly; corrupt magic / truncated ciphertext fail cleanly; schema versions `0` and `999` rejected; encoded bundles decode back byte-equivalently.
- **`ExportPlannerTest`** — table-driven. Covers: tunnel → internal host auto-pull; tunnel → `SshConfigHost` conversion (fake `SshConfigReader`); host → `KeyFileAuth` conversion (fake `KeyFileImporter`); host → `VaultAuth` credential copy; credentials-off mode (hosts downgraded to `PromptPasswordAuth`, key-file hosts left alone).
- **`ImportPlannerTest`** — given existing state + decoded bundle, produce the right `ImportItem` list. Covers all four status values and default actions.
- **`SshConfigReaderTest`** — corpus of `~/.ssh/config` fixtures: direct alias, `Include`, `ProxyJump`, tilde expansion, wildcard-only alias (warning), `Match` block (warning), unknown directive (warning).
- **`KeyFileImporterTest`** — OpenSSH unencrypted; OpenSSH encrypted (correct + wrong passphrase); PEM RSA; a text file pretending to be a key (rejected).

### Integration tests (`plugins/share/test/integration/`)

Use real `HostsFile` / `TunnelsFile` / `VaultFile` against a temp directory:

- **Full round-trip** — seed machine-A state, export, import into machine-B state, verify hosts/tunnels/credentials arrive byte-identical (modulo renamed labels).
- **Import with no existing vault** — start with no `vault.enc`, run import, verify `LockManager.createVault` is called and credentials land in a fresh vault.
- **Conflict resolution execution** — preset plan with one Replace, one Skip, one Rename; verify end state matches exactly.
- **Partial failure** — simulate a `TunnelsFile` write error after `HostsFile` succeeds; verify we stop cleanly and partial state is as expected.

### Manual / UI checklist (documented in PR)

- Toolbar buttons appear in both tool windows and the top-level menu.
- Export dialog opens preselected to the right tab based on entry point.
- Import dialog handles all three vault states (no vault, locked, unlocked) and the inline create-vault step advances cleanly.
- Preview shows conversions and warnings readably.
- Per-conflict "apply to all" checkbox persists decisions correctly.

### Not tested

- The native file chooser itself.
- IntelliJ's dialog framework.
- The vault's crypto primitives (already tested in `plugins/vault`).

## Open Questions

None at design time. Questions that arise during implementation should come back to this document as amendments.

## Related Work

- `docs/plans/2026-04-10-vault-plugin.md` — vault plugin, whose crypto primitives and `LockManager` are reused here.
- `docs/plans/2026-04-11-ssh-plugin.md` — SSH plugin, whose `SshHost` model, `HostsFile`, and `CredentialProvider` integration points are reused here.
- `docs/plans/2026-04-14-conch-roadmap.md` — project roadmap. Share/import is not currently in the published roadmap; this document introduces it.
