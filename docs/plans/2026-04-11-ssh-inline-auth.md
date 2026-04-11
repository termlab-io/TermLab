# SSH Inline Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `PromptPassword` and `KeyFile` auth modes to `SshHost`, so an SSH host can authenticate with a saved vault credential (current), a password prompted at every connect, or a key file whose path is persisted but whose passphrase is prompted every connect.

**Architecture:** Replace `SshHost.credentialId` with a non-null polymorphic `SshAuth` sealed interface (`VaultAuth` / `PromptPasswordAuth` / `KeyFileAuth`). `SshSessionProvider.createSession` dispatches on the variant — vault goes through the existing resolver+picker flow; prompt-password and key-file go through a new `InlineCredentialPromptDialog`. JSON persistence grows a discriminator-based Gson adapter mirroring the vault plugin's `AuthMethodAdapter`, plus a one-time legacy migration on load.

**Tech Stack:** Java 21 records + sealed types, Gson custom adapters, IntelliJ `DialogWrapper`, Apache MINA SSHD (unchanged), JUnit 5.

**Reference spec:** `docs/specs/2026-04-11-ssh-inline-auth-design.md`

---

## File Structure

**New files:**
- `plugins/ssh/src/com/conch/ssh/model/SshAuth.java` — sealed interface with `permits VaultAuth, PromptPasswordAuth, KeyFileAuth`.
- `plugins/ssh/src/com/conch/ssh/model/VaultAuth.java` — record variant pointing at a saved vault credential id (nullable).
- `plugins/ssh/src/com/conch/ssh/model/PromptPasswordAuth.java` — empty record marker for "prompt every connect".
- `plugins/ssh/src/com/conch/ssh/model/KeyFileAuth.java` — record carrying a non-null `keyFilePath`.
- `plugins/ssh/src/com/conch/ssh/ui/InlineCredentialPromptDialog.java` — thin `DialogWrapper` with `promptPassword` and `promptPassphrase` static factories, both returning a `char[]` on OK.
- `plugins/ssh/test/com/conch/ssh/persistence/SshAuthJsonTest.java` — round-trip every `SshAuth` variant + legacy-shape assertion.
- `plugins/ssh/test/com/conch/ssh/persistence/HostsFileLegacyMigrationTest.java` — asserts a hand-written legacy JSON file loads as `VaultAuth`, and that re-saving produces the new shape with no legacy field.

**Modified files:**
- `plugins/ssh/src/com/conch/ssh/model/SshHost.java` — record loses `credentialId`, gains `SshAuth auth`. `create` / `withEdited` / `withAuth` updated. `withCredentialId` deleted.
- `plugins/ssh/src/com/conch/ssh/persistence/SshGson.java` — registers `SshAuthSerializer` + `SshAuthDeserializer`.
- `plugins/ssh/src/com/conch/ssh/persistence/HostsFile.java` — pre-deserialize legacy migration on each host entry.
- `plugins/ssh/src/com/conch/ssh/credentials/SshCredentialResolver.java` — `resolve(SshHost)` becomes `resolve(UUID credentialId, String fallbackUsername)`. Standalone-key username fallback moves to the new signature.
- `plugins/ssh/src/com/conch/ssh/provider/SshSessionProvider.java` — `createSession` dispatches on `host.auth()`, `connectWithRetry` takes a `Retrier` instead of the raw picker.
- `plugins/ssh/src/com/conch/ssh/ui/HostEditDialog.java` — replaces the single credential combo with a radio group (Vault / Password / SSH key file) and contextual fields per radio.
- `plugins/ssh/src/com/conch/ssh/toolwindow/HostsToolWindow.java` — `duplicateSelected` uses `selected.auth()` in place of `selected.credentialId()`.
- `plugins/ssh/test/com/conch/ssh/model/SshHostTest.java` — asserts against `auth()` and the new `withAuth` / `withEdited` signatures.
- `plugins/ssh/test/com/conch/ssh/persistence/HostsFileTest.java` — asserts against `auth()` instead of `credentialId()`.
- `plugins/ssh/test/com/conch/ssh/credentials/SshCredentialResolverTest.java` — rewritten against the new `resolve(UUID, String)` signature.

**Unchanged files (confirmed by reading the spec scope line):**
- `plugins/ssh/src/com/conch/ssh/client/*` (except the resolver above): no changes
- `plugins/ssh/src/com/conch/ssh/credentials/SshCredentialPicker.java`: no changes
- `plugins/ssh/src/com/conch/ssh/palette/HostsPaletteContributor.java`: reads only label/host/port/username, doesn't touch credentials
- `plugins/ssh/src/com/conch/ssh/actions/*`: route on label/host, no credential fields read
- `plugins/ssh/resources/META-INF/plugin.xml`: no registration changes

---

## Build & test commands

Project uses Bazel via a Makefile wrapper. From `/Users/dustin/projects/conch_workbench`:

```bash
# Compile the whole conch product (catches cross-plugin breakage fast):
make conch-build

# Run the SSH plugin's JUnit 5 suite (74 tests as of plan authoring):
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //conch/plugins/ssh:ssh_test_runner

# Run the ssh lib build in isolation (faster feedback than the full product):
cd /Users/dustin/projects/intellij-community && bash bazel.cmd build //conch/plugins/ssh:ssh
```

Cited throughout the plan as `make conch-build` and `ssh_test_runner`.

---

## Task 1: `SshAuth` sealed type + Gson adapter

This task is purely additive — no existing file changes, no API breakage. The adapter is exercised by unit tests directly; production code starts using `SshAuth` in Task 2.

**Files:**
- Create: `plugins/ssh/src/com/conch/ssh/model/SshAuth.java`
- Modify: `plugins/ssh/src/com/conch/ssh/persistence/SshGson.java`
- Create: `plugins/ssh/test/com/conch/ssh/persistence/SshAuthJsonTest.java`

- [ ] **Step 1: Write the failing test**

Create `plugins/ssh/test/com/conch/ssh/persistence/SshAuthJsonTest.java`:

```java
package com.conch.ssh.persistence;

import com.conch.ssh.model.KeyFileAuth;
import com.conch.ssh.model.PromptPasswordAuth;
import com.conch.ssh.model.SshAuth;
import com.conch.ssh.model.VaultAuth;
import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SshAuthJsonTest {

    @Test
    void vaultAuth_withId_roundTrip() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String json = SshGson.GSON.toJson(new VaultAuth(id), SshAuth.class);
        assertTrue(json.contains("\"type\": \"vault\""), json);
        assertTrue(json.contains("\"credentialId\": \"" + id + "\""), json);

        SshAuth parsed = SshGson.GSON.fromJson(json, SshAuth.class);
        assertInstanceOf(VaultAuth.class, parsed);
        assertEquals(id, ((VaultAuth) parsed).credentialId());
    }

    @Test
    void vaultAuth_withoutId_roundTrip() {
        String json = SshGson.GSON.toJson(new VaultAuth(null), SshAuth.class);
        assertTrue(json.contains("\"type\": \"vault\""));
        assertFalse(json.contains("credentialId"), json);

        SshAuth parsed = SshGson.GSON.fromJson(json, SshAuth.class);
        assertInstanceOf(VaultAuth.class, parsed);
        assertNull(((VaultAuth) parsed).credentialId());
    }

    @Test
    void promptPasswordAuth_roundTrip() {
        String json = SshGson.GSON.toJson(new PromptPasswordAuth(), SshAuth.class);
        assertTrue(json.contains("\"type\": \"prompt_password\""), json);

        SshAuth parsed = SshGson.GSON.fromJson(json, SshAuth.class);
        assertInstanceOf(PromptPasswordAuth.class, parsed);
    }

    @Test
    void keyFileAuth_roundTrip() {
        String json = SshGson.GSON.toJson(
            new KeyFileAuth("/home/alice/.ssh/id_ed25519"), SshAuth.class);
        assertTrue(json.contains("\"type\": \"key_file\""));
        assertTrue(json.contains("\"keyFilePath\": \"/home/alice/.ssh/id_ed25519\""));

        SshAuth parsed = SshGson.GSON.fromJson(json, SshAuth.class);
        assertInstanceOf(KeyFileAuth.class, parsed);
        assertEquals("/home/alice/.ssh/id_ed25519", ((KeyFileAuth) parsed).keyFilePath());
    }

    @Test
    void unknownType_throws() {
        String bad = "{ \"type\": \"bogus\" }";
        assertThrows(JsonParseException.class, () -> SshGson.GSON.fromJson(bad, SshAuth.class));
    }
}
```

- [ ] **Step 2: Run it to confirm it fails with "cannot find symbol" for the SshAuth types**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd build //conch/plugins/ssh:ssh_test_lib
```

Expected: compilation error `cannot find symbol: class SshAuth` / `class VaultAuth` — proves we haven't defined them yet.

- [ ] **Step 3: Create `SshAuth.java`**

```java
package com.conch.ssh.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * How an {@link SshHost} authenticates at connect time.
 *
 * <p>Three variants, each implementing this sealed interface:
 * <ul>
 *   <li>{@link VaultAuth} — the host points at a saved
 *       {@code CredentialProvider.CredentialDescriptor}. A null
 *       credential id means "no saved entry — run the vault picker at
 *       connect time" and keeps the existing
 *       "no credential" fallback in {@code HostEditDialog} working
 *       without a fourth variant.</li>
 *   <li>{@link PromptPasswordAuth} — the host never persists a password;
 *       the user types one into {@code InlineCredentialPromptDialog}
 *       every connect.</li>
 *   <li>{@link KeyFileAuth} — the host persists an SSH private key
 *       <em>path</em> (not the key, not the passphrase), same treatment
 *       {@code ~/.ssh/config}'s {@code IdentityFile} gets. Each connect
 *       reads the key from disk and prompts for an optional passphrase.</li>
 * </ul>
 *
 * <p>JSON: a single discriminator field {@code "type"} selects the
 * variant — {@code "vault"}, {@code "prompt_password"}, or
 * {@code "key_file"}. Serialization is handled by {@code SshGson}, not
 * by field reflection.
 */
