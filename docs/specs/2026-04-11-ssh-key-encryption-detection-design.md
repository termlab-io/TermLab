# SSH Key Encryption Detection Design

**Goal:** Detect at connect time whether an `SshHost` configured with `KeyFileAuth` points at an encrypted or an unencrypted private key. Skip the passphrase prompt entirely when the key is unencrypted; show it only when a passphrase is actually needed.

**Driving constraint:** no format-specific parser code in TermLab — reuse MINA SSHD's real parser for the detection so there's no class of "detection disagreed with the real loader" bugs.

## Architecture

Introduce a `KeyFileInspector` utility that probes a key file by invoking MINA's `SecurityUtils.loadKeyPairIdentities` with a `FilePasswordProvider` whose only job is to flip a flag and abort the load when it's asked for a password. If the flag is set after the call, the key is encrypted (MINA asked because it needed one). If the load completes without the flag being set, the key is unencrypted. Any other load failure (malformed file, unreadable, unknown format) surfaces as `IOException` and is treated as a hard error at the session-provider layer.

`SshSessionProvider.keyFileSource` calls the inspector before deciding whether to prompt. Unencrypted keys produce a `SshResolvedCredential.key(username, path, null)` directly. Encrypted keys take the existing `InlineCredentialPromptDialog.promptPassphrase` path. Inspection errors abort the connect with a user-visible dialog — no prompt, no retry, because typing a passphrase can't fix a missing or malformed file.

## `KeyFileInspector`

New class at `plugins/ssh/src/com/termlab/ssh/client/KeyFileInspector.java`:

```java
public final class KeyFileInspector {

    public enum Encryption { NONE, ENCRYPTED }

    private KeyFileInspector() {}

    /**
     * Probe whether a private key file requires a passphrase to decrypt.
     *
     * @throws IOException if the file is missing, unreadable, or not a
     *                     key format MINA can parse
     */
    public static Encryption inspect(Path keyPath) throws IOException {
        AtomicBoolean wasAsked = new AtomicBoolean(false);
        FilePasswordProvider probe = (session, resource, retry) -> {
            wasAsked.set(true);
            throw new RuntimeException("abort probe");
        };
        try (InputStream in = Files.newInputStream(keyPath)) {
            try {
                SecurityUtils.loadKeyPairIdentities(
                    null, NamedResource.ofName(keyPath.toString()), in, probe);
            } catch (Exception e) {
                if (wasAsked.get()) return Encryption.ENCRYPTED;
                throw new IOException(
                    "Could not parse key file " + keyPath + ": " + e.getMessage(), e);
            }
        }
        return Encryption.NONE;
    }
}
```

Three outcome paths:

1. **Load succeeds, probe never called** → `NONE`. MINA parsed the key without needing a password.
2. **Load fails, probe WAS called** → `ENCRYPTED`. Our provider aborted MINA's decryption path, which is only reached after MINA has determined the key is encrypted and needs a passphrase.
3. **Load fails, probe NOT called** → the failure is in parsing, not decryption. Wrap as `IOException` and propagate.

## `SshSessionProvider.keyFileSource`

Rewrite the existing method to call the inspector before prompting:

```java
private static @Nullable SshResolvedCredential keyFileSource(
    @NotNull SshHost host,
    @NotNull KeyFileAuth auth
) {
    Path keyPath = Path.of(auth.keyFilePath());

    KeyFileInspector.Encryption encryption;
    try {
        encryption = KeyFileInspector.inspect(keyPath);
    } catch (IOException e) {
        Messages.showErrorDialog(
            "Could not read SSH key file:\n" + keyPath + "\n\n" + e.getMessage(),
            "SSH Key File Unreadable");
        return null;
    }

    if (encryption == KeyFileInspector.Encryption.NONE) {
        return SshResolvedCredential.key(host.username(), keyPath, null);
    }

    char[] passphrase = InlineCredentialPromptDialog.promptPassphrase(
        null, host, auth.keyFilePath());
    if (passphrase == null) return null;
    return SshResolvedCredential.key(host.username(), keyPath, passphrase);
}
```

The current method's "empty input becomes null passphrase" special case goes away — we never reach the prompt for an unencrypted key, so empty input always means "user typed nothing for a key that actually needs a passphrase", which is a user error MINA will reject.

## `InlineCredentialPromptDialog.promptPassphrase`

Drop the "Leave blank if the key is unencrypted" hint. The factory now only gets called for known-encrypted keys, so the hint would mislead. One-line change: pass `null` for the hint argument where the factory currently passes `"Leave blank if the key is unencrypted."`.

## Testing

New test class `plugins/ssh/test/com/termlab/ssh/client/KeyFileInspectorTest.java` with four cases:

- **`inspect_unencryptedOpenSshKey_returnsNone`** — writes a known-unencrypted Ed25519 key to `@TempDir`, asserts `NONE`.
- **`inspect_encryptedOpenSshKey_returnsEncrypted`** — writes a known-encrypted Ed25519 key (passphrase `test-passphrase`) to `@TempDir`, asserts `ENCRYPTED`.
- **`inspect_missingFile_throwsIOException`** — points at a non-existent path.
- **`inspect_garbageFile_throwsIOException`** — writes `"not a key"` to a file.

The two real-key fixtures are embedded as `"""..."""` text-block constants in the test class, generated by `ssh-keygen -t ed25519`. No checked-in binary fixtures, no Bazel resource wiring, no BouncyCastle-at-test-time setup. The fixtures are throwaway keys that exist only to prove MINA's parser reacts to the encryption marker; they're never used as real credentials.

## Scope

**In scope:**
- `KeyFileInspector` + `KeyFileInspectorTest`
- `SshSessionProvider.keyFileSource` rewrite
- One-line change to `InlineCredentialPromptDialog.promptPassphrase` (drop the hint)

**Out of scope:**
- Vault-resolved keys (kinds `ACCOUNT_KEY`/`ACCOUNT_KEY_AND_PASSWORD`) — those arrive with a passphrase already set at vault-creation time. Inspection would not improve anything.
- `PromptPasswordAuth` — unchanged.
- Known-hosts / server-key verifier.
- Adding a cached "this key is known unencrypted" flag to `KeyFileAuth`. Inspection is cheap and a key file can change on disk between connects, so caching is premature optimization.
