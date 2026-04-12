# Search Everywhere Contributors Design

**Goal:** Make Conch's SSH hosts and vault entries actually reachable from the command palette (Cmd+Shift+P), and retire the dead `CommandPaletteContributor` SDK interface that's been pretending to do this work.

**Driving context:** The "Conch command palette" is IntelliJ's Search Everywhere with a tab allowlist (`ConchTabsCustomizationStrategy`) and a built-in blocklist (`ConchSearchEverywhereCustomizer`). The only non-platform tab that currently shows up is `TerminalPaletteContributor` (`ConchTerminals`), which plugs directly into `com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor`. The SDK's `CommandPaletteContributor` interface and its `PaletteItem` carrier type are implemented by `HostsPaletteContributor` (SSH) and `VaultPaletteContributor` (vault), but **nothing reads the `<commandPaletteContributor>` extension point** — there is no bridge into Search Everywhere. Both classes are dead code.

## Architecture

Drop the SDK's `CommandPaletteContributor` abstraction and have each plugin implement `SearchEverywhereContributor` directly, mirroring `TerminalPaletteContributor`. The SSH plugin gets `HostsSearchEverywhereContributor` for saved hosts. The vault plugin gets `VaultSearchEverywhereContributor` for accounts and keys, plus two new `AnAction`s (`LockVaultAction`, `GenerateSshKeyAction`) that move the dead code's menu-style entries into IntelliJ's native Actions tab where they belong. Core adds the two new contributor IDs to the tab allowlist and removes the unused extension point. The SDK drops `CommandPaletteContributor` and `PaletteItem` entirely.

## SSH plugin

**New file:** `plugins/ssh/src/com/conch/ssh/palette/HostsSearchEverywhereContributor.java`.

Implements `SearchEverywhereContributor<SshHost>`. Structured like `TerminalPaletteContributor`:

- `getSearchProviderId()` → `"ConchHosts"`
- `getGroupName()` → `"Hosts"`
- `getSortWeight()` → `50`
- `isShownInSeparateTab()` → `true`
- `isEmptyPatternSupported()` → `true` (blank query lists every saved host)
- `fetchElements(pattern, indicator, consumer)` — reads `HostStore` via `ApplicationManager.getService(HostStore.class)`. For a blank pattern, consumes every host sorted by label. For a non-blank pattern, filters by case-insensitive substring match on `label`, `host`, or `username` — same rules the dead `HostsPaletteContributor` used.
- `processSelectedItem(selected, modifiers, searchText)` — casts `selected` to `SshHost` and calls `ConnectToHostAction.run(project, host)` directly. Returns `true`. No `invokeLater` wrapping: Search Everywhere closes itself on the EDT before the callback fires, so the `openFile` call runs on the same EDT pass.
- `getElementsRenderer()` — a `ListCellRenderer<Object>` that reuses the existing `HostCellRenderer` for `SshHost` values and falls through to a `DefaultListCellRenderer` for anything else (defensive).
- `getDataForItem(element, dataId)` — returns `element` for any dataId (matches `TerminalPaletteContributor`).
- Nested `Factory implements SearchEverywhereContributorFactory<SshHost>` that extracts the `Project` from `AnActionEvent` and constructs the contributor, matching `TerminalPaletteContributor.Factory`.

**Registration:** `plugins/ssh/resources/META-INF/plugin.xml` gains `<searchEverywhereContributor implementation="com.conch.ssh.palette.HostsSearchEverywhereContributor$Factory"/>` in the `com.intellij` extensions block. The old `<commandPaletteContributor implementation="com.conch.ssh.palette.HostsPaletteContributor"/>` line in the `com.conch.core` extensions block is removed.

**Deletion:** `plugins/ssh/src/com/conch/ssh/palette/HostsPaletteContributor.java` is deleted.

## Vault plugin

**New file:** `plugins/vault/src/com/conch/vault/palette/VaultSearchEverywhereContributor.java`.

Implements `SearchEverywhereContributor<Object>`. The `Object` type parameter is necessary because the tab holds both `VaultAccount` and `VaultKey` values; the renderer and `processSelectedItem` dispatch with `instanceof`.

- `getSearchProviderId()` → `"ConchVault"`
- `getGroupName()` → `"Vault"`
- `getSortWeight()` → `60`
- `isShownInSeparateTab()` → `true`
- `isEmptyPatternSupported()` → `true`
- `fetchElements`:
  - Looks up `LockManager` via `ApplicationManager.getService(LockManager.class)`.
  - If `lm == null` or `lm.getVault() == null` (locked / no vault), returns immediately without consuming anything. The empty tab is legitimate — the user unlocks via Cmd+Shift+V or the Actions tab entry for "Open Vault".
  - Otherwise iterates accounts and keys. Filters by case-insensitive substring match against `displayName` + `username` for accounts, and `name` + `algorithm` for keys.
  - Emits accounts first, then keys, both sorted by their display name within each group. No "Generate SSH Key" / "Lock Vault" palette entries — those become real AnActions (see below).
- `processSelectedItem`:
  - If `selected` is a `VaultAccount`: opens `AccountEditDialog.show(project, account)`. On OK, replaces the account in the vault and calls `lm.save()`. Exceptions during save are swallowed with a log message — the palette callback context can't reliably surface modal errors, same as the dead code.
  - If `selected` is a `VaultKey`: opens `KeyEditDialog.show(project, key)`. Same pattern.
  - Returns `true` in both cases.