public sealed interface SshAuth permits VaultAuth, PromptPasswordAuth, KeyFileAuth {
}
```

Now create `VaultAuth.java` in the same package:

```java
package com.conch.ssh.model;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Reference to a credential stored by a {@code CredentialProvider}
 * extension (the vault plugin, in practice).
 *
 * @param credentialId the saved credential's UUID, or {@code null} to
 *                     mean "no saved entry — run the vault picker at
 *                     connect time". The null case keeps
 *                     {@code HostEditDialog}'s "&lt;no credential&gt;"
 *                     combo option representable without a fourth
 *                     {@link SshAuth} variant.
 */
public record VaultAuth(@Nullable UUID credentialId) implements SshAuth {
}
```

Create `PromptPasswordAuth.java`:

```java
package com.conch.ssh.model;

/**
 * Prompt the user for a password at every connect. The host carries no
 * credential material — only the fact that this is the chosen mode.
 * {@code InlineCredentialPromptDialog.promptPassword} is the connect-time
 * entry point.
 */
public record PromptPasswordAuth() implements SshAuth {
}
```

Create `KeyFileAuth.java`:

```java
package com.conch.ssh.model;

import org.jetbrains.annotations.NotNull;

/**
 * Authenticate with a private key file on disk. The path is persisted
 * (it's not a secret — same treatment {@code ~/.ssh/config} gives
 * {@code IdentityFile}); the passphrase is prompted every connect via
 * {@code InlineCredentialPromptDialog.promptPassphrase} and never saved.
 *
 * @param keyFilePath absolute path to the private key. Validated by
 *                    {@code HostEditDialog.doValidate} at save time.
 */
public record KeyFileAuth(@NotNull String keyFilePath) implements SshAuth {
}
```

- [ ] **Step 4: Register the adapter in `SshGson.java`**

Open `plugins/ssh/src/com/conch/ssh/persistence/SshGson.java` and replace its contents with:

```java
package com.conch.ssh.persistence;

import com.conch.ssh.model.KeyFileAuth;
import com.conch.ssh.model.PromptPasswordAuth;
import com.conch.ssh.model.SshAuth;
import com.conch.ssh.model.VaultAuth;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Gson configuration for the SSH plugin.
 *
 * <p>Registers three custom adapters:
 * <ol>
 *   <li><b>Instant</b> — JDK 21 modules block default reflection into
 *       {@code java.time.Instant}, so we serialize as ISO-8601 strings.</li>
 *   <li><b>SshAuth serializer</b> — emits a discriminator-tagged object
 *       for the sealed type. Pattern mirrored from the vault plugin's
 *       {@code AuthMethodSerializer}.</li>
 *   <li><b>SshAuth deserializer</b> — dispatches on the {@code "type"}
 *       field to pick a record constructor.</li>
 * </ol>
 */
final class SshGson {

    static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(Instant.class, new InstantAdapter())
        .registerTypeAdapter(SshAuth.class, new SshAuthSerializer())
        .registerTypeAdapter(SshAuth.class, new SshAuthDeserializer())
        .create();

    private SshGson() {}

