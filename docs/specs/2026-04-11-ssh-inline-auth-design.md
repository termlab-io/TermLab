# SSH Inline-Auth Design

**Goal:** extend `HostEditDialog` so an SSH host can authenticate with one of three credential modes — a saved vault credential (current behavior), a password typed at every connect, or a key file (with the path persisted and the passphrase prompted at every connect). Typed secrets are never written to disk.

**Driving constraint:** each connect is a fresh prompt. The host entry "remembers" the mode, never the secret. Reference model is `~/.ssh/config` — `IdentityFile` is plaintext, the passphrase is not.

## Architecture

The host's credential reference is lifted from a single `@Nullable UUID credentialId` field to a non-null polymorphic `SshAuth auth` field. `SshAuth` is a sealed interface with three variants, and `SshSessionProvider.createSession` dispatches on the variant to build a short-lived `SshResolvedCredential` before handing the host to `TermLabSshClient.connect`. The tool window, palette contributor, and all persistence code reads the new field through the same sealed-type switch — the mode is enforced by the compiler at every call site.

JSON schema is migrated once on load: legacy hosts with a bare `credentialId` are rewritten into `{"auth": {"type": "vault", "credentialId": ...}}` before Gson deserializes them. New writes always use the `auth` object.

## Data model

```java
// plugins/ssh/src/com/termlab/ssh/model/SshAuth.java
public sealed interface SshAuth
    permits VaultAuth, PromptPasswordAuth, KeyFileAuth {}
```

```java
// Each variant is a sibling record in the same package.
public record VaultAuth(@Nullable UUID credentialId) implements SshAuth {}
public record PromptPasswordAuth() implements SshAuth {}
public record KeyFileAuth(@NotNull String keyFilePath) implements SshAuth {}
```

`VaultAuth(null)` is the legitimate "no saved vault entry — run the picker at connect time" state, kept so that `HostEditDialog`'s `<no credential>` option and the existing fallback path both keep working without a fourth variant.

`SshHost` swaps `@Nullable UUID credentialId` for `@NotNull SshAuth auth`:

```java
public record SshHost(
    @NotNull UUID id,
    @NotNull String label,
    @NotNull String host,
    int port,
    @NotNull String username,
    @NotNull SshAuth auth,
    @NotNull Instant createdAt,
    @NotNull Instant updatedAt
) { ... }
```

The `withCredentialId` helper is replaced by `withAuth(SshAuth)`. `withEdited` takes `SshAuth auth` in the last slot instead of `@Nullable UUID credentialId`. `SshHost.create` does the same.

## JSON persistence

A new `SshAuthAdapter` registered in `SshGson` mirrors the vault plugin's `AuthMethodAdapter`:

```json
// VaultAuth with saved id
"auth": { "type": "vault", "credentialId": "c7e8…" }

// VaultAuth without saved id
"auth": { "type": "vault" }

// PromptPasswordAuth
"auth": { "type": "prompt_password" }

// KeyFileAuth
"auth": { "type": "key_file", "keyFilePath": "/home/alice/.ssh/id_ed25519" }
```

`HostsFile.load` runs a one-time pre-deserialize migration on each host entry: if the JSON object has a top-level `credentialId` field and no `auth` field, the loader synthesizes `"auth": {"type": "vault", "credentialId": ...}` and strips the legacy field. New writes go through Gson and never produce the legacy shape.

## `HostEditDialog` UI

Layout mirrors `AccountEditDialog`: static fields up top, a radio group with three options, and a panel per option whose children are enabled/disabled as the selected radio changes.

```
Label:       [______________________]
Host:        [______________________]
Port:        [22   ▲▼]
Username:    [______________________]

Auth method:
  (•) Vault credential   [▼ credential combo     ]
  ( ) Password            (prompt every connect)
  ( ) SSH key file        [/path/to/key........] [Browse…]
                          (prompt passphrase every connect)
```

The combo population logic is unchanged — `CredentialProvider.listCredentials()` filtered to `SUPPORTED_KINDS`, plus a `<no credential>` entry. `doOKAction` reads the selected radio and constructs one of:

- `new VaultAuth(comboSelection.id())` — `id()` returns `null` for the `<no credential>` row
- `new PromptPasswordAuth()`
- `new KeyFileAuth(keyPathField.getText().trim())` — validated as an existing regular file in `doValidate`

The "missing credential" fallback (saved UUID no longer resolvable) stays as a degenerate entry inside the vault combo — nothing changes for that case.

## Connect-time prompt

New file: `plugins/ssh/src/com/termlab/ssh/ui/InlineCredentialPromptDialog.java`. Thin `DialogWrapper` with a single `JPasswordField` and a message line:

