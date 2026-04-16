# SSH Key Encryption Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect at connect time whether a `KeyFileAuth` host's key file is encrypted, so unencrypted keys skip the passphrase prompt entirely.

**Architecture:** New `KeyFileInspector` utility probes a key file by invoking MINA SSHD's real parser with a `FilePasswordProvider` that flags-and-aborts when asked for a password. `SshSessionProvider.keyFileSource` calls the inspector before deciding whether to prompt. Unencrypted → no prompt. Encrypted → existing prompt. Inspection error → hard abort with user-visible dialog.

**Tech Stack:** Java 21, Apache MINA SSHD 2.15 (`SecurityUtils.loadKeyPairIdentities`), JUnit 5.

**Reference spec:** `docs/specs/2026-04-11-ssh-key-encryption-detection-design.md`

---

## File Structure

**New files:**
- `plugins/ssh/src/com/termlab/ssh/client/KeyFileInspector.java` — static utility with a single `inspect(Path) → Encryption` method. Lives next to `TermLabSshClient` because it shares MINA-parser concerns with the rest of the `client/` package.
- `plugins/ssh/test/com/termlab/ssh/client/KeyFileInspectorTest.java` — four test cases covering unencrypted / encrypted / missing / garbage. Fixture key content is embedded as text-block constants inside the test file.

**Modified files:**
- `plugins/ssh/src/com/termlab/ssh/provider/SshSessionProvider.java` — `keyFileSource` method rewritten to call the inspector and branch on the outcome.
- `plugins/ssh/src/com/termlab/ssh/ui/InlineCredentialPromptDialog.java` — drop the "Leave blank if the key is unencrypted" hint string from `promptPassphrase` (pass `null` instead).

**Unchanged (confirmed by reading the spec scope section):**
- `SshCredentialResolver`, `SshCredentialPicker` — vault-resolved key flows are untouched.
- `TermLabSshClient` — the real MINA load path is unchanged.
- `HostEditDialog` — still writes `KeyFileAuth(path)` on save.
- `SshHost`, `SshAuth`, all model types — no data-model changes.

---

## Build & test commands

From `/Users/dustin/projects/termlab_workbench`:

```bash
# Compile the whole termlab product:
make termlab-build

# Run the SSH plugin test suite:
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/ssh:ssh_test_runner

# Compile just the ssh test lib (faster feedback):
cd /Users/dustin/projects/intellij-community && bash bazel.cmd build //termlab/plugins/ssh:ssh_test_lib
```

Baseline before this plan starts: 83 tests passing (from the preceding inline-auth plan).

---

## Task 1: `KeyFileInspector` + test fixtures