    private static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Instant.parse(in.nextString());
        }
    }

    private static final class SshAuthSerializer implements JsonSerializer<SshAuth> {
        @Override
        public JsonElement serialize(SshAuth src, java.lang.reflect.Type typeOfSrc,
                                     com.google.gson.JsonSerializationContext ctx) {
            JsonObject obj = new JsonObject();
            switch (src) {
                case VaultAuth v -> {
                    obj.addProperty("type", "vault");
                    if (v.credentialId() != null) {
                        obj.addProperty("credentialId", v.credentialId().toString());
                    }
                }
                case PromptPasswordAuth p -> obj.addProperty("type", "prompt_password");
                case KeyFileAuth k -> {
                    obj.addProperty("type", "key_file");
                    obj.addProperty("keyFilePath", k.keyFilePath());
                }
            }
            return obj;
        }
    }

    private static final class SshAuthDeserializer implements JsonDeserializer<SshAuth> {
        @Override
        public SshAuth deserialize(JsonElement json, java.lang.reflect.Type typeOfT,
                                   com.google.gson.JsonDeserializationContext ctx) {
            JsonObject obj = json.getAsJsonObject();
            String type = obj.get("type").getAsString();
            return switch (type) {
                case "vault" -> {
                    UUID id = null;
                    if (obj.has("credentialId") && !obj.get("credentialId").isJsonNull()) {
                        id = UUID.fromString(obj.get("credentialId").getAsString());
                    }
                    yield new VaultAuth(id);
                }
                case "prompt_password" -> new PromptPasswordAuth();
                case "key_file" -> new KeyFileAuth(obj.get("keyFilePath").getAsString());
                default -> throw new JsonParseException("unknown SshAuth type: " + type);
            };
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //conch/plugins/ssh:ssh_test_runner
```

Expected: all tests pass (the existing 74 + the 5 new `SshAuthJsonTest` tests = 79 total). If the legacy `SshHost`-dependent tests still use `credentialId`, they should still pass — we haven't changed `SshHost` yet.

- [ ] **Step 6: Commit**

```bash
cd /Users/dustin/projects/conch_workbench
git add plugins/ssh/src/com/conch/ssh/model/SshAuth.java \
        plugins/ssh/src/com/conch/ssh/model/VaultAuth.java \
        plugins/ssh/src/com/conch/ssh/model/PromptPasswordAuth.java \
        plugins/ssh/src/com/conch/ssh/model/KeyFileAuth.java \
        plugins/ssh/src/com/conch/ssh/persistence/SshGson.java \
        plugins/ssh/test/com/conch/ssh/persistence/SshAuthJsonTest.java
git commit -m "$(cat <<'EOF'
feat(ssh): introduce SshAuth sealed type + Gson adapter

Additive change — SshHost still uses credentialId. Next commit swaps the
host record over and migrates legacy JSON on load.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Swap `SshHost.credentialId` → `SshHost.auth`, migrate JSON

This is the load-bearing refactor. `SshHost` changes shape, and every direct consumer of `credentialId` has to move to `auth()` in the same commit (Java won't compile otherwise). The scope is bounded — I've read every caller; the list below is exhaustive.

**Files:**
- Modify: `plugins/ssh/src/com/conch/ssh/model/SshHost.java`
- Modify: `plugins/ssh/src/com/conch/ssh/persistence/HostsFile.java`
- Modify: `plugins/ssh/src/com/conch/ssh/credentials/SshCredentialResolver.java`
- Modify: `plugins/ssh/src/com/conch/ssh/provider/SshSessionProvider.java`
- Modify: `plugins/ssh/src/com/conch/ssh/ui/HostEditDialog.java`
- Modify: `plugins/ssh/src/com/conch/ssh/toolwindow/HostsToolWindow.java`
- Modify: `plugins/ssh/test/com/conch/ssh/model/SshHostTest.java`
- Modify: `plugins/ssh/test/com/conch/ssh/persistence/HostsFileTest.java`
- Modify: `plugins/ssh/test/com/conch/ssh/credentials/SshCredentialResolverTest.java`
- Create: `plugins/ssh/test/com/conch/ssh/persistence/HostsFileLegacyMigrationTest.java`

- [ ] **Step 1: Write the failing legacy-migration test**

Create `plugins/ssh/test/com/conch/ssh/persistence/HostsFileLegacyMigrationTest.java`:

```java
package com.conch.ssh.persistence;

import com.conch.ssh.model.SshHost;
import com.conch.ssh.model.VaultAuth;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HostsFileLegacyMigrationTest {

    @Test
    void legacyCredentialIdWithValue_migratesToVaultAuthWithId(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        UUID credId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Files.writeString(file, """
            {
              "version": 1,
              "hosts": [
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "label": "legacy",
                  "host": "example.com",
                  "port": 22,
                  "username": "alice",
                  "credentialId": "%s",
                  "createdAt": "2026-01-01T00:00:00Z",
                  "updatedAt": "2026-01-01T00:00:00Z"
                }
              ]
            }
            """.formatted(credId));

        List<SshHost> loaded = HostsFile.load(file);
        assertEquals(1, loaded.size());
        SshHost host = loaded.get(0);
        assertInstanceOf(VaultAuth.class, host.auth());
        assertEquals(credId, ((VaultAuth) host.auth()).credentialId());
    }

    @Test
    void legacyCredentialIdNull_migratesToVaultAuthWithNullId(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        Files.writeString(file, """
            {
              "version": 1,
              "hosts": [
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "label": "legacy",
                  "host": "example.com",
                  "port": 22,
                  "username": "alice",
                  "credentialId": null,
                  "createdAt": "2026-01-01T00:00:00Z",
                  "updatedAt": "2026-01-01T00:00:00Z"
                }
              ]
            }
            """);

        List<SshHost> loaded = HostsFile.load(file);
        assertEquals(1, loaded.size());
        assertInstanceOf(VaultAuth.class, loaded.get(0).auth());
        assertNull(((VaultAuth) loaded.get(0).auth()).credentialId());
    }

    @Test
    void saveAfterLoad_writesNewShapeAndDropsLegacyField(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ssh-hosts.json");
        UUID credId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Files.writeString(file, """
            {
              "version": 1,
              "hosts": [
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "label": "legacy",
                  "host": "example.com",
                  "port": 22,
                  "username": "alice",
                  "credentialId": "%s",
                  "createdAt": "2026-01-01T00:00:00Z",
                  "updatedAt": "2026-01-01T00:00:00Z"
                }
              ]
            }
            """.formatted(credId));

        List<SshHost> loaded = HostsFile.load(file);
        HostsFile.save(file, loaded);

        // Structural check: the host object must carry an "auth" object
        // and must NOT carry a top-level "credentialId" field (the
        // latter would be a regression where the record somehow
        // re-gained the legacy property).
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject hostObj = root.getAsJsonArray("hosts").get(0).getAsJsonObject();
        assertTrue(hostObj.has("auth"), "host object should carry 'auth' after save");
        assertFalse(hostObj.has("credentialId"),
            "legacy top-level credentialId should be dropped after save");

        JsonObject authObj = hostObj.getAsJsonObject("auth");
        assertEquals("vault", authObj.get("type").getAsString());
        assertEquals(credId.toString(), authObj.get("credentialId").getAsString());

        // Functional check: reloading the rewritten file still yields VaultAuth.
        List<SshHost> reloaded = HostsFile.load(file);
        assertInstanceOf(VaultAuth.class, reloaded.get(0).auth());
        assertEquals(credId, ((VaultAuth) reloaded.get(0).auth()).credentialId());
    }
}
```

- [ ] **Step 2: Run it to confirm the failure**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd build //conch/plugins/ssh:ssh_test_lib
```

Expected: compilation error on `host.auth()` — proves we haven't swapped the `SshHost` field yet.

- [ ] **Step 3: Rewrite `SshHost.java`**

Replace the file contents with:

```java
package com.conch.ssh.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A saved SSH host.
 *
 * <p>Hostnames and ports aren't secrets — they live in plaintext under
 * {@code ~/.config/conch/ssh-hosts.json}, the same treatment
 * {@code ~/.ssh/config} already gets. The {@link #auth} field carries
 * one of three variants:
 * <ul>
 *   <li>{@link VaultAuth} — points at a
 *       {@code CredentialProvider.CredentialDescriptor} in the vault; the
 *       actual secret is fetched at connect time and never cached outside
 *       the short-lived resolved-credential window.</li>
 *   <li>{@link PromptPasswordAuth} — no saved secret; prompt every
 *       connect.</li>
 *   <li>{@link KeyFileAuth} — a private-key path is saved; the passphrase
 *       (if any) is prompted every connect.</li>
 * </ul>
 *
 * @param id         stable UUID, survives renames
 * @param label      user-facing name ("prod-db-primary")
 * @param host       hostname or IP
 * @param port       SSH port, usually 22
 * @param username   default username for this host. When the referenced
 *                   credential is a standalone SSH key (kind
 *                   {@code SSH_KEY}) the resolved credential has no
 *                   username of its own, so the connector falls back to
 *                   this field.
 * @param auth       how the host authenticates — see {@link SshAuth}
 * @param createdAt  when the host entry was created
 * @param updatedAt  when the host entry was last edited
 */
public record SshHost(
    @NotNull UUID id,
    @NotNull String label,
    @NotNull String host,
    int port,
    @NotNull String username,
    @NotNull SshAuth auth,
    @NotNull Instant createdAt,
    @NotNull Instant updatedAt
) {
    /** Default SSH port — matches OpenSSH. */
    public static final int DEFAULT_PORT = 22;

    /** @return a copy of this host with a new label and a bumped {@code updatedAt}. */
    public SshHost withLabel(@NotNull String newLabel) {
        return new SshHost(id, newLabel, host, port, username, auth, createdAt, Instant.now());
    }

    /** @return a copy of this host with a new auth mode and a bumped {@code updatedAt}. */
    public SshHost withAuth(@NotNull SshAuth newAuth) {
        return new SshHost(id, label, host, port, username, newAuth, createdAt, Instant.now());
    }

    /** @return a copy with every editable field replaced. Used by the edit dialog's Save path. */
    public SshHost withEdited(
        @NotNull String newLabel,
        @NotNull String newHost,
        int newPort,
        @NotNull String newUsername,
        @NotNull SshAuth newAuth
    ) {
        return new SshHost(id, newLabel, newHost, newPort, newUsername, newAuth, createdAt, Instant.now());
    }

    /** Factory for brand-new hosts. */
    public static @NotNull SshHost create(
        @NotNull String label,
        @NotNull String host,
        int port,
        @NotNull String username,
        @NotNull SshAuth auth
    ) {
        Instant now = Instant.now();
        return new SshHost(UUID.randomUUID(), label, host, port, username, auth, now, now);
    }
}
```

- [ ] **Step 4: Rewrite `SshHostTest.java`**

Replace its contents with:

```java
package com.conch.ssh.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SshHostTest {

    @Test
    void create_populatesIdAndTimestamps() {
        SshHost host = SshHost.create("prod", "example.com", 22, "root", new VaultAuth(null));
        assertNotNull(host.id());
        assertEquals("prod", host.label());
        assertEquals("example.com", host.host());
        assertEquals(22, host.port());
        assertEquals("root", host.username());
        assertInstanceOf(VaultAuth.class, host.auth());
        assertNull(((VaultAuth) host.auth()).credentialId());
        assertNotNull(host.createdAt());
        assertEquals(host.createdAt(), host.updatedAt());
    }

    @Test
    void withLabel_preservesIdentityBumpsUpdatedAt() throws InterruptedException {
        SshHost original = SshHost.create("prod", "example.com", 22, "root", new VaultAuth(null));
        Thread.sleep(5);
        SshHost renamed = original.withLabel("production-db");
        assertEquals(original.id(), renamed.id());
        assertEquals(original.createdAt(), renamed.createdAt());
        assertEquals("production-db", renamed.label());
        assertTrue(renamed.updatedAt().isAfter(original.updatedAt()));
    }

    @Test
    void withAuth_replacesAuthPreservingIdentity() {
        SshHost original = SshHost.create("prod", "example.com", 22, "root", new VaultAuth(null));
        UUID credId = UUID.randomUUID();
        SshHost linked = original.withAuth(new VaultAuth(credId));
        assertEquals(original.id(), linked.id());
        assertInstanceOf(VaultAuth.class, linked.auth());
        assertEquals(credId, ((VaultAuth) linked.auth()).credentialId());
    }

    @Test
    void withAuth_promptPasswordVariant() {
        SshHost original = SshHost.create("prod", "example.com", 22, "root", new VaultAuth(null));
        SshHost flipped = original.withAuth(new PromptPasswordAuth());
        assertInstanceOf(PromptPasswordAuth.class, flipped.auth());
    }

    @Test
    void withAuth_keyFileVariant() {
        SshHost original = SshHost.create("prod", "example.com", 22, "root", new VaultAuth(null));
        SshHost flipped = original.withAuth(new KeyFileAuth("/tmp/key"));
        assertInstanceOf(KeyFileAuth.class, flipped.auth());
        assertEquals("/tmp/key", ((KeyFileAuth) flipped.auth()).keyFilePath());
    }

    @Test
    void withEdited_replacesAllEditableFields() {
        SshHost original = SshHost.create("prod", "example.com", 22, "root", new VaultAuth(null));
        UUID newCredentialId = UUID.randomUUID();
        SshHost edited = original.withEdited(
            "staging", "stage.example.com", 2222, "deploy", new VaultAuth(newCredentialId));

        assertEquals(original.id(), edited.id());
        assertEquals(original.createdAt(), edited.createdAt());
        assertEquals("staging", edited.label());
        assertEquals("stage.example.com", edited.host());
        assertEquals(2222, edited.port());
        assertEquals("deploy", edited.username());
        assertEquals(newCredentialId, ((VaultAuth) edited.auth()).credentialId());
    }

    @Test
    void defaultPortMatchesOpenSsh() {
        assertEquals(22, SshHost.DEFAULT_PORT);
    }
}
```

- [ ] **Step 5: Update `HostsFile.java` to perform legacy migration**

Replace its contents with:

```java
package com.conch.ssh.persistence;

import com.conch.ssh.model.SshHost;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Atomic JSON I/O for the SSH host list.
 *
 * <p>The file is wrapped in a versioned envelope so we have room to evolve
 * the format later without breaking existing users:
 * <pre>
 *   {
 *     "version": 1,
 *     "hosts": [ { "id": "…", "label": "…", ... } ]
 *   }
 * </pre>
 *
 * <p>Atomic write: serialize → write to temp → rename. A crash mid-write
 * can never corrupt the existing file.
 *
 * <p><b>Legacy migration.</b> Before {@link com.conch.ssh.model.SshAuth}
 * existed, host entries carried a bare top-level {@code credentialId}
 * field. On load, {@link #load(Path)} rewrites any such entry into the
 * new {@code "auth": {"type": "vault", ...}} shape before handing the
 * JSON to Gson. The next {@link #save} drops the legacy field for good.
 */
public final class HostsFile {

    public static final int VERSION = 1;

    private HostsFile() {}

    public static void save(@NotNull Path target, @NotNull List<SshHost> hosts) throws IOException {
        Envelope envelope = new Envelope(VERSION, new ArrayList<>(hosts));
        String json = SshGson.GSON.toJson(envelope);

        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Load the host list from disk. Returns an empty list if the file
     * doesn't exist. Returns an empty list (and ignores the error) if the
     * file is unreadable or structurally invalid, because a corrupted
     * host list is recoverable — the user just re-adds their hosts, they
     * lose nothing secret.
     */
    public static @NotNull List<SshHost> load(@NotNull Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            return Collections.emptyList();
        }
        String raw = Files.readString(source);
        try {
            JsonElement rootEl = JsonParser.parseString(raw);
            if (rootEl == null || !rootEl.isJsonObject()) return Collections.emptyList();
            JsonObject root = rootEl.getAsJsonObject();
            if (!root.has("hosts") || !root.get("hosts").isJsonArray()) {
                return Collections.emptyList();
            }
            JsonArray hostsArray = root.getAsJsonArray("hosts");
            for (JsonElement element : hostsArray) {
                if (element.isJsonObject()) migrateLegacyHostEntry(element.getAsJsonObject());
            }
            Envelope envelope = SshGson.GSON.fromJson(root, Envelope.class);
            if (envelope == null || envelope.hosts == null) {
                return Collections.emptyList();
            }
            return envelope.hosts;
        } catch (Exception malformed) {
            return Collections.emptyList();
        }
    }

    /**
     * In-place rewrite of a single host JSON object: if it has a bare
     * top-level {@code credentialId} field (the pre-SshAuth shape) and no
     * {@code auth} field, synthesize the new
     * {@code "auth": {"type": "vault", ...}} sub-object and strip the
     * legacy field so Gson sees only the current schema.
     */
    private static void migrateLegacyHostEntry(@NotNull JsonObject hostObj) {
        if (hostObj.has("auth")) return;                    // already new-shape
        if (!hostObj.has("credentialId")) return;           // nothing to migrate

        JsonElement legacy = hostObj.remove("credentialId");
        JsonObject auth = new JsonObject();
        auth.addProperty("type", "vault");
        if (legacy != null && !legacy.isJsonNull()) {
            auth.addProperty("credentialId", legacy.getAsString());
        }
        hostObj.add("auth", auth);
    }

    public static boolean exists(@NotNull Path target) {
        return Files.isRegularFile(target);
    }

    /**
     * Gson-friendly envelope type. Public fields so Gson can read/write
     * directly without reflection into private members — same pattern as
     * ConchTerminalConfig.State and the vault settings.
     */
    static final class Envelope {
        int version;
        List<SshHost> hosts;

        Envelope() {}

        Envelope(int version, List<SshHost> hosts) {
            this.version = version;
            this.hosts = hosts;
        }
    }
}
```

- [ ] **Step 6: Update `HostsFileTest.java`**

Replace the test cases that reference `credentialId` (and the `SshHost.create` calls) with `auth`-based equivalents. Apply these replacements:

- Replace every `SshHost.create(label, host, port, user, null)` with `SshHost.create(label, host, port, user, new VaultAuth(null))`
- Replace every `SshHost.create(label, host, port, user, credId)` with `SshHost.create(label, host, port, user, new VaultAuth(credId))`
- In `saveSingleHost_thenLoad_preservesFields`:
  - Replace `assertEquals(credId, restored.credentialId());` with `assertEquals(credId, ((VaultAuth) restored.auth()).credentialId());`
- In `save_nullCredentialIdRoundTrips`:
  - Rename to `save_vaultAuthWithNullId_roundTrips`
  - Replace `assertNull(loaded.get(0).credentialId());` with `assertNull(((VaultAuth) loaded.get(0).auth()).credentialId());`
- Add an import: `import com.conch.ssh.model.VaultAuth;`

- [ ] **Step 7: Update `SshCredentialResolver.java` signature**

Replace the file contents with:

```java
package com.conch.ssh.credentials;

import com.conch.sdk.CredentialProvider;
import com.conch.sdk.CredentialProvider.Credential;
import com.conch.ssh.client.SshResolvedCredential;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Maps a vault credential id to a short-lived {@link SshResolvedCredential}
 * by walking the registered {@link CredentialProvider} extensions.
 *
 * <p>The caller decides the policy (which credential id to look up, what
 * to do on miss) — this class is a pure dispatcher. When the returned
 * credential is a standalone key that has no username of its own,
 * {@code fallbackUsername} is substituted.
 *
 * <p>Two constructors: a zero-arg one that discovers providers via
 * the IntelliJ extension area (production path), and one that accepts an
 * explicit list (tests).
 */
public final class SshCredentialResolver {

    private static final ExtensionPointName<CredentialProvider> EP_NAME =
        ExtensionPointName.create("com.conch.core.credentialProvider");

    private final ProviderLookup providerLookup;

    /** Production constructor — resolves providers via the IntelliJ EP at call time. */
    public SshCredentialResolver() {
        this.providerLookup = () -> {
            if (ApplicationManager.getApplication() == null) {
                return List.of();
            }
            return EP_NAME.getExtensionList();
        };
    }

    /** Test constructor — uses an explicit provider list. */
    public SshCredentialResolver(@NotNull List<? extends CredentialProvider> providers) {
        this.providerLookup = () -> List.copyOf(providers);
    }

    /**
     * Resolve {@code credentialId} through any registered provider and
     * copy the result into a fresh {@link SshResolvedCredential} the
     * caller owns and must {@code close()}.
     *
     * @param credentialId     the saved credential's id
     * @param fallbackUsername used when the resolved credential is a
     *                         standalone key with no username of its own
     * @return the resolved credential, or {@code null} if no provider
     *         knows this id (locked vault, deleted entry)
     */
    public @Nullable SshResolvedCredential resolve(
        @NotNull UUID credentialId,
        @NotNull String fallbackUsername
    ) {
        Credential sdkCredential = null;
        for (CredentialProvider provider : providerLookup.get()) {
            if (!provider.isAvailable()) continue;
            Credential candidate = provider.getCredential(credentialId);
            if (candidate != null) {
                sdkCredential = candidate;
                break;
            }
        }
        if (sdkCredential == null) return null;

        try {
            return convert(sdkCredential, fallbackUsername);
        } finally {
            sdkCredential.destroy();
        }
    }

    /**
     * Convert an SDK-level {@link Credential} into an
     * {@link SshResolvedCredential}, copying char-array fields so the
     * caller can freely call {@link Credential#destroy()} afterwards.
     *
     * <p>Visible for {@link SshCredentialPicker} to reuse.
     */
    static @Nullable SshResolvedCredential convert(
        @NotNull Credential sdkCredential,
        @NotNull String fallbackUsername
    ) {
        String username = sdkCredential.username();
        if (username == null || username.isEmpty()) {
            username = fallbackUsername;
        }

        return switch (sdkCredential.authMethod()) {
            case PASSWORD -> {
                char[] pw = sdkCredential.password();
                if (pw == null) yield null;
                yield SshResolvedCredential.password(username, pw.clone());
            }
            case KEY -> {
                if (sdkCredential.keyPath() == null) yield null;
                char[] passphrase = sdkCredential.keyPassphrase();
                yield SshResolvedCredential.key(
                    username,
                    Path.of(sdkCredential.keyPath()),
                    passphrase == null ? null : passphrase.clone()
                );
            }
            case KEY_AND_PASSWORD -> {
                if (sdkCredential.keyPath() == null) yield null;
                char[] pw = sdkCredential.password();
                if (pw == null) yield null;
                char[] passphrase = sdkCredential.keyPassphrase();
                yield SshResolvedCredential.keyAndPassword(
                    username,
                    Path.of(sdkCredential.keyPath()),
                    passphrase == null ? null : passphrase.clone(),
                    pw.clone()
                );
            }
        };
    }

    @FunctionalInterface
    private interface ProviderLookup {
        @NotNull List<? extends CredentialProvider> get();
    }
}
```

- [ ] **Step 8: Update `SshCredentialResolverTest.java`**

The tests still cover the same behavior (vault lookup + username fallback), they just call the new signature. Replace the file contents with:

```java
package com.conch.ssh.credentials;

import com.conch.sdk.CredentialProvider;
import com.conch.ssh.client.SshResolvedCredential;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SshCredentialResolverTest {

    @Test
    void resolve_noProviderKnowsCredential_returnsNull() {
        UUID credentialId = UUID.randomUUID();
        SshCredentialResolver resolver = new SshCredentialResolver(List.of(new FakeProvider()));
        assertNull(resolver.resolve(credentialId, "u"));
    }

    @Test
    void resolve_passwordCredential_populatesResolved() {
        UUID credentialId = UUID.randomUUID();

        FakeProvider provider = new FakeProvider();
        provider.add(new CredentialProvider.Credential(
            credentialId,
            "Prod DB",
            "dbadmin",
            CredentialProvider.AuthMethod.PASSWORD,
            "hunter2".toCharArray(),
            null,
            null));

        try (SshResolvedCredential cred =
                 new SshCredentialResolver(List.of(provider)).resolve(credentialId, "u")) {
            assertNotNull(cred);
            assertEquals(SshResolvedCredential.Mode.PASSWORD, cred.mode());
            assertEquals("dbadmin", cred.username());
            assertArrayEquals("hunter2".toCharArray(), cred.password());
        }
    }

    @Test
    void resolve_sdkCredentialIsDestroyedAfterCopy() {
        UUID credentialId = UUID.randomUUID();

        char[] pw = "hunter2".toCharArray();
        CredentialProvider.Credential sdkCred = new CredentialProvider.Credential(
            credentialId, "Prod", "admin", CredentialProvider.AuthMethod.PASSWORD,
            pw, null, null);

        FakeProvider provider = new FakeProvider();
        provider.add(sdkCred);

        try (SshResolvedCredential cred =
                 new SshCredentialResolver(List.of(provider)).resolve(credentialId, "u")) {
            assertNotNull(cred);
            assertArrayEquals(new char[]{0, 0, 0, 0, 0, 0, 0}, pw);
            assertArrayEquals("hunter2".toCharArray(), cred.password());
        }
    }

    @Test
    void resolve_keyCredential_populatesResolved() {
        UUID credentialId = UUID.randomUUID();

        FakeProvider provider = new FakeProvider();
        provider.add(new CredentialProvider.Credential(
            credentialId,
            "GitHub",
            "git",
            CredentialProvider.AuthMethod.KEY,
            null,
            "/home/me/.ssh/id_ed25519",
            "keypass".toCharArray()));

        try (SshResolvedCredential cred =
                 new SshCredentialResolver(List.of(provider)).resolve(credentialId, "u")) {
            assertNotNull(cred);
            assertEquals(SshResolvedCredential.Mode.KEY, cred.mode());
            assertEquals("git", cred.username());
            assertEquals("/home/me/.ssh/id_ed25519", cred.keyPath().toString());
            assertArrayEquals("keypass".toCharArray(), cred.keyPassphrase());
        }
    }

    @Test
    void resolve_keyAndPasswordCredential_populatesResolved() {
        UUID credentialId = UUID.randomUUID();

        FakeProvider provider = new FakeProvider();
        provider.add(new CredentialProvider.Credential(
            credentialId,
            "Bastion",
            "ops",
            CredentialProvider.AuthMethod.KEY_AND_PASSWORD,
            "server-pw".toCharArray(),
            "/keys/bastion",
            "key-pp".toCharArray()));

        try (SshResolvedCredential cred =
                 new SshCredentialResolver(List.of(provider)).resolve(credentialId, "u")) {
            assertNotNull(cred);
            assertEquals(SshResolvedCredential.Mode.KEY_AND_PASSWORD, cred.mode());
            assertEquals("ops", cred.username());
            assertEquals("/keys/bastion", cred.keyPath().toString());
            assertArrayEquals("server-pw".toCharArray(), cred.password());
            assertArrayEquals("key-pp".toCharArray(), cred.keyPassphrase());
        }
    }

    @Test
    void resolve_standaloneKey_injectsFallbackUsername() {
        UUID credentialId = UUID.randomUUID();

        FakeProvider provider = new FakeProvider();
        provider.add(new CredentialProvider.Credential(
            credentialId,
            "Work laptop key",
            null,  // standalone key, no username
            CredentialProvider.AuthMethod.KEY,
            null,
            "/keys/work",
            null));

        try (SshResolvedCredential cred =
                 new SshCredentialResolver(List.of(provider)).resolve(credentialId, "fallback-user")) {
            assertNotNull(cred);
            assertEquals("fallback-user", cred.username());
            assertEquals(SshResolvedCredential.Mode.KEY, cred.mode());
            assertEquals("/keys/work", cred.keyPath().toString());
        }
    }

    @Test
    void resolve_multipleProviders_firstHitWins() {
        UUID credentialId = UUID.randomUUID();

        FakeProvider first = new FakeProvider();
        first.add(new CredentialProvider.Credential(
            credentialId, "A", "a-user", CredentialProvider.AuthMethod.PASSWORD,
            "a-pw".toCharArray(), null, null));

        FakeProvider second = new FakeProvider();
        second.add(new CredentialProvider.Credential(
            credentialId, "B", "b-user", CredentialProvider.AuthMethod.PASSWORD,
            "b-pw".toCharArray(), null, null));

        try (SshResolvedCredential cred =
                 new SshCredentialResolver(List.of(first, second)).resolve(credentialId, "u")) {
            assertNotNull(cred);
            assertEquals("a-user", cred.username());
        }
    }

    @Test
    void resolve_unavailableProviderIsSkipped() {
        UUID credentialId = UUID.randomUUID();

        FakeProvider locked = new FakeProvider();
        locked.available = false;
        locked.add(new CredentialProvider.Credential(
            credentialId, "A", "locked-user", CredentialProvider.AuthMethod.PASSWORD,
            "pw".toCharArray(), null, null));

        FakeProvider unlocked = new FakeProvider();
        unlocked.add(new CredentialProvider.Credential(
            credentialId, "B", "unlocked-user", CredentialProvider.AuthMethod.PASSWORD,
            "pw".toCharArray(), null, null));

        try (SshResolvedCredential cred =
                 new SshCredentialResolver(List.of(locked, unlocked)).resolve(credentialId, "u")) {
            assertNotNull(cred);
            assertEquals("unlocked-user", cred.username());
        }
    }

    // -- fake provider --------------------------------------------------------

    private static final class FakeProvider implements CredentialProvider {
        private final List<Credential> store = new ArrayList<>();
        boolean available = true;

        void add(Credential credential) {
            store.add(credential);
        }

        @Override
        public @NotNull String getDisplayName() {
            return "Fake Provider";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public @NotNull List<CredentialDescriptor> listCredentials() {
            return Collections.emptyList();
        }

        @Override
        public @Nullable Credential getCredential(@NotNull UUID credentialId) {
            for (Credential c : store) {
                if (c.accountId().equals(credentialId)) return c;
            }
            return null;
        }

        @Override
        public @Nullable Credential promptForCredential() {
            return null;
        }
    }
}
```

- [ ] **Step 9: Update `SshSessionProvider.java` to use the new signature (vault-only dispatch for now)**

The full auth-variant dispatch lands in Task 4 — this step just keeps the build green. Find the current block in `createSession`:

```java
SshCredentialResolver resolver = new SshCredentialResolver();
SshCredentialPicker picker = new SshCredentialPicker();

// Step 1: try the saved credential.
SshResolvedCredential credential = resolver.resolve(host);

// Step 2: fall back to the picker if the host has no saved
// credential or the saved one can no longer be resolved.
if (credential == null) {
    credential = picker.pick(host);
}
```

Replace with:

```java
SshCredentialResolver resolver = new SshCredentialResolver();
SshCredentialPicker picker = new SshCredentialPicker();

// Phase 5: only VaultAuth is wired here. PromptPasswordAuth and
// KeyFileAuth are handled in a follow-up task — for now they fall
// through to the picker, which is a legitimate behavior (the user
// still gets prompted, just via the full vault picker instead of the
// inline dialog).
SshResolvedCredential credential = null;
if (host.auth() instanceof com.conch.ssh.model.VaultAuth vaultAuth
    && vaultAuth.credentialId() != null) {
    credential = resolver.resolve(vaultAuth.credentialId(), host.username());
}
if (credential == null) {
    credential = picker.pick(host);
}
```

- [ ] **Step 10: Update `HostEditDialog.java` to build a `VaultAuth` (still vault-only)**

Find the `doOKAction` method:

```java
@Override
protected void doOKAction() {
    String label = labelField.getText().trim();
    String host = hostField.getText().trim();
    int port = (Integer) portSpinner.getValue();
    String username = usernameField.getText().trim();

    CredentialEntry selected = (CredentialEntry) credentialCombo.getSelectedItem();
    UUID credentialId = selected == null ? null : selected.id();

    if (existing == null) {
        result = SshHost.create(label, host, port, username, credentialId);
    } else {
        result = existing.withEdited(label, host, port, username, credentialId);
    }

    super.doOKAction();
}
```

Replace with:

```java
@Override
protected void doOKAction() {
    String label = labelField.getText().trim();
    String host = hostField.getText().trim();
    int port = (Integer) portSpinner.getValue();
    String username = usernameField.getText().trim();

    CredentialEntry selected = (CredentialEntry) credentialCombo.getSelectedItem();
    UUID credentialId = selected == null ? null : selected.id();
    SshAuth auth = new VaultAuth(credentialId);

    if (existing == null) {
        result = SshHost.create(label, host, port, username, auth);
    } else {
        result = existing.withEdited(label, host, port, username, auth);
    }

    super.doOKAction();
}
```

Add these imports at the top of the file (next to the existing `com.conch.ssh.model.SshHost` import):

```java
import com.conch.ssh.model.SshAuth;
import com.conch.ssh.model.VaultAuth;
```

Also update `populateFromExisting` — find the block:

```java
UUID savedId = existing.credentialId();
if (savedId == null) {
    credentialCombo.setSelectedItem(CredentialEntry.NONE);
    return;
}
```

Replace with:

```java
UUID savedId = existing.auth() instanceof VaultAuth vaultAuth
    ? vaultAuth.credentialId()
    : null;
if (savedId == null) {
    credentialCombo.setSelectedItem(CredentialEntry.NONE);
    return;
}
```

- [ ] **Step 11: Update `HostsToolWindow.java` — `duplicateSelected`**

Find the method:

```java
private void duplicateSelected() {
    SshHost selected = list.getSelectedValue();
    if (selected == null) return;
    SshHost copy = SshHost.create(
        selected.label() + " (copy)",
        selected.host(),
        selected.port(),
        selected.username(),
        selected.credentialId()
    );
    store.addHost(copy);
    saveAndRefresh();
}
```

Replace with:

```java
private void duplicateSelected() {
    SshHost selected = list.getSelectedValue();
    if (selected == null) return;
    SshHost copy = SshHost.create(
        selected.label() + " (copy)",
        selected.host(),
        selected.port(),
        selected.username(),
        selected.auth()
    );
    store.addHost(copy);
    saveAndRefresh();
}
```

- [ ] **Step 12: Run the full build to verify every caller compiles**

```bash
cd /Users/dustin/projects/conch_workbench && make conch-build
```

Expected: `Build completed successfully`. If any other file still references `credentialId`, the compiler will name it — fix by replacing that read with `auth()` / `instanceof VaultAuth` as appropriate.

- [ ] **Step 13: Run the test suite**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //conch/plugins/ssh:ssh_test_runner
```

Expected: all tests pass. The three new migration tests now exercise the legacy path.

- [ ] **Step 14: Commit**

```bash
cd /Users/dustin/projects/conch_workbench
git add plugins/ssh/src/com/conch/ssh/model/SshHost.java \
        plugins/ssh/src/com/conch/ssh/persistence/HostsFile.java \
        plugins/ssh/src/com/conch/ssh/credentials/SshCredentialResolver.java \
        plugins/ssh/src/com/conch/ssh/provider/SshSessionProvider.java \
        plugins/ssh/src/com/conch/ssh/ui/HostEditDialog.java \
        plugins/ssh/src/com/conch/ssh/toolwindow/HostsToolWindow.java \
        plugins/ssh/test/com/conch/ssh/model/SshHostTest.java \
        plugins/ssh/test/com/conch/ssh/persistence/HostsFileTest.java \
        plugins/ssh/test/com/conch/ssh/persistence/HostsFileLegacyMigrationTest.java \
        plugins/ssh/test/com/conch/ssh/credentials/SshCredentialResolverTest.java
git commit -m "$(cat <<'EOF'
refactor(ssh): SshHost.credentialId → SshHost.auth (SshAuth variant)

Swap the nullable UUID for a non-null SshAuth field, and migrate
legacy JSON files transparently on load. HostEditDialog still only
knows how to produce VaultAuth — PromptPasswordAuth and KeyFileAuth
get wired in the next commits.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `InlineCredentialPromptDialog`

Self-contained new file. No consumer yet — Task 4 wires it into the session provider.

**Files:**
- Create: `plugins/ssh/src/com/conch/ssh/ui/InlineCredentialPromptDialog.java`

- [ ] **Step 1: Create the dialog**

```java
package com.conch.ssh.ui;

import com.conch.ssh.model.SshHost;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Small modal that pops during connect when the host is configured for
 * {@code PromptPasswordAuth} or {@code KeyFileAuth}. One
 * {@link JPasswordField}, a contextual headline, and — for the
 * passphrase variant — a "leave blank if unencrypted" hint.
 *
 * <p>Returns a freshly-allocated {@code char[]} on OK (caller owns and
 * is expected to zero it after use); {@code null} on cancel. The
 * session provider already runs on the EDT before handing off to
 * {@code Task.Modal}, so this dialog can be shown synchronously from
 * {@code createSession}.
 */
public final class InlineCredentialPromptDialog extends DialogWrapper {

    private final String headline;
    private final @Nullable String hint;
    private final JPasswordField field = new JPasswordField(24);

    private char @Nullable [] result;

    private InlineCredentialPromptDialog(@Nullable Project project,
                                          @NotNull String title,
                                          @NotNull String headline,
                                          @Nullable String hint) {
        super(project, true);
        this.headline = headline;
        this.hint = hint;
        setTitle(title);
        setOKButtonText("Connect");
        init();
    }

    /** Prompt for a password. Returns zeroed-free chars on OK or null on cancel. */
    public static char @Nullable [] promptPassword(@Nullable Project project, @NotNull SshHost host) {
        InlineCredentialPromptDialog dlg = new InlineCredentialPromptDialog(
            project,
            "SSH Password",
            "Password for " + host.username() + "@" + host.host() + ":" + host.port(),
            null);
        return dlg.showAndGet() ? dlg.result : null;
    }

    /**
     * Prompt for a key passphrase. Returns chars on OK, {@code null} on cancel.
     * Empty input is still returned as an empty {@code char[]} — callers should
     * treat zero-length as "no passphrase" when handing to MINA.
     */
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

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(12));
        panel.setPreferredSize(new Dimension(460, hint == null ? 100 : 130));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;

        JLabel headlineLabel = new JLabel(headline);
        headlineLabel.setFont(headlineLabel.getFont().deriveFont(Font.BOLD));
        panel.add(headlineLabel, c);

        c.gridy++;
        c.gridwidth = 1;
        c.weightx = 0;
        panel.add(new JLabel("Secret:"), c);

        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);

        if (hint != null) {
            c.gridx = 0;
            c.gridy++;
            c.gridwidth = 2;
            c.weightx = 1;
            JLabel hintLabel = new JLabel(hint);
            hintLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            panel.add(hintLabel, c);
        }

        return panel;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return field;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        return null;  // empty input is legal (unencrypted key case)
    }

    @Override
    protected void doOKAction() {
        result = field.getPassword();  // fresh char[] allocation — caller owns it
        super.doOKAction();
    }
}
```

- [ ] **Step 2: Verify the file compiles**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd build //conch/plugins/ssh:ssh
```

Expected: build succeeds.

- [ ] **Step 3: Commit**

```bash
cd /Users/dustin/projects/conch_workbench
git add plugins/ssh/src/com/conch/ssh/ui/InlineCredentialPromptDialog.java
git commit -m "$(cat <<'EOF'
feat(ssh): add InlineCredentialPromptDialog for prompt-at-connect auth

Single-field DialogWrapper used by the upcoming auth dispatch to ask
the user for a password or a key passphrase on each connect. No
consumer yet — wired into SshSessionProvider next.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Dispatch on `SshAuth` in `SshSessionProvider` + `Retrier`

Wires the new dialog into the connect flow. Vault path still goes through the existing resolver/picker; new variants go through the inline dialog.

**Files:**
- Modify: `plugins/ssh/src/com/conch/ssh/provider/SshSessionProvider.java`

- [ ] **Step 1: Rewrite `SshSessionProvider.java`**

Replace the file's contents with:

```java
package com.conch.ssh.provider;

import com.conch.sdk.TerminalSessionProvider;
import com.conch.ssh.client.ConchServerKeyVerifier;
import com.conch.ssh.client.ConchSshClient;
import com.conch.ssh.client.SshConnectException;
import com.conch.ssh.client.SshConnection;
import com.conch.ssh.client.SshResolvedCredential;
import com.conch.ssh.credentials.SshCredentialPicker;
import com.conch.ssh.credentials.SshCredentialResolver;
import com.conch.ssh.model.KeyFileAuth;
import com.conch.ssh.model.PromptPasswordAuth;
import com.conch.ssh.model.SshAuth;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.model.VaultAuth;
import com.conch.ssh.persistence.KnownHostsFile;
import com.conch.ssh.ui.InlineCredentialPromptDialog;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.ui.Messages;
import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.nio.file.Path;

/**
 * {@link TerminalSessionProvider} that opens SSH sessions via
 * {@link ConchSshClient}, renders them through the existing
 * {@code ConchTerminalEditor}, and dispatches on {@link SshAuth} to
 * choose how the credential for this host is sourced.
 *
 * <p>Flow on {@link #createSession(SessionContext)}:
 * <ol>
 *   <li>The context must be an {@link SshSessionContext} carrying an
 *       {@link SshHost}.</li>
 *   <li>Dispatch on {@code host.auth()}:
 *       <ul>
 *         <li>{@link VaultAuth}({@code id} != null) → look up in the
 *             vault. If that fails, fall through to the vault picker.</li>
 *         <li>{@link VaultAuth}({@code id} == null) → run the vault
 *             picker directly.</li>
 *         <li>{@link PromptPasswordAuth} →
 *             {@link InlineCredentialPromptDialog#promptPassword}.</li>
 *         <li>{@link KeyFileAuth} →
 *             {@link InlineCredentialPromptDialog#promptPassphrase} +
 *             {@link SshResolvedCredential#key}.</li>
 *       </ul>
 *   </li>
 *   <li>Connect runs inside a {@link Task.Modal} so the EDT never blocks
 *       during MINA's ~seconds-long handshake.</li>
 *   <li>On {@link SshConnectException.Kind#AUTH_FAILED}, the provider
 *       re-runs the same auth-variant's source once via the supplied
 *       {@link Retrier}, giving the user a chance to correct a stale
 *       credential without closing the tab.</li>
 *   <li>On {@link SshConnectException.Kind#HOST_KEY_REJECTED}, a hard
 *       MITM-warning dialog fires and there is no "accept anyway" path.</li>
 * </ol>
 *
 * <p>Host-key verification is handled by {@link ConchServerKeyVerifier}
 * which consults {@link KnownHostsFile} and prompts the user on first
 * contact.
 */
public final class SshSessionProvider implements TerminalSessionProvider {

    public static final String ID = "com.conch.ssh";

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "SSH";
    }

    @Override
    public @Nullable Icon getIcon() {
        return AllIcons.Webreferences.Server;
    }

    @Override
    public boolean canQuickOpen() {
        return false;
    }

    @Override
    public @Nullable TtyConnector createSession(@NotNull SessionContext context) {
        if (!(context instanceof SshSessionContext sshContext)) {
            Messages.showErrorDialog(
                "SSH sessions require an SshSessionContext with a selected host.",
                "No Host Selected");
            return null;
        }
        SshHost host = sshContext.host();

        AuthSource source = authSourceFor(host);
        SshResolvedCredential initial = source.fetch(host);
        if (initial == null) return null;

        return connectWithRetry(host, initial, source);
    }

    // -- dispatch -------------------------------------------------------------

    /**
     * Encapsulates "how to produce an {@link SshResolvedCredential} for
     * this host", including the retry case after an auth failure. The
     * three {@link SshAuth} variants map to three different sources —
     * never cross-contaminate (e.g. a PromptPassword host should never
     * trigger a vault picker on retry).
     */
    private interface AuthSource {
        @Nullable SshResolvedCredential fetch(@NotNull SshHost host);
    }

    private @NotNull AuthSource authSourceFor(@NotNull SshHost host) {
        SshCredentialResolver resolver = new SshCredentialResolver();
        SshCredentialPicker picker = new SshCredentialPicker();

        return switch (host.auth()) {
            case VaultAuth v -> vaultSource(resolver, picker, v);
            case PromptPasswordAuth p -> h -> promptPasswordSource(h);
            case KeyFileAuth k -> h -> keyFileSource(h, k);
        };
    }

    private static @NotNull AuthSource vaultSource(
        @NotNull SshCredentialResolver resolver,
        @NotNull SshCredentialPicker picker,
        @NotNull VaultAuth vault
    ) {
        return host -> {
            if (vault.credentialId() != null) {
                SshResolvedCredential saved = resolver.resolve(vault.credentialId(), host.username());
                if (saved != null) return saved;
            }
            return picker.pick(host);
        };
    }

    private static @Nullable SshResolvedCredential promptPasswordSource(@NotNull SshHost host) {
        char[] pw = InlineCredentialPromptDialog.promptPassword(null, host);
        if (pw == null) return null;
        return SshResolvedCredential.password(host.username(), pw);
    }

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

    // -- connect loop ---------------------------------------------------------

    private @Nullable TtyConnector connectWithRetry(
        @NotNull SshHost host,
        @NotNull SshResolvedCredential initialCredential,
        @NotNull AuthSource source
    ) {
        ConchSshClient client = getClient();
        SshResolvedCredential current = initialCredential;
        int attemptsLeft = 2;  // initial + one retry after AUTH_FAILED

        while (attemptsLeft > 0) {
            attemptsLeft--;
            ConnectOutcome outcome = runConnect(client, host, current);

            if (outcome.connection != null) {
                current.close();
                return outcome.connection.getTtyConnector();
            }

            current.close();

            if (outcome.failure == null) return null;  // user cancelled mid-connect

            SshConnectException.Kind kind = outcome.failure.kind();
            if (kind == SshConnectException.Kind.AUTH_FAILED && attemptsLeft > 0) {
                SshResolvedCredential retry = source.fetch(host);
                if (retry == null) return null;
                current = retry;
                continue;
            }

            if (kind == SshConnectException.Kind.HOST_KEY_REJECTED) {
                Messages.showErrorDialog(
                    "Host key mismatch for " + host.host() + ":" + host.port() + ".\n\n"
                        + "The remote host presented a different key than the one Conch "
                        + "has on file. This may mean someone is intercepting your "
                        + "connection (man-in-the-middle attack).\n\n"
                        + "If the key legitimately changed, remove the entry from "
                        + "~/.config/conch/known_hosts manually and try again.",
                    "Host Key Rejected");
                return null;
            }

            if (kind == SshConnectException.Kind.HOST_UNREACHABLE) {
                Messages.showErrorDialog(
                    "Could not reach " + host.host() + ":" + host.port() + ":\n"
                        + outcome.failure.getMessage(),
                    "SSH Connection Failed");
                return null;
            }

            Messages.showErrorDialog(
                "SSH connection failed: " + outcome.failure.getMessage(),
                "SSH Connection Failed");
            return null;
        }

        return null;
    }

    private @NotNull ConnectOutcome runConnect(
        @NotNull ConchSshClient client,
        @NotNull SshHost host,
        @NotNull SshResolvedCredential credential
    ) {
        ConnectOutcome outcome = new ConnectOutcome();

        ProgressManager.getInstance().run(new Task.Modal(
            null,
            "Connecting to " + host.label() + " (" + host.host() + ":" + host.port() + ")…",
            /* canBeCancelled = */ true
        ) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    outcome.connection = client.connect(
                        host, credential, new ConchServerKeyVerifier());
                } catch (SshConnectException e) {
                    outcome.failure = e;
                } catch (Exception e) {
                    outcome.failure = new SshConnectException(
                        SshConnectException.Kind.UNKNOWN,
                        "Unexpected failure: " + e.getMessage(),
                        e);
                }
            }
        });

        return outcome;
    }

    private @NotNull ConchSshClient getClient() {
        if (ApplicationManager.getApplication() != null) {
            ConchSshClient service =
                ApplicationManager.getApplication().getService(ConchSshClient.class);
            if (service != null) return service;
        }
        return new ConchSshClient();
    }

    /** Mutable holder the Task.Modal populates with either success or failure. */
    private static final class ConnectOutcome {
        @Nullable SshConnection connection;
        @Nullable SshConnectException failure;
    }
}
```

- [ ] **Step 2: Build and run the test suite**

```bash
cd /Users/dustin/projects/conch_workbench && make conch-build
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //conch/plugins/ssh:ssh_test_runner
```

Expected: build succeeds, all tests pass. No new unit tests — the dispatch is wiring and the dialog paths are covered by the manual smoke test in Task 6.

- [ ] **Step 3: Commit**

```bash
cd /Users/dustin/projects/conch_workbench
git add plugins/ssh/src/com/conch/ssh/provider/SshSessionProvider.java
git commit -m "$(cat <<'EOF'
feat(ssh): dispatch on SshAuth in SshSessionProvider