- `getElementsRenderer()` — dispatches with `instanceof`:
  - `VaultAccount` → "displayName  —  username"
  - `VaultKey` → "name  —  algorithm  ·  fingerprint"
- Nested `Factory implements SearchEverywhereContributorFactory<Object>`.

**New `AnAction`s**:

- `plugins/vault/src/com/conch/vault/actions/LockVaultAction.java` — calls `LockManager.lock()`. `update` disables when the vault is already locked or not yet created.
- `plugins/vault/src/com/conch/vault/actions/GenerateSshKeyAction.java` — opens `KeyGenDialog`. `update` disables when the vault is locked.

Both are registered in `plugins/vault/resources/META-INF/plugin.xml` alongside `OpenVaultAction`. No keyboard shortcuts — they're discoverable via the Actions tab of Search Everywhere.

**Registration:** `plugins/vault/resources/META-INF/plugin.xml` gains `<searchEverywhereContributor implementation="com.conch.vault.palette.VaultSearchEverywhereContributor$Factory"/>`. The old `<commandPaletteContributor implementation="com.conch.vault.palette.VaultPaletteContributor"/>` line is removed.

**Deletion:** `plugins/vault/src/com/conch/vault/palette/VaultPaletteContributor.java` is deleted.

## Core

Two edits:

1. **`core/src/com/conch/core/palette/ConchTabsCustomizationStrategy.java`** — the `ALLOWED_TAB_IDS` set gains `"ConchHosts"` and `"ConchVault"`. Final contents: `{"ActionSearchEverywhereContributor", "ConchTerminals", "ConchHosts", "ConchVault"}`.

2. **`core/resources/META-INF/plugin.xml`** — the `<extensionPoint name="commandPaletteContributor" interface="com.conch.sdk.CommandPaletteContributor" dynamic="true"/>` line is removed. Safe only after both plugins have stopped registering contributors under that EP name (enforced by commit ordering).

`ConchSearchEverywhereCustomizer` is untouched — its blocklist of unwanted built-in contributors is a separate concern.

## SDK

Two files deleted:

- `sdk/src/com/conch/sdk/CommandPaletteContributor.java`
- `sdk/src/com/conch/sdk/PaletteItem.java`

Nothing imports them after the SSH and vault changes land.

## Testing

No new unit tests. `SearchEverywhereContributor` implementations are thin glue over existing services (`HostStore`, `LockManager`) that are already tested. The work is covered by a manual smoke test in the plan:

1. Open Cmd+Shift+P. The tab strip shows Actions / Terminals / Hosts / Vault.
2. Click the Hosts tab. Blank query lists every saved host. Typing narrows the list by label/hostname/username.
3. Select a host → editor tab opens connected to it (same flow as double-clicking in the tool window).
4. Click the Vault tab with the vault unlocked. Accounts and keys are listed. Selecting an account opens the edit dialog; editing and saving persists through the lock manager.
5. Click the Vault tab with the vault locked. Tab is empty (no error).
6. Open the Actions tab. Type "lock vault" → `LockVaultAction` appears. Selecting it locks the vault (confirmed by the Vault tab going empty).
7. Type "generate ssh key" in the Actions tab with the vault unlocked → `GenerateSshKeyAction` appears. Selecting it opens the key-gen dialog.
8. Regression: `Cmd+Shift+V` still opens the vault (existing `OpenVaultAction`, untouched).

## Scope

**In scope:**
- SSH plugin: new contributor + Factory + registration, delete old contributor, remove old registration
- Vault plugin: new contributor + Factory + registration, two new `AnAction`s and their registrations, delete old contributor, remove old registration
- Core: allowlist update, remove `commandPaletteContributor` extension point
- SDK: delete `CommandPaletteContributor` and `PaletteItem`

**Not in scope:**
- Any change to `ConchSearchEverywhereCustomizer` or the blocklist of unwanted built-in contributors
- New palette behavior beyond what the dead code already designed
- Tool window, dialog, or model changes in either plugin
- Keyboard shortcuts for the two new AnActions — they're Actions-tab-only

## Commit ordering

The plan must land in three commits in this order to keep the build green at each step:

1. **SSH plugin.** Add `HostsSearchEverywhereContributor` + `<searchEverywhereContributor>` registration. Delete `HostsPaletteContributor` and its `<commandPaletteContributor>` line. At the end of this commit: SSH no longer touches the SDK interface; vault's `VaultPaletteContributor` still exists and still uses it; core's extension point declaration still matches vault's remaining registration. Everything compiles.

2. **Vault plugin.** Add `VaultSearchEverywhereContributor` + `LockVaultAction` + `GenerateSshKeyAction` and their registrations. Delete `VaultPaletteContributor` and its `<commandPaletteContributor>` line. At the end of this commit: no plugin uses the SDK interface anymore, but the interface files and the core extension point declaration are still on disk. Everything compiles.

3. **Core + SDK cleanup.** Update `ConchTabsCustomizationStrategy.ALLOWED_TAB_IDS`, remove the `<extensionPoint name="commandPaletteContributor">` declaration from core plugin.xml, delete `CommandPaletteContributor.java` and `PaletteItem.java` from the SDK. Safe only because step 2 removed the last consumer.

A mid-sequence build failure would mean removing the SDK interface or the extension point before all consumers have stopped implementing it. The ordering above keeps each commit independently buildable.