- Password mode: `Password for alice@db.example.com:22`
- Passphrase mode: `Passphrase for /Users/alice/.ssh/id_ed25519 (alice@db.example.com)`, followed by a gray `Leave blank if the key is unencrypted.` hint

```java
public final class InlineCredentialPromptDialog extends DialogWrapper {
    public static @Nullable char[] promptPassword(@Nullable Project project, @NotNull SshHost host);
    public static @Nullable char[] promptPassphrase(@Nullable Project project, @NotNull SshHost host, @NotNull String keyPath);
}
```

Returns a freshly-allocated `char[]` on OK (caller owns and zeroes) or `null` on cancel. Runs on the EDT — the session provider already invokes `createSession` on the EDT before handing off to `Task.Modal`, so this prompt runs *before* the modal wraps the blocking connect.

MVP always prompts for a passphrase on `KeyFileAuth` rather than probing whether the key is encrypted. Users accustomed to OpenSSH accept this; a "prompt only when needed" v2 is a trivial follow-up.

## Credential resolution flow

`SshCredentialResolver.resolve` changes signature from `resolve(SshHost)` to `resolve(UUID credentialId, String fallbackUsername)`. The dispatcher that decides what "resolve" *means* for a given host lives in `SshSessionProvider.createSession`:

```java
SshResolvedCredential credential = switch (host.auth()) {
    case VaultAuth v -> {
        if (v.credentialId() == null) yield picker.pick(host);
        SshResolvedCredential saved = resolver.resolve(v.credentialId(), host.username());
        yield saved != null ? saved : picker.pick(host);
    }
    case PromptPasswordAuth p -> buildPasswordCredential(host);
    case KeyFileAuth k -> buildKeyFileCredential(host, k);
};
```

- `buildPasswordCredential` shows `InlineCredentialPromptDialog.promptPassword(project, host)` and wraps the result in `SshResolvedCredential.password(host.username(), chars)`.
- `buildKeyFileCredential` shows `InlineCredentialPromptDialog.promptPassphrase(project, host, k.keyFilePath())` and wraps the result in `SshResolvedCredential.key(host.username(), Path.of(k.keyFilePath()), chars-or-null)`. Empty input becomes a `null` passphrase so the MINA key loader skips decryption.

### AUTH_FAILED retry

`connectWithRetry` currently re-invokes `picker.pick(host)` on `AUTH_FAILED`. For `PromptPassword` / `KeyFile` hosts that's wrong — the retry must re-run the inline prompt, not the vault picker. A small `Retrier` functional interface is threaded in:

```java
interface Retrier { @Nullable SshResolvedCredential retry(@NotNull SshHost host); }
```

Each branch of the dispatcher produces its own `Retrier`:
- `VaultAuth` → `host -> picker.pick(host)`
- `PromptPasswordAuth` → `host -> buildPasswordCredential(host)`
- `KeyFileAuth` → `host -> buildKeyFileCredential(host, k)`

`connectWithRetry` takes `(host, initialCredential, retrier)` instead of `(host, initialCredential, picker)`.

## Testing

**Unit tests:**
- `SshAuthJsonTest`: round-trip each variant through Gson (`vault+id`, `vault+null`, `prompt_password`, `key_file`).
- `SshHostJsonTest`: round-trip a host carrying each variant through `HostsFile.save` + `HostsFile.load`.
- `HostsFileLegacyMigrationTest`: write a legacy JSON file by hand, load it, assert the host comes back with `VaultAuth(savedId)`, and that a subsequent save produces the new shape with no legacy field.
- `SshCredentialResolverTest`: updated to the new `resolve(UUID, String)` signature. Existing test cases for standalone-key username injection and vault-locked behavior stay.

**Manual smoke test (re-runs Phase 5 gate for all three modes):**
1. Add a host with `Vault credential` → existing behavior still works end-to-end.
2. Add a host with `Password` → connect → inline prompt appears → type password → remote shell. Reconnect → prompt appears again, confirming nothing was cached.
3. Add a host with `SSH key file` pointing at an unencrypted key → connect → inline passphrase prompt appears → leave blank → remote shell.
4. Repeat #3 with an encrypted key → leave blank → AUTH_FAILED → retry loop re-prompts → type passphrase → remote shell.
5. Edit an existing host from mode X to mode Y → save → reopen dialog → confirm Y is selected.

## Scope

**In scope:** model/JSON changes, `HostEditDialog` rewrite, `InlineCredentialPromptDialog`, `SshSessionProvider` dispatch, resolver signature change, migration, the tests listed above.

**Out of scope:** known-hosts / `TermLabServerKeyVerifier` changes, tool-window changes (beyond whatever naturally follows from the model change), palette-contributor changes (same), vault-plugin changes, "smart" encrypted-key detection, the `-o IdentitiesOnly`-style fine control.