VaultAuth keeps going through resolver+picker; PromptPasswordAuth and
KeyFileAuth route through InlineCredentialPromptDialog. AUTH_FAILED
retry now re-runs the same auth source instead of always running the
vault picker, so a prompt-password host re-prompts inline on retry.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `HostEditDialog` radio-button rewrite

Final piece — the dialog grows two new auth modes in the UI.

**Files:**
- Modify: `plugins/ssh/src/com/conch/ssh/ui/HostEditDialog.java`

- [ ] **Step 1: Rewrite `HostEditDialog.java`**

Replace the entire file with:

```java
package com.conch.ssh.ui;

import com.conch.sdk.CredentialProvider;
import com.conch.sdk.CredentialProvider.CredentialDescriptor;
import com.conch.ssh.model.KeyFileAuth;
import com.conch.ssh.model.PromptPasswordAuth;
import com.conch.ssh.model.SshAuth;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.model.VaultAuth;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Modal add/edit dialog for a single {@link SshHost}. Produces either a
 * brand-new host (add mode, {@code existing == null}) or an edited copy
 * of an existing one (edit mode).
 *
 * <p>Three auth modes, picked via radio buttons that mirror the layout
 * of the vault plugin's {@code AccountEditDialog}:
 * <ul>
 *   <li><b>Vault credential</b> — combo populated from every
 *       {@link CredentialProvider} extension filtered to SSH-usable
 *       kinds, plus a {@code <no credential>} option that produces
 *       {@code VaultAuth(null)} and makes the session provider run the
 *       vault picker at connect time.</li>
 *   <li><b>Password (prompt every connect)</b> — no fields; the connect
 *       flow shows {@code InlineCredentialPromptDialog.promptPassword}
 *       every time.</li>
 *   <li><b>SSH key file</b> — key path with browse button; passphrase
 *       is prompted at connect time by
 *       {@code InlineCredentialPromptDialog.promptPassphrase}.</li>
 * </ul>
 */
public final class HostEditDialog extends DialogWrapper {

    private static final ExtensionPointName<CredentialProvider> EP_NAME =
        ExtensionPointName.create("com.conch.core.credentialProvider");

    private static final Set<CredentialProvider.Kind> SUPPORTED_KINDS = EnumSet.of(
        CredentialProvider.Kind.ACCOUNT_PASSWORD,
        CredentialProvider.Kind.ACCOUNT_KEY,
        CredentialProvider.Kind.ACCOUNT_KEY_AND_PASSWORD,
        CredentialProvider.Kind.SSH_KEY
    );

    private final @Nullable SshHost existing;

    private final JTextField labelField = new JTextField(24);
    private final JTextField hostField = new JTextField(24);
    private final JSpinner portSpinner = new JSpinner(
        new SpinnerNumberModel(SshHost.DEFAULT_PORT, 1, 65535, 1));
    private final JTextField usernameField = new JTextField(24);

    private final JRadioButton vaultRadio = new JRadioButton("Vault credential", true);
    private final JRadioButton promptRadio = new JRadioButton("Password (prompt every connect)");
    private final JRadioButton keyFileRadio = new JRadioButton("SSH key file");

    private final JComboBox<CredentialEntry> credentialCombo = new JComboBox<>();
    private final TextFieldWithBrowseButton keyPathField = new TextFieldWithBrowseButton();

    private SshHost result;

    public HostEditDialog(@Nullable Project project, @Nullable SshHost existing) {
        super(project, true);
        this.existing = existing;
        setTitle(existing == null ? "Add SSH Host" : "Edit SSH Host");
        setOKButtonText(existing == null ? "Add" : "Save");
        wireKeyPathChooser();
        populateCredentialCombo();
        init();
        populateFromExisting();
        updateEnablement();
    }

    public static @Nullable SshHost show(@Nullable Project project, @Nullable SshHost existing) {
        HostEditDialog dlg = new HostEditDialog(project, existing);
        return dlg.showAndGet() ? dlg.result : null;
    }

    private void wireKeyPathChooser() {
        keyPathField.addBrowseFolderListener(new TextBrowseFolderListener(
            FileChooserDescriptorFactory.createSingleFileDescriptor()));
    }

    private void populateCredentialCombo() {
        credentialCombo.removeAllItems();
        credentialCombo.addItem(CredentialEntry.NONE);
        if (ApplicationManager.getApplication() == null) return;

        List<CredentialEntry> entries = new ArrayList<>();
        for (CredentialProvider provider : EP_NAME.getExtensionList()) {
            if (!provider.isAvailable()) continue;
            for (CredentialDescriptor d : provider.listCredentials()) {
                if (!SUPPORTED_KINDS.contains(d.kind())) continue;
                entries.add(new CredentialEntry(d));
            }
        }
        entries.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
        for (CredentialEntry e : entries) credentialCombo.addItem(e);
    }

    private void populateFromExisting() {
        if (existing == null) {
            vaultRadio.setSelected(true);
            credentialCombo.setSelectedItem(CredentialEntry.NONE);
            return;
        }
        labelField.setText(existing.label());
        hostField.setText(existing.host());
        portSpinner.setValue(existing.port());
        usernameField.setText(existing.username());

        switch (existing.auth()) {
            case VaultAuth v -> {
                vaultRadio.setSelected(true);
                selectVaultEntry(v.credentialId());
            }
            case PromptPasswordAuth p -> promptRadio.setSelected(true);
            case KeyFileAuth k -> {
                keyFileRadio.setSelected(true);
                keyPathField.setText(k.keyFilePath());
            }
        }
    }

    private void selectVaultEntry(@Nullable UUID savedId) {
        if (savedId == null) {
            credentialCombo.setSelectedItem(CredentialEntry.NONE);
            return;
        }
        for (int i = 0; i < credentialCombo.getItemCount(); i++) {
            CredentialEntry entry = credentialCombo.getItemAt(i);
            if (entry.matches(savedId)) {
                credentialCombo.setSelectedIndex(i);
                return;
            }
        }
        CredentialEntry missing = CredentialEntry.missing(savedId);
        credentialCombo.addItem(missing);
        credentialCombo.setSelectedItem(missing);
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(520, 320));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        panel.add(new JLabel("Label:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(labelField, c);

        c.gridy++; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Host:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(hostField, c);

        c.gridy++; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Port:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(portSpinner, c);

        c.gridy++; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel("Username:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(usernameField, c);

        ButtonGroup group = new ButtonGroup();
        group.add(vaultRadio);
        group.add(promptRadio);
        group.add(keyFileRadio);

        c.gridy++; c.gridx = 0; c.gridwidth = 2; c.weightx = 1;
        panel.add(new JLabel("Auth method:"), c);

        c.gridy++; c.gridx = 0; c.gridwidth = 2;
        panel.add(vaultRadio, c);
        c.gridy++; c.gridx = 0; c.gridwidth = 1; c.weightx = 0;
        panel.add(Box.createHorizontalStrut(24), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(credentialCombo, c);

        c.gridy++; c.gridx = 0; c.gridwidth = 2; c.weightx = 1;
        panel.add(promptRadio, c);

        c.gridy++; c.gridx = 0; c.gridwidth = 2;
        panel.add(keyFileRadio, c);
        c.gridy++; c.gridx = 0; c.gridwidth = 1; c.weightx = 0;
        panel.add(Box.createHorizontalStrut(24), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(keyPathField, c);

        vaultRadio.addActionListener(e -> updateEnablement());
        promptRadio.addActionListener(e -> updateEnablement());
        keyFileRadio.addActionListener(e -> updateEnablement());

        return panel;
    }

    private void updateEnablement() {
        credentialCombo.setEnabled(vaultRadio.isSelected());
        keyPathField.setEnabled(keyFileRadio.isSelected());
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return labelField;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (labelField.getText().trim().isEmpty()) {
            return new ValidationInfo("Label is required", labelField);
        }
        if (hostField.getText().trim().isEmpty()) {
            return new ValidationInfo("Host is required", hostField);
        }
        if (usernameField.getText().trim().isEmpty()) {
            return new ValidationInfo("Username is required", usernameField);
        }
        if (keyFileRadio.isSelected()) {
            String path = keyPathField.getText().trim();
            if (path.isEmpty()) {
                return new ValidationInfo("Key file path is required", keyPathField.getTextField());
            }
            if (!Files.isRegularFile(Paths.get(path))) {
                return new ValidationInfo("Key file does not exist", keyPathField.getTextField());
            }
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        String label = labelField.getText().trim();
        String host = hostField.getText().trim();
        int port = (Integer) portSpinner.getValue();
        String username = usernameField.getText().trim();

        SshAuth auth;
        if (promptRadio.isSelected()) {
            auth = new PromptPasswordAuth();
        } else if (keyFileRadio.isSelected()) {
            auth = new KeyFileAuth(keyPathField.getText().trim());
        } else {
            CredentialEntry selected = (CredentialEntry) credentialCombo.getSelectedItem();
            UUID credentialId = selected == null ? null : selected.id();
            auth = new VaultAuth(credentialId);
        }

        if (existing == null) {
            result = SshHost.create(label, host, port, username, auth);
        } else {
            result = existing.withEdited(label, host, port, username, auth);
        }

        super.doOKAction();
    }

    // -- combo entry ---------------------------------------------------------

    private static final class CredentialEntry {
        static final CredentialEntry NONE = new CredentialEntry(null, "<no credential>", null);

        private final @Nullable UUID id;
        private final @NotNull String label;
        private final @Nullable CredentialProvider.Kind kind;

        private CredentialEntry(@Nullable UUID id,
                                @NotNull String label,
                                @Nullable CredentialProvider.Kind kind) {
            this.id = id;
            this.label = label;
            this.kind = kind;
        }

        CredentialEntry(@NotNull CredentialDescriptor descriptor) {
            this(descriptor.id(),
                descriptor.displayName() + "  ·  " + descriptor.subtitle(),
                descriptor.kind());
        }

        static CredentialEntry missing(@NotNull UUID id) {
            return new CredentialEntry(id, "<missing credential " + id + ">", null);
        }

        @Nullable UUID id() { return id; }
        @NotNull String label() { return label; }
        boolean matches(@NotNull UUID other) { return Objects.equals(id, other); }

        @Override public String toString() { return label; }
    }
}
```