TDD — write the failing tests first, then the implementation. The tests own the fixture key content.

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/client/KeyFileInspector.java`
- Create: `plugins/ssh/test/com/termlab/ssh/client/KeyFileInspectorTest.java`

- [ ] **Step 1: Write the failing test file**

Create `plugins/ssh/test/com/termlab/ssh/client/KeyFileInspectorTest.java` with this exact content. The two text-block constants at the bottom are real Ed25519 private keys produced by `ssh-keygen -t ed25519` — the encrypted one uses passphrase `test-passphrase`. They're throwaway keys, never used as real credentials, so committing them is harmless.

```java
package com.termlab.ssh.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KeyFileInspectorTest {

    @Test
    void inspect_unencryptedOpenSshKey_returnsNone(@TempDir Path tmp) throws Exception {
        Path keyPath = tmp.resolve("id_unenc");
        Files.writeString(keyPath, UNENCRYPTED_ED25519);

        assertEquals(
            KeyFileInspector.Encryption.NONE,
            KeyFileInspector.inspect(keyPath));
    }

    @Test
    void inspect_encryptedOpenSshKey_returnsEncrypted(@TempDir Path tmp) throws Exception {
        Path keyPath = tmp.resolve("id_enc");
        Files.writeString(keyPath, ENCRYPTED_ED25519);

        assertEquals(
            KeyFileInspector.Encryption.ENCRYPTED,
            KeyFileInspector.inspect(keyPath));
    }

    @Test
    void inspect_missingFile_throwsIOException(@TempDir Path tmp) {
        Path keyPath = tmp.resolve("nonexistent");
        assertThrows(IOException.class, () -> KeyFileInspector.inspect(keyPath));
    }

    @Test
    void inspect_garbageFile_throwsIOException(@TempDir Path tmp) throws Exception {
        Path keyPath = tmp.resolve("garbage");
        Files.writeString(keyPath, "not a key at all, just some bytes\n");

        assertThrows(IOException.class, () -> KeyFileInspector.inspect(keyPath));
    }

    // -- fixtures -------------------------------------------------------------
    //
    // Throwaway Ed25519 keys generated via `ssh-keygen -t ed25519`. They
    // exist only to exercise MINA's parser — they're not valid
    // credentials for any real host. The encrypted variant uses
    // passphrase "test-passphrase".

    private static final String UNENCRYPTED_ED25519 =
        "-----BEGIN OPENSSH PRIVATE KEY-----\n"
        + "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\n"
        + "QyNTUxOQAAACCSWhy8xyD/oaVcxp3mP0t0ClZJRp3zfC8bZaki6b/onQAAAJg5u4O7ObuD\n"
        + "uwAAAAtzc2gtZWQyNTUxOQAAACCSWhy8xyD/oaVcxp3mP0t0ClZJRp3zfC8bZaki6b/onQ\n"
        + "AAAEDg0aZNQcOHeaUc1duO8iuGiBDbOQ3kZqKmylS4ehiibJJaHLzHIP+hpVzGneY/S3QK\n"
        + "VklGnfN8LxtlqSLpv+idAAAAEHRlc3QtdW5lbmNyeXB0ZWQBAgMEBQ==\n"
        + "-----END OPENSSH PRIVATE KEY-----\n";

    private static final String ENCRYPTED_ED25519 =
        "-----BEGIN OPENSSH PRIVATE KEY-----\n"
        + "b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABDmmpmxdl\n"
        + "F3taXhLJstFfH9AAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIPWZuhfhUPZM3nWK\n"
        + "XmIGgXS8q4ZNuZrylqO3horzOIMXAAAAoMggc1l3S3ejToteDLTxPzWZdFGVkzCm/7nbuM\n"
        + "OBxhCSN9rB5vbqvmSb/M6AG30LWaMWXHJiaR11BFdsyhvNluPdG0TIRSWBHvswThRa5hP5\n"
        + "7Dnm28/ZwjqBYFa66am5VWPZkVka8brXnJoqkJHg0T81479C3cr8/swSxeclpylUZ8Eu0E\n"
        + "EG5bZXzWXJxWWi/jE8jt4CQNfU9eW4WINeccU=\n"
        + "-----END OPENSSH PRIVATE KEY-----\n";
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd build //termlab/plugins/ssh:ssh_test_lib 2>&1 | tail -15
```

Expected: compilation error `cannot find symbol: class KeyFileInspector`. This proves the test is wired up and that the class doesn't exist yet.

- [ ] **Step 3: Create `KeyFileInspector.java`**

```java
package com.termlab.ssh.client;

import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detects whether an SSH private key file on disk is encrypted
 * (needs a passphrase) or unencrypted.
 *
 * <p>Implementation: invoke MINA SSHD's real parser via
 * {@link SecurityUtils#loadKeyPairIdentities} with a
 * {@link FilePasswordProvider} whose only job is to flip a flag and
 * throw when MINA asks it for a password. Three outcomes:
 *
 * <ol>
 *   <li><b>Load succeeds, probe never invoked</b> → {@link Encryption#NONE}.
 *       MINA parsed the key without needing a password.</li>
 *   <li><b>Load fails, probe WAS invoked</b> → {@link Encryption#ENCRYPTED}.
 *       The only way our provider gets called is if MINA determined the
 *       key is encrypted and needs a passphrase.</li>
 *   <li><b>Load fails, probe NOT invoked</b> → the failure is in parsing,
 *       not decryption. Wrap as {@link IOException} and propagate — the
 *       caller decides how to handle an unreadable or malformed file.</li>
 * </ol>
 *
 * <p>This is the same parser {@code TermLabSshClient.connect} uses for the
 * real load, so by construction the detection can't disagree with what
 * the subsequent load will do.
 */
public final class KeyFileInspector {

    /** Whether a key file needs a passphrase to decrypt. */
    public enum Encryption {
        /** The key loaded cleanly without any passphrase. */
        NONE,
        /** MINA's parser asked for a password before it could decrypt the key. */
        ENCRYPTED
    }

    private KeyFileInspector() {}

    /**
     * Probe {@code keyPath} and report whether it's encrypted.
     *
     * @throws IOException if the file is missing, unreadable, or not a
     *                     key format MINA can parse
     */
    public static @NotNull Encryption inspect(@NotNull Path keyPath) throws IOException {
        AtomicBoolean wasAsked = new AtomicBoolean(false);
        FilePasswordProvider probe = (session, resource, retry) -> {
            wasAsked.set(true);
            // Unwind MINA's parser. Any exception here will be caught
            // below; the wasAsked flag is how we distinguish "our probe
            // triggered this" from "the file is malformed".
            throw new RuntimeException("KeyFileInspector probe abort");
        };

        try (InputStream in = Files.newInputStream(keyPath)) {
            try {
                SecurityUtils.loadKeyPairIdentities(
                    null,
                    NamedResource.ofName(keyPath.toString()),
                    in,
                    probe);
            } catch (Exception e) {
                if (wasAsked.get()) {
                    return Encryption.ENCRYPTED;
                }
                throw new IOException(
                    "Could not parse key file " + keyPath + ": " + e.getMessage(), e);
            }
        }
        return Encryption.NONE;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/ssh:ssh_test_runner 2>&1 | tail -20
```

Expected: 87/87 passing (83 pre-existing + 4 new `KeyFileInspectorTest` tests).

If any of the four new tests fail:
- `inspect_unencryptedOpenSshKey_returnsNone` failing → MINA parsed the file but the test fixture may be wrong. Inspect the text-block constant for stray whitespace from the copy-paste.
- `inspect_encryptedOpenSshKey_returnsEncrypted` failing with NONE → MINA didn't reach the password step, meaning the fixture isn't actually encrypted. Regenerate if needed with `ssh-keygen -t ed25519 -N test-passphrase -f /tmp/test_enc -q -C test-encrypted` and replace the constant.
- `inspect_missingFile_throwsIOException` failing → the method returned normally; check that `Files.newInputStream` is throwing `NoSuchFileException` (an `IOException` subclass) as expected.
- `inspect_garbageFile_throwsIOException` failing → the test garbage string managed to parse as a valid key (very unlikely). Change the content to known-invalid bytes.

- [ ] **Step 5: Commit**

```bash
cd /Users/dustin/projects/termlab_workbench
git add plugins/ssh/src/com/termlab/ssh/client/KeyFileInspector.java \
        plugins/ssh/test/com/termlab/ssh/client/KeyFileInspectorTest.java
git commit -m "$(cat <<'EOF'
feat(ssh): add KeyFileInspector to detect encrypted key files

Probes via MINA's real parser with a flag-and-abort
FilePasswordProvider. Three outcomes: NONE (loaded cleanly),
ENCRYPTED (probe was called), IOException (parse failure).

Next commit wires this into SshSessionProvider.keyFileSource so
unencrypted keys skip the passphrase prompt entirely.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Wire `KeyFileInspector` into `SshSessionProvider.keyFileSource`

Replace the current "always prompt" behavior with a branch on the inspector result.

**Files:**
- Modify: `plugins/ssh/src/com/termlab/ssh/provider/SshSessionProvider.java`

- [ ] **Step 1: Read the current `keyFileSource` method**

Open `plugins/ssh/src/com/termlab/ssh/provider/SshSessionProvider.java` and locate the `keyFileSource` method. It currently looks like this:

```java
private static @Nullable SshResolvedCredential keyFileSource(
    @NotNull SshHost host,
    @NotNull KeyFileAuth auth
) {
    char[] passphrase = InlineCredentialPromptDialog.promptPassphrase(
        null, host, auth.keyFilePath());
    if (passphrase == null) return null;
    // Empty input means "no passphrase" — MINA's key loader skips
    // decryption when we pass null. A zero-length char[] carries no
    // secret, so we don't bother zeroing it.
    char[] phraseOrNull = passphrase.length == 0 ? null : passphrase;
    return SshResolvedCredential.key(
        host.username(),
        Path.of(auth.keyFilePath()),
        phraseOrNull
    );
}
```

- [ ] **Step 2: Replace the method body**

Replace the entire `keyFileSource` method (including its Javadoc block if any) with:

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

- [ ] **Step 3: Add the missing import**

At the top of `SshSessionProvider.java`, add `import com.termlab.ssh.client.KeyFileInspector;` to the existing `com.termlab.ssh.client.*` import block. It should land between `TermLabSshClient` and the other client imports alphabetically:

```java
import com.termlab.ssh.client.TermLabServerKeyVerifier;
import com.termlab.ssh.client.TermLabSshClient;
import com.termlab.ssh.client.KeyFileInspector;
import com.termlab.ssh.client.SshConnectException;
import com.termlab.ssh.client.SshConnection;
import com.termlab.ssh.client.SshResolvedCredential;
```

Also add `import java.io.IOException;` to the `java.*` imports block if it isn't already present.

- [ ] **Step 4: Build**

```bash
cd /Users/dustin/projects/termlab_workbench && make termlab-build 2>&1 | tail -15
```

Expected: `Build completed successfully`. If the build fails with `cannot find symbol: IOException`, it's because the import is missing — add it to the `java.io` imports block.

- [ ] **Step 5: Run the test suite (regression check)**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/ssh:ssh_test_runner 2>&1 | tail -20
```

Expected: 87/87 passing — same count as after Task 1. No new unit tests here; the dispatch logic is exercised by the existing `KeyFileInspectorTest` plus the manual smoke test in Task 4.

- [ ] **Step 6: Commit**

```bash
cd /Users/dustin/projects/termlab_workbench
git add plugins/ssh/src/com/termlab/ssh/provider/SshSessionProvider.java
git commit -m "$(cat <<'EOF'
feat(ssh): skip passphrase prompt for unencrypted key files

SshSessionProvider.keyFileSource now calls KeyFileInspector before
deciding whether to prompt. Unencrypted keys build the resolved
credential directly; encrypted keys take the existing inline prompt
path; inspection failures abort the connect with a user-visible
error dialog (no prompt, no retry — a missing file isn't fixed by
typing a passphrase).

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Drop the "leave blank if unencrypted" hint

The passphrase dialog no longer gets shown for unencrypted keys, so the hint is stale.

**Files:**
- Modify: `plugins/ssh/src/com/termlab/ssh/ui/InlineCredentialPromptDialog.java`

- [ ] **Step 1: Read the current `promptPassphrase` method**

Open `plugins/ssh/src/com/termlab/ssh/ui/InlineCredentialPromptDialog.java` and find the `promptPassphrase` factory. It currently reads:

```java
public static char @Nullable [] promptPassphrase(
    @Nullable Project project,
    @NotNull SshHost host,
    @NotNull String keyFilePath
) {
    InlineCredentialPromptDialog dlg = new InlineCredentialPromptDialog(
        project,
        "SSH Key Passphrase",
        "Passphrase for " + keyFilePath
            + " (" + host.username() + "@" + host.host() + ")",
        "Leave blank if the key is unencrypted.");
    return dlg.showAndGet() ? dlg.result : null;
}
```

- [ ] **Step 2: Replace the hint string with `null`**

Change the last argument of the `InlineCredentialPromptDialog` constructor call from the string literal to `null`. The method becomes:

```java
public static char @Nullable [] promptPassphrase(
    @Nullable Project project,
    @NotNull SshHost host,
    @NotNull String keyFilePath
) {
    InlineCredentialPromptDialog dlg = new InlineCredentialPromptDialog(
        project,
        "SSH Key Passphrase",
        "Passphrase for " + keyFilePath
            + " (" + host.username() + "@" + host.host() + ")",
        null);
    return dlg.showAndGet() ? dlg.result : null;
}
```

No other changes to the file — the constructor and `createCenterPanel` already handle `hint == null` by omitting the hint row.

- [ ] **Step 3: Build and run tests**

```bash
cd /Users/dustin/projects/termlab_workbench && make termlab-build 2>&1 | tail -10
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/ssh:ssh_test_runner 2>&1 | tail -10
```

Expected: build succeeds, 87/87 passing.

- [ ] **Step 4: Commit**

```bash
cd /Users/dustin/projects/termlab_workbench
git add plugins/ssh/src/com/termlab/ssh/ui/InlineCredentialPromptDialog.java
git commit -m "$(cat <<'EOF'
feat(ssh): drop stale 'leave blank if unencrypted' hint from passphrase prompt

The prompt is now only shown when KeyFileInspector has confirmed the
key is encrypted, so the hint would mislead.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Smoke-test gate

Not code — checklist. Block merging until every item passes.

- [ ] **Step 1: Run the full test suite**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/ssh:ssh_test_runner 2>&1 | tail -20
```

Expected: 87/87 passing.

- [ ] **Step 2: Build the full product**

```bash
cd /Users/dustin/projects/termlab_workbench && make termlab-build 2>&1 | tail -15
```

Expected: `Build completed successfully`.

- [ ] **Step 3: Manual smoke test — unencrypted key skips the prompt**

```bash
cd /Users/dustin/projects/termlab_workbench && make termlab
```

Generate an unencrypted throwaway key:

```bash
ssh-keygen -t ed25519 -f /tmp/smoke_unenc -N '' -q -C smoke-test
```

Add it to your own machine's `~/.ssh/authorized_keys` so the key actually authorizes into `localhost`:

```bash
cat /tmp/smoke_unenc.pub >> ~/.ssh/authorized_keys
```

1. In the running TermLab, open the Hosts tool window.
2. Add a host: label "localhost unencrypted", host `127.0.0.1`, port `22`, username `$USER`, auth method "SSH key file", path `/tmp/smoke_unenc`.
3. Double-click the host.
4. **Expected:** no passphrase dialog appears. The "Connecting to …" progress indicator pops, then a local shell via SSH.
5. Close the tab.
6. Clean up: `ssh-keygen -R 127.0.0.1` (removes the known_hosts entry) and remove the entry from `authorized_keys` and delete `/tmp/smoke_unenc{,.pub}`.

- [ ] **Step 4: Manual smoke test — encrypted key still prompts**

Generate an encrypted throwaway key:

```bash
ssh-keygen -t ed25519 -f /tmp/smoke_enc -N 'smoke-passphrase' -q -C smoke-test
cat /tmp/smoke_enc.pub >> ~/.ssh/authorized_keys
```

1. In TermLab, add another host: label "localhost encrypted", path `/tmp/smoke_enc`, other fields the same.
2. Double-click.
3. **Expected:** passphrase prompt appears with title "SSH Key Passphrase" and body "Passphrase for /tmp/smoke_enc (user@127.0.0.1)". No "leave blank" hint. Enter `smoke-passphrase`. Connect succeeds.
4. Close the tab.
5. Clean up: same as above.

- [ ] **Step 5: Manual smoke test — missing key file**

1. Add a host pointing at `/tmp/definitely_not_a_key_file_here`.
2. Double-click.
3. **Expected:** error dialog with title "SSH Key File Unreadable" and the file path in the body. No passphrase prompt. No connect attempt.

- [ ] **Step 6: Manual smoke test — garbage key file**

```bash
echo "this is not an ssh key" > /tmp/smoke_garbage
```

1. Add a host pointing at `/tmp/smoke_garbage`.
2. Double-click.
3. **Expected:** error dialog with title "SSH Key File Unreadable". The error body should include MINA's parse failure description. No passphrase prompt. No connect attempt.
4. Clean up: `rm /tmp/smoke_garbage`.

- [ ] **Step 7: Regression check — encrypted key from the previous plan still works**

If you still have any existing `KeyFileAuth` hosts from the previous smoke test, double-click them and confirm the passphrase prompt appears without the "leave blank" hint. Connect succeeds when you type the passphrase.

If any of Steps 1-7 fail, stop and fix before merging.

---

## Self-review checklist (plan author ran this before handing off)

**1. Spec coverage:**
- `KeyFileInspector` class → Task 1 ✓
- MINA-probe approach with `wasAsked` flag → Task 1 Step 3 ✓
- Three outcomes (NONE / ENCRYPTED / IOException) → all three exercised by Task 1 Step 1 tests ✓
- `SshSessionProvider.keyFileSource` rewrite → Task 2 ✓
- Error dialog path for unreadable/malformed files → Task 2 Step 2 ✓
- `InlineCredentialPromptDialog` hint removal → Task 3 ✓
- Four test cases (unencrypted / encrypted / missing / garbage) → Task 1 Step 1 ✓
- Fixtures embedded as text-block constants → Task 1 Step 1 ✓
- Smoke test covering all four runtime scenarios → Task 4 ✓

No gaps.

**2. Type / signature consistency:**
- `KeyFileInspector.inspect(Path) → Encryption` — one signature, used in Task 1 (test), Task 2 (call site).
- `KeyFileInspector.Encryption.NONE` / `ENCRYPTED` — two enum constants, used consistently everywhere.
- `InlineCredentialPromptDialog.promptPassphrase(Project, SshHost, String)` — signature unchanged (only the body's constructor argument changes), so Task 2's call site stays the same.
- `SshResolvedCredential.key(String username, Path keyPath, char @Nullable [] keyPassphrase)` — used in Task 2 with both `null` and a non-null `passphrase`. Matches the existing factory.

**3. Placeholder scan:**
No "TBD", no "similar to", no "add validation" hand-waving. Every code block is complete. Every command is exact.