- [ ] **Step 2: Build**

```bash
cd /Users/dustin/projects/conch_workbench && make conch-build
```

Expected: build succeeds.

- [ ] **Step 3: Commit**

```bash
cd /Users/dustin/projects/conch_workbench
git add plugins/ssh/src/com/conch/ssh/ui/HostEditDialog.java
git commit -m "$(cat <<'EOF'
feat(ssh): HostEditDialog radio-button rewrite for three auth modes

Vault credential (existing), Password (prompt every connect), and SSH
key file (path persisted, passphrase prompted). Radio-button layout
mirrors the vault plugin's AccountEditDialog so the dialogs feel
consistent across plugins.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Smoke test gate

Not code — the gate is a checklist. Block merging until every item passes.

- [ ] **Step 1: Run the full test suite**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //conch/plugins/ssh:ssh_test_runner
```

Expected: all tests pass (the pre-existing 74 + 5 `SshAuthJsonTest` + 3 `HostsFileLegacyMigrationTest` + 2 new `SshHostTest` cases + rewritten resolver tests ≈ 85 total).

- [ ] **Step 2: Run the full product build**

```bash
cd /Users/dustin/projects/conch_workbench && make conch-build
```

Expected: `Build completed successfully`.

- [ ] **Step 3: Manual smoke test — vault auth unchanged**

```bash
cd /Users/dustin/projects/conch_workbench && make conch
```

1. Open the Hosts tool window (right sidebar).
2. Add a host pointing at a reachable server, selecting an existing vault credential in the combo.
3. Double-click the host → `Connecting to …` progress dialog → remote shell appears.
4. Close the tab.

Regression check: existing hosts loaded from `~/.config/conch/ssh-hosts.json` still connect (the legacy migration converted them silently on load).

- [ ] **Step 4: Manual smoke test — prompt-password mode**

1. Add a new host; pick the "Password (prompt every connect)" radio.
2. Save the host (no password is entered here).
3. Double-click → `InlineCredentialPromptDialog` appears with `Password for user@host:port` → type the password → click Connect.
4. The `Task.Modal` spinner appears, then a remote shell.
5. Close the tab.
6. Double-click again → the prompt appears again (nothing was cached).
7. Intentionally type a wrong password on a third connect → `AUTH_FAILED` path → the prompt re-opens (not the vault picker) → enter the correct password → connect.

- [ ] **Step 5: Manual smoke test — key-file mode with unencrypted key**

1. Generate or pick an unencrypted key at some path.
2. Add a new host; pick "SSH key file" radio; browse to the key; save.
3. Double-click → passphrase prompt appears with "Leave blank if unencrypted" hint → leave blank → Connect → remote shell.

- [ ] **Step 6: Manual smoke test — key-file mode with encrypted key**

1. Generate or pick an encrypted key at some path (e.g., `ssh-keygen -f /tmp/test -t ed25519` with a passphrase).
2. Add a host pointing at it.
3. Double-click → leave blank on first prompt → `AUTH_FAILED` → prompt re-opens → enter the passphrase → connect succeeds.

- [ ] **Step 7: Manual smoke test — edit round-trip**

1. Edit an existing Vault-mode host; flip to Password mode; save.
2. Reopen the Edit dialog → confirm the Password radio is selected and the combo is disabled.
3. Flip to Key File mode → save → reopen → confirm the path survived the round-trip.

If any item fails, stop and fix before merging.

---

## Self-review checklist (plan author ran this before handing off)

**1. Spec coverage:**
- Data model / sealed type → Task 1 ✓
- JSON adapter → Task 1 ✓
- Legacy migration on load → Task 2 (Step 5) + migration tests (Step 1) ✓
- `SshHost` field swap → Task 2 Steps 3-4 ✓
- `HostEditDialog` radio layout → Task 5 ✓
- `InlineCredentialPromptDialog` → Task 3 ✓
- Dispatch in `SshSessionProvider` → Task 4 ✓
- `AUTH_FAILED` retry reruns same auth source → Task 4 (Retrier-as-`AuthSource` in `connectWithRetry`) ✓
- Resolver signature change → Task 2 Step 7 ✓
- Standalone-key username fallback preserved → resolver's `convert` still uses `fallbackUsername` ✓
- Unit tests (each variant round-trip, legacy migration, resolver rewritten) → Tasks 1, 2 ✓
- Manual smoke test script → Task 6 ✓

No gaps.

**2. Type / method signature consistency:**
- `SshHost.create(..., SshAuth)` — matches every call site (Task 2 Steps 3-11, Task 5 Step 1)
- `SshHost.withEdited(..., SshAuth)` — matches `HostEditDialog.doOKAction` in both Task 2 (vault-only) and Task 5 (radios)
- `SshCredentialResolver.resolve(UUID, String)` — matches both call sites (Task 2 Step 9 vault-only dispatch, Task 4 `vaultSource`)
- `SshResolvedCredential.password(String, char[])` / `.key(String, Path, char[])` — matches the existing factory signatures
- `InlineCredentialPromptDialog.promptPassword(Project, SshHost)` / `.promptPassphrase(Project, SshHost, String)` — matches Task 4 call sites
- `VaultAuth.credentialId()` → `UUID` (nullable) — matches dialog and provider reads

**3. Placeholder scan:**
No "TBD", no "similar to", no hand-waving. Every code block is complete and compilable on its own.
