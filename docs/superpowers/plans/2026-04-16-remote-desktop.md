# Remote Desktop Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new `plugins/rdp/` plugin that lets users connect to Windows hosts via RDP in embedded TermLab editor tabs, with vault-backed credentials, clipboard sync, and shared-drive drag-and-drop.

**Architecture:** Rendering uses Apache Guacamole's HTML5 client (`guacamole-common-js`) inside a JCEF browser, connected through an in-process Netty WebSocket proxy to a bundled `guacd` sidecar. The plugin owns host profiles (`RdpHostStore`), cert trust (`RdpCertTrustStore`), the guacd lifecycle (`GuacdSupervisor`), the WS bridge (`GuacdProxy`), and the session tab UI. Installer-side bundling of `guacd` is out of scope; the Makefile resolves `guacd` from `$TERMLAB_GUACD_BIN` or the system path during dev.

**Tech Stack:** Java 21, IntelliJ Platform SDK (services, tool windows, actions, JCEF), Netty (via `//platform/platform-util-netty`), GSON, JUnit 5, Bazel, vendored `guacamole-common-js`.

**Spec:** `docs/superpowers/specs/2026-04-16-remote-desktop-design.md`

---

## File Structure

```
plugins/rdp/
├── BUILD.bazel
├── intellij.termlab.rdp.iml
├── resources/
│   ├── META-INF/plugin.xml
│   └── web/
│       ├── index.html
│       ├── client.js
│       ├── style.css
│       └── guacamole-common-js/          # vendored, pinned (1.5.5)
├── src/com/termlab/rdp/
│   ├── model/
│   │   ├── RdpHost.java
│   │   ├── RdpAuth.java
│   │   ├── VaultAuth.java
│   │   ├── PromptPasswordAuth.java
│   │   └── RdpSecurity.java
│   ├── persistence/
│   │   ├── RdpPaths.java
│   │   ├── RdpGson.java
│   │   ├── RdpHostsFile.java
│   │   ├── RdpHostStore.java
│   │   ├── RdpCertTrustFile.java
│   │   └── RdpCertTrustStore.java
│   ├── guacd/
│   │   ├── GuacdInstruction.java
│   │   ├── ConnectionParams.java
│   │   ├── GuacdHandshake.java
│   │   ├── TokenVault.java
│   │   ├── GuacdSupervisor.java
│   │   └── GuacdProxy.java
│   ├── session/
│   │   ├── RdpSessionState.java
│   │   ├── RdpSessionVirtualFile.java
│   │   ├── RdpSessionVirtualFileSystem.java
│   │   ├── RdpSessionEditorProvider.java
│   │   ├── RdpSessionEditor.java
│   │   ├── RdpSessionTab.java
│   │   ├── RdpClipboardBridge.java
│   │   └── SharedFolderManager.java
│   ├── toolwindow/
│   │   ├── RdpHostsToolWindowFactory.java
│   │   ├── RdpHostsToolWindow.java
│   │   └── RdpHostCellRenderer.java
│   ├── ui/
│   │   ├── RdpHostEditDialog.java
│   │   ├── RdpCertTrustPromptDialog.java
│   │   └── RdpHostPicker.java
│   ├── actions/
│   │   ├── NewRdpSessionAction.java
│   │   ├── ConnectToRdpHostAction.java
│   │   ├── AddRdpHostAction.java
│   │   ├── OpenRdpSharedFolderAction.java
│   │   ├── ReconnectRdpAction.java
│   │   ├── DisconnectRdpAction.java
│   │   └── SendCtrlAltDelAction.java
│   └── palette/
│       └── RdpHostsSearchEverywhereContributor.java
└── test/com/termlab/rdp/
    ├── TestRunner.java
    ├── model/RdpHostTest.java
    ├── persistence/
    │   ├── RdpHostStoreTest.java
    │   └── RdpCertTrustStoreTest.java
    └── guacd/
        ├── GuacdInstructionTest.java
        ├── ConnectionParamsTest.java
        ├── TokenVaultTest.java
        ├── FakeGuacdProcess.java
        ├── GuacdSupervisorTest.java
        └── GuacdProxyTest.java
```

Files modified in other modules:
- `BUILD.bazel` (root) — add `//termlab/plugins/rdp` to `termlab_run`.
- `customization/resources/idea/TermLabApplicationInfo.xml` — add essential-plugin entry.
- `Makefile` — add `termlab-guacd` target; wire it as a prereq of `termlab` and `termlab-build`.
- `README.md` — note `guacd` dependency under Requirements.

---

### Task 1: Plugin Skeleton, Makefile wiring, README dependency note

**Files:**
- Create: `plugins/rdp/BUILD.bazel`
- Create: `plugins/rdp/intellij.termlab.rdp.iml`
- Create: `plugins/rdp/resources/META-INF/plugin.xml`
- Create: `plugins/rdp/src/com/termlab/rdp/package-info.java`
- Create: `plugins/rdp/test/com/termlab/rdp/TestRunner.java`
- Create: `scripts/resolve-guacd.sh`
- Modify: `BUILD.bazel` (root) — add rdp to `termlab_run` runtime deps
- Modify: `customization/resources/idea/TermLabApplicationInfo.xml` — add essential-plugin
- Modify: `Makefile` — add `termlab-guacd` + wire as prereq
- Modify: `README.md` — add `guacd` under Requirements + a one-liner under Current Capabilities

- [ ] **Step 1: Create `plugins/rdp/BUILD.bazel`**

```python
load("@rules_java//java:defs.bzl", "java_binary")
load("@rules_jvm//:jvm.bzl", "jvm_library", "resourcegroup")

resourcegroup(
    name = "rdp_resources",
    srcs = glob(["resources/**/*"]),
    strip_prefix = "resources",
)

jvm_library(
    name = "rdp",
    module_name = "intellij.termlab.rdp",
    visibility = ["//visibility:public"],
    srcs = glob(["src/**/*.java"], allow_empty = True),
    resources = [":rdp_resources"],
    deps = [
        "//termlab/sdk",
        "//termlab/core",
        "//termlab/plugins/vault",
        "//platform/analysis-api:analysis",
        "//platform/core-api:core",
        "//platform/core-ui",
        "//platform/editor-ui-api:editor-ui",
        "//platform/ide-core",
        "//platform/platform-api:ide",
        "//platform/lang-api:lang",
        "//platform/platform-impl:ide-impl",
        "//platform/platform-util-netty:ide-util-netty",
        "//platform/projectModel-api:projectModel",
        "//platform/ui.jcef",
        "//platform/util",
        "//platform/util:util-ui",
        "@lib//:gson",
        "@lib//:jetbrains-annotations",
        "@lib//:kotlin-stdlib",
        "@lib//:netty-buffer",
        "@lib//:netty-codec-http",
    ],
)

jvm_library(
    name = "rdp_test_lib",
    module_name = "intellij.termlab.rdp.tests",
    visibility = ["//visibility:public"],
    srcs = glob(["test/**/*.java"], allow_empty = True),
    deps = [
        ":rdp",
        "//termlab/sdk",
        "//libraries/junit5",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
        "//platform/core-api:core",
        "//platform/util",
        "@lib//:gson",
        "@lib//:jetbrains-annotations",
        "@lib//:kotlin-stdlib",
    ],
)

java_binary(
    name = "rdp_test_runner",
    main_class = "com.termlab.rdp.TestRunner",
    runtime_deps = [
        ":rdp_test_lib",
        "//libraries/junit5-jupiter",
        "//libraries/junit5-launcher",
    ],
)

exports_files(["intellij.termlab.rdp.iml"], visibility = ["//visibility:public"])
```

*Note on Netty targets:* if `@lib//:netty-buffer` / `@lib//:netty-codec-http` resolve errors at build time, inspect `libraries/BUILD.bazel` in the intellij-community checkout and swap to whichever label the platform exposes. The `platform-util-netty` target re-exports Netty and is typically sufficient on its own — in that case drop the two `@lib//:netty-*` lines.

- [ ] **Step 2: Create `plugins/rdp/intellij.termlab.rdp.iml`** (mirror `plugins/ssh/intellij.termlab.ssh.iml`)

Copy the SSH `.iml` file as-is, then replace every occurrence of:
- `intellij.termlab.ssh` → `intellij.termlab.rdp`
- `com.termlab.ssh` → `com.termlab.rdp`

Run: `diff plugins/ssh/intellij.termlab.ssh.iml plugins/rdp/intellij.termlab.rdp.iml`
Expected: only naming differences; no structural diffs.

- [ ] **Step 3: Create `plugins/rdp/resources/META-INF/plugin.xml`**

Minimal skeleton that compiles; later tasks add services, tool window, editor provider, actions.

```xml
<idea-plugin>
    <id>com.termlab.rdp</id>
    <name>TermLab Remote Desktop</name>
    <vendor>TermLab</vendor>
    <description>Embedded RDP (Windows Remote Desktop) sessions with
    clipboard sync and shared-drive drag-and-drop, rendered via
    Apache Guacamole.</description>

    <depends>com.termlab.core</depends>
    <depends>com.termlab.vault</depends>
    <depends>com.intellij.modules.platform</depends>

    <extensions defaultExtensionNs="com.intellij">
    </extensions>
</idea-plugin>
```

- [ ] **Step 4: Create `plugins/rdp/src/com/termlab/rdp/package-info.java`**

```java
/**
 * Root package for the TermLab Remote Desktop plugin. See
 * {@code docs/superpowers/specs/2026-04-16-remote-desktop-design.md} for the design.
 */
package com.termlab.rdp;
```

- [ ] **Step 5: Create `plugins/rdp/test/com/termlab/rdp/TestRunner.java`**

Copy `plugins/ssh/test/com/termlab/ssh/TestRunner.java`, replace package `com.termlab.ssh` → `com.termlab.rdp`. Keeps test-runner parity across plugins.

- [ ] **Step 6: Wire the plugin into `BUILD.bazel` (root)**

Add `"//termlab/plugins/rdp",` to the `termlab_run` target's `runtime_deps` list, in the "TermLab bundled plugins" section, alphabetically after `//termlab/plugins/editor` (before `runner`).

```python
        # Remote Desktop: embedded RDP sessions via Guacamole + JCEF.
        "//termlab/plugins/rdp",
```

- [ ] **Step 7: Register as essential plugin in `TermLabApplicationInfo.xml`**

Add under the existing `<essential-plugin>` entries:

```xml
  <essential-plugin>com.termlab.rdp</essential-plugin>
```

- [ ] **Step 8: Create `scripts/resolve-guacd.sh`**

Resolves a `guacd` binary for local dev runs. Priority: `$TERMLAB_GUACD_BIN` → `which guacd` → platform-specific install hint + exit 1. On success, prints the absolute path to stdout.

```bash
#!/usr/bin/env bash
# Usage: scripts/resolve-guacd.sh
# Prints the absolute path to a working guacd binary for local TermLab runs.
# Priority:
#   1. $TERMLAB_GUACD_BIN (explicit override)
#   2. `which guacd`      (system install, e.g. Homebrew / apt / dnf)
#   3. fail with an install hint
set -euo pipefail

if [[ -n "${TERMLAB_GUACD_BIN:-}" ]]; then
    if [[ -x "$TERMLAB_GUACD_BIN" ]]; then
        echo "$TERMLAB_GUACD_BIN"
        exit 0
    fi
    echo "ERROR: TERMLAB_GUACD_BIN=$TERMLAB_GUACD_BIN is not executable" >&2
    exit 1
fi

if command -v guacd >/dev/null 2>&1; then
    command -v guacd
    exit 0
fi

UNAME="$(uname -s)"
case "$UNAME" in
    Darwin) HINT="brew install guacamole-server" ;;
    Linux)
        if command -v apt >/dev/null 2>&1; then
            HINT="sudo apt install guacd"
        elif command -v dnf >/dev/null 2>&1; then
            HINT="sudo dnf install guacd"
        elif command -v pacman >/dev/null 2>&1; then
            HINT="sudo pacman -S guacamole-server"
        else
            HINT="install guacd from https://guacamole.apache.org/ (or your distro's package manager)"
        fi
        ;;
    *) HINT="install guacd from https://guacamole.apache.org/" ;;
esac

cat >&2 <<EOF
ERROR: could not find 'guacd' (Apache Guacamole daemon).

TermLab's Remote Desktop plugin needs guacd available at runtime.

Options:
  • install it:       $HINT
  • or set the env var: TERMLAB_GUACD_BIN=/absolute/path/to/guacd

EOF
exit 1
```

Make it executable:
```
chmod +x scripts/resolve-guacd.sh
```

- [ ] **Step 9: Wire `termlab-guacd` target into the Makefile**

Insert this target after `termlab-version` and list it as a prereq on `termlab` and `termlab-build`:

```make
# Resolve a working guacd binary for dev runs. Prints its path to
# build/guacd-path.txt so the RDP plugin's GuacdSupervisor can pick
# it up via the TERMLAB_GUACD_BIN env var in termlab_run below.
termlab-guacd:
	@mkdir -p "$(WORKBENCH_DIR)/build"
	@bash $(WORKBENCH_DIR)/scripts/resolve-guacd.sh > "$(WORKBENCH_DIR)/build/guacd-path.txt"
	@echo "→ guacd: $$(cat $(WORKBENCH_DIR)/build/guacd-path.txt)"
```

Change the existing `termlab` and `termlab-build` targets to depend on it:

```make
termlab: check-intellij termlab-version termlab-guacd
	TERMLAB_GUACD_BIN="$$(cat $(WORKBENCH_DIR)/build/guacd-path.txt)" \
	$(BAZEL) run //termlab:termlab_run -- $(TERMLAB_WORKSPACE)

termlab-build: check-intellij termlab-version termlab-guacd
	$(BAZEL) build //termlab:termlab_run
```

Add `termlab-guacd` to the `.PHONY` list.

- [ ] **Step 10: Update `README.md`**

Under `## Quick Start` → `### Requirements`, add a bullet:

```
- Apache `guacd` daemon (for Remote Desktop sessions) — `brew install guacamole-server` on macOS, `sudo apt install guacd` on Debian/Ubuntu, or set `TERMLAB_GUACD_BIN` to a custom build
```

Under `## Current Capabilities`, add:

```
- Embedded RDP sessions to Windows hosts with clipboard sync and shared-drive file transfer
```

- [ ] **Step 11: Build**

Run:
```
make termlab-build
```
Expected: build succeeds; `build/guacd-path.txt` exists and contains an absolute path to a `guacd` binary.

If `guacd` isn't installed locally, expected failure is the clear `resolve-guacd.sh` error; install it and re-run.

- [ ] **Step 12: Commit**

```
git add plugins/rdp BUILD.bazel Makefile README.md \
        customization/resources/idea/TermLabApplicationInfo.xml \
        scripts/resolve-guacd.sh
git commit -m "feat(rdp): scaffold plugin skeleton and guacd resolution"
```

---

### Task 2: Paths, Gson singleton, sealed `RdpAuth`, `RdpSecurity`, `RdpHost`

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/persistence/RdpPaths.java`
- Create: `plugins/rdp/src/com/termlab/rdp/model/RdpAuth.java`
- Create: `plugins/rdp/src/com/termlab/rdp/model/VaultAuth.java`
- Create: `plugins/rdp/src/com/termlab/rdp/model/PromptPasswordAuth.java`
- Create: `plugins/rdp/src/com/termlab/rdp/model/RdpSecurity.java`
- Create: `plugins/rdp/src/com/termlab/rdp/model/RdpHost.java`
- Create: `plugins/rdp/src/com/termlab/rdp/persistence/RdpGson.java`
- Create: `plugins/rdp/test/com/termlab/rdp/model/RdpHostTest.java`

- [ ] **Step 1: `RdpPaths.java`** — mirrors `HostPaths`:

```java
package com.termlab.rdp.persistence;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Resolves on-disk locations for the RDP plugin. */
public final class RdpPaths {

    private RdpPaths() {}

    public static @NotNull Path hostsFile() {
        return configDir().resolve("rdp-hosts.json");
    }

    public static @NotNull Path certTrustFile() {
        return configDir().resolve("rdp-known-certs.json");
    }

    public static @NotNull Path sharedRoot() {
        return Paths.get(System.getProperty("user.home"), "TermLab", "RDP Shared");
    }

    private static @NotNull Path configDir() {
        return Paths.get(System.getProperty("user.home"), ".config", "termlab");
    }
}
```

- [ ] **Step 2: `RdpAuth.java` (sealed interface)**

```java
package com.termlab.rdp.model;

/**
 * How an RDP host authenticates. Sealed, following the
 * {@code SshAuth} pattern in the SSH plugin.
 */
public sealed interface RdpAuth permits VaultAuth, PromptPasswordAuth {}
```

- [ ] **Step 3: `VaultAuth.java`**

```java
package com.termlab.rdp.model;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Password stored in the TermLab vault. {@code credentialId} may be
 * null when the user has selected "use the vault" but has not yet
 * bound a specific credential; the connect flow treats that as a
 * prompt to pick one.
 */
public record VaultAuth(@Nullable UUID credentialId) implements RdpAuth {}
```

- [ ] **Step 4: `PromptPasswordAuth.java`**

```java
package com.termlab.rdp.model;

/** Prompt for a password on every connect; nothing is persisted. */
public record PromptPasswordAuth() implements RdpAuth {}
```

- [ ] **Step 5: `RdpSecurity.java`**

```java
package com.termlab.rdp.model;

/** RDP security-layer negotiation modes, as understood by guacd. */
public enum RdpSecurity {
    /** Network-Level Authentication (modern Windows Server). Default. */
    NLA,
    /** TLS without NLA. */
    TLS,
    /** Legacy Standard RDP security. */
    RDP,
    /** Let guacd pick whichever the server supports. */
    ANY;

    /** guacd's `security` parameter value for this mode. */
    public @org.jetbrains.annotations.NotNull String guacValue() {
        return switch (this) {
            case NLA -> "nla";
            case TLS -> "tls";
            case RDP -> "rdp";
            case ANY -> "any";
        };
    }
}
```

- [ ] **Step 6: `RdpHost.java`**

```java
package com.termlab.rdp.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record RdpHost(
    @NotNull UUID id,
    @NotNull String label,
    @NotNull String host,
    int port,
    @NotNull String username,
    @Nullable String domain,
    @NotNull RdpAuth auth,
    @NotNull RdpSecurity security,
    int colorDepth,
    @Nullable Integer initialWidth,
    @Nullable Integer initialHeight,
    int dpiScale,
    boolean multiMonitor,
    boolean enableClipboard,
    boolean enableDriveShare,
    @Nullable String sharedFolderPath,
    boolean sendCmdAsCtrl,
    boolean ignoreCertErrors,
    @NotNull Instant createdAt,
    @NotNull Instant updatedAt
) {
    public static final int DEFAULT_PORT = 3389;
    public static final int DEFAULT_COLOR_DEPTH = 32;
    public static final int DEFAULT_DPI_SCALE = 100;

    public RdpHost {
        domain = trimToNull(domain);
        sharedFolderPath = trimToNull(sharedFolderPath);
    }

    public @NotNull RdpHost withLabel(@NotNull String newLabel) {
        return copyWith(newLabel, host, port, username, domain, auth, security,
            colorDepth, initialWidth, initialHeight, dpiScale, multiMonitor,
            enableClipboard, enableDriveShare, sharedFolderPath, sendCmdAsCtrl,
            ignoreCertErrors);
    }

    public @NotNull RdpHost withAuth(@NotNull RdpAuth newAuth) {
        return copyWith(label, host, port, username, domain, newAuth, security,
            colorDepth, initialWidth, initialHeight, dpiScale, multiMonitor,
            enableClipboard, enableDriveShare, sharedFolderPath, sendCmdAsCtrl,
            ignoreCertErrors);
    }

    public static @NotNull RdpHost create(
        @NotNull String label,
        @NotNull String host,
        int port,
        @NotNull String username,
        @Nullable String domain,
        @NotNull RdpAuth auth
    ) {
        Instant now = Instant.now();
        return new RdpHost(
            UUID.randomUUID(), label, host, port, username, domain, auth,
            RdpSecurity.NLA, DEFAULT_COLOR_DEPTH, null, null, DEFAULT_DPI_SCALE,
            false, true, true, null, false, false, now, now);
    }

    private RdpHost copyWith(
        String label, String host, int port, String username, String domain,
        RdpAuth auth, RdpSecurity security, int colorDepth,
        Integer initialWidth, Integer initialHeight, int dpiScale,
        boolean multiMonitor, boolean enableClipboard, boolean enableDriveShare,
        String sharedFolderPath, boolean sendCmdAsCtrl, boolean ignoreCertErrors
    ) {
        return new RdpHost(id, label, host, port, username, domain, auth, security,
            colorDepth, initialWidth, initialHeight, dpiScale, multiMonitor,
            enableClipboard, enableDriveShare, sharedFolderPath, sendCmdAsCtrl,
            ignoreCertErrors, createdAt, Instant.now());
    }

    private static @Nullable String trimToNull(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
```

- [ ] **Step 7: `RdpGson.java`** — mirrors `SshGson` with a two-variant sealed adapter

```java
package com.termlab.rdp.persistence;

import com.termlab.rdp.model.PromptPasswordAuth;
import com.termlab.rdp.model.RdpAuth;
import com.termlab.rdp.model.VaultAuth;
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

public final class RdpGson {

    public static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(Instant.class, new InstantAdapter())
        .registerTypeAdapter(RdpAuth.class, new RdpAuthSerializer())
        .registerTypeAdapter(RdpAuth.class, new RdpAuthDeserializer())
        .create();

    private RdpGson() {}

    private static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override public void write(JsonWriter out, Instant v) throws IOException {
            if (v == null) out.nullValue(); else out.value(v.toString());
        }
        @Override public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
            return Instant.parse(in.nextString());
        }
    }

    private static final class RdpAuthSerializer implements JsonSerializer<RdpAuth> {
        @Override public JsonElement serialize(RdpAuth src, java.lang.reflect.Type t,
                                               com.google.gson.JsonSerializationContext c) {
            JsonObject o = new JsonObject();
            switch (src) {
                case VaultAuth v -> {
                    o.addProperty("type", "vault");
                    if (v.credentialId() != null) {
                        o.addProperty("credentialId", v.credentialId().toString());
                    }
                }
                case PromptPasswordAuth p -> o.addProperty("type", "prompt_password");
            }
            return o;
        }
    }

    private static final class RdpAuthDeserializer implements JsonDeserializer<RdpAuth> {
        @Override public RdpAuth deserialize(JsonElement json, java.lang.reflect.Type t,
                                             com.google.gson.JsonDeserializationContext c) {
            JsonObject o = json.getAsJsonObject();
            String type = o.get("type").getAsString();
            return switch (type) {
                case "vault" -> {
                    UUID id = null;
                    if (o.has("credentialId") && !o.get("credentialId").isJsonNull()) {
                        id = UUID.fromString(o.get("credentialId").getAsString());
                    }
                    yield new VaultAuth(id);
                }
                case "prompt_password" -> new PromptPasswordAuth();
                default -> throw new JsonParseException("unknown RdpAuth type: " + type);
            };
        }
    }
}
```

- [ ] **Step 8: Write failing test `RdpHostTest.java`**

```java
package com.termlab.rdp.model;

import com.termlab.rdp.persistence.RdpGson;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class RdpHostTest {

    @Test
    void create_fillsDefaults() {
        RdpHost h = RdpHost.create("box", "example.com", 3389, "alice", null, new PromptPasswordAuth());
        assertEquals("box", h.label());
        assertEquals(RdpSecurity.NLA, h.security());
        assertEquals(32, h.colorDepth());
        assertEquals(100, h.dpiScale());
        assertEquals(3389, h.port());
        assertEquals(true, h.enableClipboard());
        assertEquals(true, h.enableDriveShare());
        assertNotNull(h.id());
    }

    @Test
    void gson_roundTrip_vaultAuth() {
        UUID cid = UUID.randomUUID();
        RdpHost h = RdpHost.create("box", "example.com", 3389, "alice", "CORP", new VaultAuth(cid));
        String json = RdpGson.GSON.toJson(h);
        RdpHost restored = RdpGson.GSON.fromJson(json, RdpHost.class);
        assertEquals(h, restored);
        assertEquals(new VaultAuth(cid), restored.auth());
    }

    @Test
    void gson_roundTrip_promptAuth() {
        RdpHost h = RdpHost.create("box", "example.com", 3389, "alice", null, new PromptPasswordAuth());
        String json = RdpGson.GSON.toJson(h);
        RdpHost restored = RdpGson.GSON.fromJson(json, RdpHost.class);
        assertEquals(h, restored);
        assertEquals(new PromptPasswordAuth(), restored.auth());
    }
}
```

- [ ] **Step 9: Run the test — expect FAIL with "no test runner" or similar if `TestRunner.java` isn't wired yet.** Wire it, re-run; then expect PASS.

Run: `$(BAZEL) run //termlab/plugins/rdp:rdp_test_runner`
Expected: 3 tests PASS.

- [ ] **Step 10: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/model \
        plugins/rdp/src/com/termlab/rdp/persistence \
        plugins/rdp/test/com/termlab/rdp/model
git commit -m "feat(rdp): add host, auth, security model and Gson"
```

---

### Task 3: `RdpHostsFile` + `RdpHostStore`

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/persistence/RdpHostsFile.java`
- Create: `plugins/rdp/src/com/termlab/rdp/persistence/RdpHostStore.java`
- Create: `plugins/rdp/test/com/termlab/rdp/persistence/RdpHostStoreTest.java`
- Modify: `plugins/rdp/resources/META-INF/plugin.xml` (register app service)

- [ ] **Step 1: `RdpHostsFile.java`** — atomic load/save, versioned envelope, exactly like `HostsFile` but for `RdpHost`.

```java
package com.termlab.rdp.persistence;

import com.termlab.rdp.model.RdpHost;
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

public final class RdpHostsFile {

    public static final int VERSION = 1;

    private RdpHostsFile() {}

    public static void save(@NotNull Path target, @NotNull List<RdpHost> hosts) throws IOException {
        Envelope env = new Envelope(VERSION, new ArrayList<>(hosts));
        String json = RdpGson.GSON.toJson(env);
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static @NotNull List<RdpHost> load(@NotNull Path source) throws IOException {
        if (!Files.isRegularFile(source)) return Collections.emptyList();
        String raw = Files.readString(source);
        try {
            JsonElement rootEl = JsonParser.parseString(raw);
            if (rootEl == null || !rootEl.isJsonObject()) return Collections.emptyList();
            JsonObject root = rootEl.getAsJsonObject();
            if (!root.has("hosts") || !root.get("hosts").isJsonArray()) {
                return Collections.emptyList();
            }
            Envelope env = RdpGson.GSON.fromJson(root, Envelope.class);
            if (env == null || env.hosts == null) return Collections.emptyList();
            return env.hosts;
        } catch (Exception malformed) {
            return Collections.emptyList();
        }
    }

    public static boolean exists(@NotNull Path target) {
        return Files.isRegularFile(target);
    }

    static final class Envelope {
        int version;
        List<RdpHost> hosts;

        Envelope() {}
        Envelope(int version, List<RdpHost> hosts) {
            this.version = version;
            this.hosts = hosts;
        }
    }
}
```

- [ ] **Step 2: `RdpHostStore.java`** — mirrors `HostStore` verbatim, swapping types and paths.

```java
package com.termlab.rdp.persistence;

import com.termlab.rdp.model.RdpHost;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Application service holding the saved RDP host list. Persistence is
 * delegated to {@link RdpHostsFile}.
 */
public final class RdpHostStore {

    private static final Logger LOG = Logger.getInstance(RdpHostStore.class);

    private final Path path;
    private final List<RdpHost> hosts = new ArrayList<>();
    private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public RdpHostStore() {
        this(RdpPaths.hostsFile(), loadSilently(RdpPaths.hostsFile()));
    }

    public RdpHostStore(@NotNull Path path, @NotNull List<RdpHost> initial) {
        this.path = path;
        hosts.addAll(initial);
    }

    public @NotNull List<RdpHost> getHosts() {
        return Collections.unmodifiableList(new ArrayList<>(hosts));
    }

    public @Nullable RdpHost findById(@NotNull UUID id) {
        for (RdpHost h : hosts) if (h.id().equals(id)) return h;
        return null;
    }

    public void addHost(@NotNull RdpHost host) {
        hosts.add(host);
        fireChanged();
    }

    public boolean removeHost(@NotNull UUID id) {
        boolean removed = hosts.removeIf(h -> h.id().equals(id));
        if (removed) fireChanged();
        return removed;
    }

    public boolean updateHost(@NotNull RdpHost updated) {
        for (int i = 0; i < hosts.size(); i++) {
            if (hosts.get(i).id().equals(updated.id())) {
                hosts.set(i, updated);
                fireChanged();
                return true;
            }
        }
        return false;
    }

    public void save() throws IOException {
        RdpHostsFile.save(path, hosts);
    }

    public void reload() throws IOException {
        hosts.clear();
        hosts.addAll(RdpHostsFile.load(path));
        fireChanged();
    }

    public void addChangeListener(@NotNull Runnable listener) {
        changeListeners.addIfAbsent(listener);
    }

    public void removeChangeListener(@NotNull Runnable listener) {
        changeListeners.remove(listener);
    }

    private void fireChanged() {
        for (Runnable r : changeListeners) {
            try { r.run(); } catch (Throwable t) { LOG.warn("[rdp] listener failed", t); }
        }
    }

    private static @NotNull List<RdpHost> loadSilently(@NotNull Path path) {
        try {
            return RdpHostsFile.load(path);
        } catch (IOException e) {
            LOG.warn("[rdp] could not load hosts from " + path, e);
            return new ArrayList<>();
        }
    }
}
```

- [ ] **Step 3: Register the service in `plugin.xml`**

```xml
    <extensions defaultExtensionNs="com.intellij">
        <applicationService serviceImplementation="com.termlab.rdp.persistence.RdpHostStore"/>
    </extensions>
```

- [ ] **Step 4: `RdpHostStoreTest.java` — write failing tests first**

```java
package com.termlab.rdp.persistence;

import com.termlab.rdp.model.PromptPasswordAuth;
import com.termlab.rdp.model.RdpHost;
import com.termlab.rdp.model.VaultAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class RdpHostStoreTest {

    @Test
    void crud_roundTripsToDisk(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("rdp-hosts.json");
        RdpHostStore s1 = new RdpHostStore(file, List.of());
        RdpHost a = RdpHost.create("A", "a.example", 3389, "alice", null, new PromptPasswordAuth());
        s1.addHost(a);
        s1.save();

        RdpHostStore s2 = new RdpHostStore(file, RdpHostsFile.load(file));
        assertEquals(1, s2.getHosts().size());
        assertEquals("A", s2.getHosts().get(0).label());
    }

    @Test
    void updateHost_replacesByIdAndFiresListener(@TempDir Path tmp) throws Exception {
        RdpHostStore store = new RdpHostStore(tmp.resolve("hosts.json"), List.of());
        RdpHost h = RdpHost.create("A", "a.example", 3389, "alice", null, new PromptPasswordAuth());
        store.addHost(h);
        AtomicInteger calls = new AtomicInteger();
        store.addChangeListener(calls::incrementAndGet);
        assertTrue(store.updateHost(h.withLabel("A2")));
        assertEquals("A2", store.getHosts().get(0).label());
        assertEquals(1, calls.get());
    }

    @Test
    void load_returnsEmpty_forMissingFile(@TempDir Path tmp) throws Exception {
        List<RdpHost> loaded = RdpHostsFile.load(tmp.resolve("nope.json"));
        assertTrue(loaded.isEmpty());
    }

    @Test
    void load_survivesMalformedJson(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("bad.json");
        java.nio.file.Files.writeString(file, "{not: valid json");
        assertTrue(RdpHostsFile.load(file).isEmpty());
    }

    @Test
    void findById_returnsNullWhenAbsent() {
        RdpHostStore store = new RdpHostStore(Path.of("/tmp/ignored"), List.of());
        assertNull(store.findById(UUID.randomUUID()));
    }

    @Test
    void vaultAuthCredentialIdPersists(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("hosts.json");
        UUID cid = UUID.randomUUID();
        RdpHost h = RdpHost.create("V", "v.example", 3389, "u", null, new VaultAuth(cid));
        RdpHostStore s1 = new RdpHostStore(file, List.of(h));
        s1.save();
        RdpHost restored = new RdpHostStore(file, RdpHostsFile.load(file)).getHosts().get(0);
        assertEquals(new VaultAuth(cid), restored.auth());
    }
}
```

- [ ] **Step 5: Run tests**

Run: `$(BAZEL) run //termlab/plugins/rdp:rdp_test_runner`
Expected: all 6 `RdpHostStore*` tests PASS, plus the 3 from Task 2.

- [ ] **Step 6: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/persistence \
        plugins/rdp/test/com/termlab/rdp/persistence \
        plugins/rdp/resources/META-INF/plugin.xml
git commit -m "feat(rdp): add host store with atomic persistence"
```

---

### Task 4: `RdpCertTrustFile` + `RdpCertTrustStore`

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/persistence/RdpCertTrustFile.java`
- Create: `plugins/rdp/src/com/termlab/rdp/persistence/RdpCertTrustStore.java`
- Create: `plugins/rdp/test/com/termlab/rdp/persistence/RdpCertTrustStoreTest.java`
- Modify: `plugins/rdp/resources/META-INF/plugin.xml` (register second app service)

- [ ] **Step 1: `RdpCertTrustFile.java`**

```java
package com.termlab.rdp.persistence;

import com.termlab.rdp.persistence.RdpCertTrustStore.Entry;
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

public final class RdpCertTrustFile {

    public static final int VERSION = 1;

    private RdpCertTrustFile() {}

    public static void save(@NotNull Path target, @NotNull List<Entry> entries) throws IOException {
        Envelope env = new Envelope(VERSION, new ArrayList<>(entries));
        String json = RdpGson.GSON.toJson(env);
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static @NotNull List<Entry> load(@NotNull Path source) throws IOException {
        if (!Files.isRegularFile(source)) return Collections.emptyList();
        String raw = Files.readString(source);
        try {
            JsonElement rootEl = JsonParser.parseString(raw);
            if (rootEl == null || !rootEl.isJsonObject()) return Collections.emptyList();
            JsonObject root = rootEl.getAsJsonObject();
            if (!root.has("entries") || !root.get("entries").isJsonArray()) {
                return Collections.emptyList();
            }
            Envelope env = RdpGson.GSON.fromJson(root, Envelope.class);
            if (env == null || env.entries == null) return Collections.emptyList();
            return env.entries;
        } catch (Exception malformed) {
            return Collections.emptyList();
        }
    }

    static final class Envelope {
        int version;
        List<Entry> entries;
        Envelope() {}
        Envelope(int version, List<Entry> entries) { this.version = version; this.entries = entries; }
    }
}
```

- [ ] **Step 2: `RdpCertTrustStore.java`**

```java
package com.termlab.rdp.persistence;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Trust-on-first-use store for RDP server certificate fingerprints.
 * Mirrors the SSH {@code KnownHostsFile} pattern.
 */
public final class RdpCertTrustStore {

    private static final Logger LOG = Logger.getInstance(RdpCertTrustStore.class);

    private final Path path;
    private final List<Entry> entries = new ArrayList<>();

    public RdpCertTrustStore() {
        this(RdpPaths.certTrustFile(), loadSilently(RdpPaths.certTrustFile()));
    }

    public RdpCertTrustStore(@NotNull Path path, @NotNull List<Entry> initial) {
        this.path = path;
        entries.addAll(initial);
    }

    public enum TrustDecision {
        /** Fingerprint matches a pinned record — connect without prompting. */
        TRUSTED,
        /** No record for this host — prompt TOFU. */
        UNKNOWN,
        /** Record exists but fingerprint differs — block / prompt mismatch. */
        MISMATCH
    }

    public @NotNull TrustDecision evaluate(@NotNull String hostKey, @NotNull String fingerprint) {
        for (Entry e : entries) {
            if (e.hostKey.equals(hostKey)) {
                return e.sha256.equals(fingerprint) ? TrustDecision.TRUSTED : TrustDecision.MISMATCH;
            }
        }
        return TrustDecision.UNKNOWN;
    }

    public void trust(@NotNull String hostKey, @NotNull String subject, @NotNull String fingerprint) {
        entries.removeIf(e -> e.hostKey.equals(hostKey));
        entries.add(new Entry(hostKey, subject, fingerprint, Instant.now()));
    }

    public @Nullable Entry find(@NotNull String hostKey) {
        for (Entry e : entries) if (e.hostKey.equals(hostKey)) return e;
        return null;
    }

    public @NotNull List<Entry> all() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public void save() throws IOException {
        RdpCertTrustFile.save(path, entries);
    }

    /** "host:port" form used as the trust key. */
    public static @NotNull String hostKey(@NotNull String host, int port) {
        return host + ":" + port;
    }

    private static @NotNull List<Entry> loadSilently(@NotNull Path path) {
        try {
            return RdpCertTrustFile.load(path);
        } catch (IOException e) {
            LOG.warn("[rdp] could not load cert trust from " + path, e);
            return new ArrayList<>();
        }
    }

    public static final class Entry {
        public final String hostKey;
        public final String subject;
        public final String sha256;
        public final Instant lastSeen;

        public Entry() { this("", "", "", Instant.EPOCH); }

        public Entry(String hostKey, String subject, String sha256, Instant lastSeen) {
            this.hostKey = hostKey;
            this.subject = subject;
            this.sha256 = sha256;
            this.lastSeen = lastSeen;
        }
    }
}
```

- [ ] **Step 3: Register in `plugin.xml`**

```xml
<applicationService serviceImplementation="com.termlab.rdp.persistence.RdpCertTrustStore"/>
```

- [ ] **Step 4: `RdpCertTrustStoreTest.java`**

```java
package com.termlab.rdp.persistence;

import com.termlab.rdp.persistence.RdpCertTrustStore.Entry;
import com.termlab.rdp.persistence.RdpCertTrustStore.TrustDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RdpCertTrustStoreTest {

    @Test
    void unknown_whenNoRecord() {
        RdpCertTrustStore s = new RdpCertTrustStore(Path.of("/tmp/ignored"), List.of());
        assertEquals(TrustDecision.UNKNOWN, s.evaluate("h:3389", "AA"));
    }

    @Test
    void trustedAfterPinning(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("certs.json");
        RdpCertTrustStore s1 = new RdpCertTrustStore(file, List.of());
        s1.trust("h:3389", "CN=h", "AA");
        s1.save();
        RdpCertTrustStore s2 = new RdpCertTrustStore(file, RdpCertTrustFile.load(file));
        assertEquals(TrustDecision.TRUSTED, s2.evaluate("h:3389", "AA"));
    }

    @Test
    void mismatchOnDifferentFingerprint() {
        RdpCertTrustStore s = new RdpCertTrustStore(Path.of("/tmp/ignored"),
            List.of(new Entry("h:3389", "CN=h", "AA", java.time.Instant.now())));
        assertEquals(TrustDecision.MISMATCH, s.evaluate("h:3389", "BB"));
    }

    @Test
    void trustReplacesExisting() {
        RdpCertTrustStore s = new RdpCertTrustStore(Path.of("/tmp/ignored"), List.of());
        s.trust("h:3389", "CN=h", "AA");
        s.trust("h:3389", "CN=h", "BB");
        assertEquals(1, s.all().size());
        assertEquals("BB", s.all().get(0).sha256);
    }
}
```

- [ ] **Step 5: Run tests**

Expected: 4 cert-trust tests PASS alongside prior tests.

- [ ] **Step 6: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/persistence \
        plugins/rdp/test/com/termlab/rdp/persistence \
        plugins/rdp/resources/META-INF/plugin.xml
git commit -m "feat(rdp): add cert trust-on-first-use store"
```

---

### Task 5: `GuacdInstruction` encoder/decoder

The Guacamole protocol frames every message as a length-prefixed list of elements terminated by `;`, e.g. `4.size,4.1024,3.768,2.32;`. This is the unit we encode/decode everywhere.

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/guacd/GuacdInstruction.java`
- Create: `plugins/rdp/test/com/termlab/rdp/guacd/GuacdInstructionTest.java`

- [ ] **Step 1: `GuacdInstruction.java`**

```java
package com.termlab.rdp.guacd;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One Guacamole protocol instruction: an opcode plus zero or more string
 * arguments, wire-encoded as length-prefixed UTF-8 elements separated by
 * commas and terminated by a semicolon. See
 * https://guacamole.apache.org/doc/gug/guacamole-protocol.html
 *
 * <p>Length is the UTF-16 code-unit count per the spec, so we measure
 * with {@code String.length()}, not the UTF-8 byte length.
 */
public final class GuacdInstruction {

    private final String opcode;
    private final List<String> args;

    public GuacdInstruction(@NotNull String opcode, @NotNull String... args) {
        this(opcode, Arrays.asList(args));
    }

    public GuacdInstruction(@NotNull String opcode, @NotNull List<String> args) {
        this.opcode = opcode;
        this.args = List.copyOf(args);
    }

    public @NotNull String opcode() { return opcode; }
    public @NotNull List<String> args() { return args; }

    /** Encode to the Guacamole wire format as UTF-8 bytes. */
    public byte @NotNull [] encode() {
        StringBuilder sb = new StringBuilder();
        append(sb, opcode);
        for (String a : args) {
            sb.append(',');
            append(sb, a);
        }
        sb.append(';');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void append(StringBuilder sb, String v) {
        sb.append(v.length()).append('.').append(v);
    }

    /**
     * Decode one instruction from a buffer starting at {@code offset}.
     * Returns {@code null} if the buffer does not yet contain a complete
     * instruction terminated by ';'. On success, advances the reader.
     */
    public static @org.jetbrains.annotations.Nullable Decoded tryDecode(byte @NotNull [] buf, int offset, int limit) {
        List<String> parts = new ArrayList<>();
        int i = offset;
        while (i < limit) {
            int dot = findDot(buf, i, limit);
            if (dot < 0) return null;
            int len;
            try {
                len = Integer.parseInt(new String(buf, i, dot - i, StandardCharsets.UTF_8));
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("malformed length prefix");
            }
            int textStart = dot + 1;
            int textEndByteExclusive = textStart;
            int utf16Seen = 0;
            while (textEndByteExclusive < limit && utf16Seen < len) {
                int cp = codePointAt(buf, textEndByteExclusive, limit);
                textEndByteExclusive += utf8Width(cp);
                utf16Seen += Character.charCount(cp);
            }
            if (utf16Seen < len) return null;
            if (textEndByteExclusive >= limit) return null;
            parts.add(new String(buf, textStart, textEndByteExclusive - textStart, StandardCharsets.UTF_8));
            byte sep = buf[textEndByteExclusive];
            i = textEndByteExclusive + 1;
            if (sep == ';') {
                String opcode = parts.remove(0);
                return new Decoded(new GuacdInstruction(opcode, parts), i - offset);
            } else if (sep != ',') {
                throw new IllegalArgumentException("expected , or ; got " + (char) sep);
            }
        }
        return null;
    }

    private static int findDot(byte[] buf, int start, int limit) {
        for (int i = start; i < limit; i++) if (buf[i] == '.') return i;
        return -1;
    }

    private static int codePointAt(byte[] buf, int offset, int limit) {
        return new String(buf, offset, Math.min(4, limit - offset), StandardCharsets.UTF_8).codePointAt(0);
    }

    private static int utf8Width(int cp) {
        if (cp < 0x80) return 1;
        if (cp < 0x800) return 2;
        if (cp < 0x10000) return 3;
        return 4;
    }

    @Override public boolean equals(Object o) {
        return o instanceof GuacdInstruction g && opcode.equals(g.opcode) && args.equals(g.args);
    }
    @Override public int hashCode() { return Objects.hash(opcode, args); }
    @Override public String toString() { return "GuacdInstruction{" + opcode + args + "}"; }

    /** Result of a successful decode: the instruction plus bytes consumed. */
    public record Decoded(@NotNull GuacdInstruction instruction, int bytesConsumed) {}
}
```

- [ ] **Step 2: `GuacdInstructionTest.java`**

```java
package com.termlab.rdp.guacd;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class GuacdInstructionTest {

    @Test
    void encode_sizeInstruction() {
        byte[] bytes = new GuacdInstruction("size", "1024", "768").encode();
        assertEquals("4.size,4.1024,3.768;", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void decode_roundTrip() {
        byte[] wire = "4.size,4.1024,3.768;".getBytes(StandardCharsets.UTF_8);
        GuacdInstruction.Decoded d = GuacdInstruction.tryDecode(wire, 0, wire.length);
        assertNotNull(d);
        assertEquals(wire.length, d.bytesConsumed());
        assertEquals("size", d.instruction().opcode());
        assertEquals(java.util.List.of("1024", "768"), d.instruction().args());
    }

    @Test
    void decode_returnsNull_whenIncomplete() {
        byte[] wire = "4.size,4.10".getBytes(StandardCharsets.UTF_8);
        assertNull(GuacdInstruction.tryDecode(wire, 0, wire.length));
    }

    @Test
    void decode_handlesMultibyte() {
        // "é" is 1 UTF-16 code unit but 2 UTF-8 bytes.
        byte[] wire = "3.arg,1.é;".getBytes(StandardCharsets.UTF_8);
        GuacdInstruction.Decoded d = GuacdInstruction.tryDecode(wire, 0, wire.length);
        assertNotNull(d);
        assertEquals("é", d.instruction().args().get(0));
    }

    @Test
    void encode_emptyArg() {
        byte[] bytes = new GuacdInstruction("select", "").encode();
        assertEquals("6.select,0.;", new String(bytes, StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 3: Run tests — expect 5 new tests PASS.**

- [ ] **Step 4: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/guacd/GuacdInstruction.java \
        plugins/rdp/test/com/termlab/rdp/guacd/GuacdInstructionTest.java
git commit -m "feat(rdp): add Guacamole protocol instruction codec"
```

---

### Task 6: `ConnectionParams` + `GuacdHandshake`

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/guacd/ConnectionParams.java`
- Create: `plugins/rdp/src/com/termlab/rdp/guacd/GuacdHandshake.java`
- Create: `plugins/rdp/test/com/termlab/rdp/guacd/ConnectionParamsTest.java`

- [ ] **Step 1: `ConnectionParams.java`**

```java
package com.termlab.rdp.guacd;

import com.termlab.rdp.model.RdpHost;
import com.termlab.rdp.model.RdpSecurity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Snapshot of everything the RDP connect handshake needs. Built when the
 * session tab is created; handed to {@link GuacdProxy} via a token.
 * Password is held in-memory only; never serialized.
 */
public record ConnectionParams(
    @NotNull String hostname,
    int port,
    @NotNull String username,
    @Nullable String domain,
    @Nullable String password,
    @NotNull RdpSecurity security,
    boolean ignoreCert,
    int width,
    int height,
    int dpi,
    int colorDepth,
    boolean enableClipboard,
    boolean enableDrive,
    @Nullable String drivePath,
    @Nullable String pinnedCertSha256
) {
    public static @NotNull ConnectionParams from(
        @NotNull RdpHost host,
        @Nullable String resolvedPassword,
        int width,
        int height,
        @Nullable String pinnedCertSha256,
        @Nullable String drivePath
    ) {
        return new ConnectionParams(
            host.host(),
            host.port(),
            host.username(),
            host.domain(),
            resolvedPassword,
            host.security(),
            host.ignoreCertErrors(),
            width,
            height,
            host.dpiScale(),
            host.colorDepth(),
            host.enableClipboard(),
            host.enableDriveShare(),
            drivePath,
            pinnedCertSha256
        );
    }
}
```

- [ ] **Step 2: `GuacdHandshake.java`**

```java
package com.termlab.rdp.guacd;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the sequence of Guacamole instructions that negotiate an RDP
 * connection. See:
 * https://guacamole.apache.org/doc/gug/guacamole-protocol.html#protocol-control
 *
 * <p>Flow:
 * <pre>
 *   client → select rdp
 *   guacd  → args <list of param names in order>
 *   client → size/audio/video/image
 *   client → connect <values matching guacd's args order>
 * </pre>
 *
 * <p>Because the order of args depends on guacd's version, the
 * {@link #buildConnect(List, ConnectionParams)} method maps each param
 * name guacd asked for to a value from {@link ConnectionParams}.
 */
public final class GuacdHandshake {

    private GuacdHandshake() {}

    public static @NotNull GuacdInstruction select() {
        return new GuacdInstruction("select", "rdp");
    }

    public static @NotNull GuacdInstruction size(int width, int height, int dpi) {
        return new GuacdInstruction("size",
            Integer.toString(width), Integer.toString(height), Integer.toString(dpi));
    }

    public static @NotNull GuacdInstruction audio() {
        return new GuacdInstruction("audio");
    }

    public static @NotNull GuacdInstruction video() {
        return new GuacdInstruction("video");
    }

    public static @NotNull GuacdInstruction image(@NotNull String... mimes) {
        return new GuacdInstruction("image", mimes);
    }

    /**
     * Build the final {@code connect} instruction, mapping each parameter
     * name guacd announced in its {@code args} reply to a string value.
     * Unknown parameter names are sent as empty strings (per Guacamole
     * compatibility rules).
     */
    public static @NotNull GuacdInstruction buildConnect(
        @NotNull List<String> paramNames, @NotNull ConnectionParams p
    ) {
        List<String> values = new ArrayList<>(paramNames.size());
        for (String name : paramNames) values.add(paramValue(name, p));
        return new GuacdInstruction("connect", values);
    }

    static @NotNull String paramValue(@NotNull String name, @NotNull ConnectionParams p) {
        return switch (name) {
            case "hostname" -> p.hostname();
            case "port" -> Integer.toString(p.port());
            case "username" -> p.username();
            case "password" -> p.password() == null ? "" : p.password();
            case "domain" -> p.domain() == null ? "" : p.domain();
            case "security" -> p.security().guacValue();
            case "ignore-cert" -> p.ignoreCert() ? "true" : "false";
            case "width" -> Integer.toString(p.width());
            case "height" -> Integer.toString(p.height());
            case "dpi" -> Integer.toString(p.dpi());
            case "color-depth" -> Integer.toString(p.colorDepth());
            case "enable-clipboard" -> p.enableClipboard() ? "true" : "false";
            case "enable-drive" -> p.enableDrive() ? "true" : "false";
            case "drive-path" -> p.drivePath() == null ? "" : p.drivePath();
            case "drive-name" -> "TermLab";
            case "create-drive-path" -> "true";
            case "resize-method" -> "display-update";
            case "server-layout" -> "en-us-qwerty";
            default -> "";
        };
    }
}
```

- [ ] **Step 3: `ConnectionParamsTest.java`**

```java
package com.termlab.rdp.guacd;

import com.termlab.rdp.model.PromptPasswordAuth;
import com.termlab.rdp.model.RdpHost;
import com.termlab.rdp.model.RdpSecurity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ConnectionParamsTest {

    @Test
    void from_copiesCoreFields() {
        RdpHost h = RdpHost.create("B", "example.com", 3389, "alice", "CORP", new PromptPasswordAuth());
        ConnectionParams p = ConnectionParams.from(h, "s3cret", 1600, 900, null, "/tmp/share");
        assertEquals("example.com", p.hostname());
        assertEquals("alice", p.username());
        assertEquals("CORP", p.domain());
        assertEquals("s3cret", p.password());
        assertEquals(1600, p.width());
        assertEquals(900, p.height());
        assertEquals(RdpSecurity.NLA, p.security());
        assertTrue(p.enableClipboard());
        assertTrue(p.enableDrive());
        assertEquals("/tmp/share", p.drivePath());
    }

    @Test
    void handshake_select_emitsRdp() {
        assertArrayEquals("6.select,3.rdp;".getBytes(), GuacdHandshake.select().encode());
    }

    @Test
    void handshake_buildConnect_mapsNamedParams() {
        RdpHost h = RdpHost.create("B", "example.com", 3389, "alice", "CORP", new PromptPasswordAuth());
        ConnectionParams p = ConnectionParams.from(h, "s3cret", 1600, 900, null, "/tmp/share");
        GuacdInstruction c = GuacdHandshake.buildConnect(
            List.of("hostname", "port", "username", "password", "security", "width", "height"),
            p);
        assertEquals("connect", c.opcode());
        assertEquals(List.of("example.com", "3389", "alice", "s3cret", "nla", "1600", "900"), c.args());
    }

    @Test
    void handshake_unknownParam_emptyString() {
        RdpHost h = RdpHost.create("B", "ex", 3389, "a", null, new PromptPasswordAuth());
        ConnectionParams p = ConnectionParams.from(h, null, 100, 100, null, null);
        assertEquals("", GuacdHandshake.paramValue("not-real", p));
    }

    @Test
    void handshake_nullPassword_emptyString() {
        RdpHost h = RdpHost.create("B", "ex", 3389, "a", null, new PromptPasswordAuth());
        ConnectionParams p = ConnectionParams.from(h, null, 100, 100, null, null);
        assertEquals("", GuacdHandshake.paramValue("password", p));
    }
}
```

- [ ] **Step 4: Run tests — expect 5 new PASS.**

- [ ] **Step 5: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/guacd \
        plugins/rdp/test/com/termlab/rdp/guacd
git commit -m "feat(rdp): add ConnectionParams and handshake builder"
```

---

### Task 7: `TokenVault`

One-time connection tokens for the WS proxy. 128-bit random, single-use, 30s TTL. Token → `ConnectionParams` mapping lives entirely in memory.

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/guacd/TokenVault.java`
- Create: `plugins/rdp/test/com/termlab/rdp/guacd/TokenVaultTest.java`

- [ ] **Step 1: `TokenVault.java`**

```java
package com.termlab.rdp.guacd;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues and consumes one-time tokens mapping to {@link ConnectionParams}.
 * Tokens are single-use and expire after {@link #DEFAULT_TTL}.
 *
 * <p>Invariant: a token can only be consumed once. After
 * {@link #consume(String)} it is removed from the vault regardless of
 * the outcome.
 */
public final class TokenVault {

    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);
    private static final SecureRandom RNG = new SecureRandom();

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    public TokenVault() {
        this(DEFAULT_TTL, Clock.systemUTC());
    }

    public TokenVault(@NotNull Duration ttl, @NotNull Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    public @NotNull String issue(@NotNull ConnectionParams params) {
        byte[] raw = new byte[16];
        RNG.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        pending.put(token, new Pending(params, clock.instant().plus(ttl)));
        return token;
    }

    public @Nullable ConnectionParams consume(@NotNull String token) {
        expire();
        Pending p = pending.remove(token);
        if (p == null) return null;
        if (clock.instant().isAfter(p.expiresAt)) return null;
        return p.params;
    }

    public int size() { return pending.size(); }

    private void expire() {
        Instant now = clock.instant();
        for (Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().expiresAt.isBefore(now)) it.remove();
        }
    }

    private record Pending(ConnectionParams params, Instant expiresAt) {}
}
```

- [ ] **Step 2: `TokenVaultTest.java`**

```java
package com.termlab.rdp.guacd;

import com.termlab.rdp.model.PromptPasswordAuth;
import com.termlab.rdp.model.RdpHost;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class TokenVaultTest {

    private static ConnectionParams sample() {
        RdpHost h = RdpHost.create("B", "h", 3389, "u", null, new PromptPasswordAuth());
        return ConnectionParams.from(h, null, 100, 100, null, null);
    }

    @Test
    void issueAndConsumeReturnsParamsOnce() {
        TokenVault v = new TokenVault();
        String t = v.issue(sample());
        assertNotNull(v.consume(t));
        assertNull(v.consume(t), "token must be single-use");
    }

    @Test
    void unknownTokenIsNull() {
        assertNull(new TokenVault().consume("deadbeef"));
    }

    @Test
    void expiredTokenIsNull() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-04-16T12:00:00Z"));
        Clock clock = Clock.fixed(now.get(), ZoneOffset.UTC) {
            @Override public Instant instant() { return now.get(); }
        };
        TokenVault v = new TokenVault(Duration.ofSeconds(30), clock);
        String t = v.issue(sample());
        now.set(now.get().plusSeconds(31));
        assertNull(v.consume(t));
    }

    @Test
    void tokensAreDistinct() {
        TokenVault v = new TokenVault();
        String a = v.issue(sample());
        String b = v.issue(sample());
        assertNotEquals(a, b);
    }
}
```

*Note:* the `Clock.fixed` subclass trick above works because `Clock.fixed(...)` is a final-instance wrapper; if the inline override fails to compile under javac's rules, replace with:

```java
Clock clock = new Clock() {
    @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId z) { return this; }
    @Override public Instant instant() { return now.get(); }
};
```

Use that form if needed.

- [ ] **Step 3: Run tests — expect 4 new PASS.**

- [ ] **Step 4: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/guacd/TokenVault.java \
        plugins/rdp/test/com/termlab/rdp/guacd/TokenVaultTest.java
git commit -m "feat(rdp): add one-time TokenVault for proxy handshakes"
```

---

### Task 8: `GuacdSupervisor` (+ `FakeGuacdProcess`)

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/guacd/GuacdSupervisor.java`
- Create: `plugins/rdp/test/com/termlab/rdp/guacd/FakeGuacdProcess.java`
- Create: `plugins/rdp/test/com/termlab/rdp/guacd/GuacdSupervisorTest.java`
- Modify: `plugins/rdp/resources/META-INF/plugin.xml` (register third app service)

- [ ] **Step 1: `GuacdSupervisor.java`**

```java
package com.termlab.rdp.guacd;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the single bundled {@code guacd} process for the app lifetime.
 * Lazy-start on first connect; loopback-bound ephemeral port; crash →
 * dead → restart on next {@link #getOrStart()}.
 */
public final class GuacdSupervisor implements Disposable {

    private static final Logger LOG = Logger.getInstance(GuacdSupervisor.class);

    public static @NotNull GuacdSupervisor get() {
        return ApplicationManager.getApplication().getService(GuacdSupervisor.class);
    }

    private final Launcher launcher;
    private final AtomicReference<Running> running = new AtomicReference<>();

    public GuacdSupervisor() { this(new DefaultLauncher()); }

    public GuacdSupervisor(@NotNull Launcher launcher) {
        this.launcher = launcher;
        Disposer.register(ApplicationManager.getApplication(), this);
    }

    /** @return a live guacd endpoint; starts the daemon if not running. */
    public synchronized @NotNull Endpoint getOrStart() throws IOException {
        Running r = running.get();
        if (r != null && r.process.isAlive()) return r.endpoint;
        int port = allocatePort();
        Process p = launcher.start(port);
        if (!handshake(port)) {
            p.destroyForcibly();
            throw new IOException("guacd on port " + port + " did not respond to select handshake");
        }
        drainStderr(p);
        Running next = new Running(p, new Endpoint("127.0.0.1", port));
        running.set(next);
        LOG.info("[rdp/guacd] started, pid=" + p.pid() + " port=" + port);
        return next.endpoint;
    }

    public boolean isAlive() {
        Running r = running.get();
        return r != null && r.process.isAlive();
    }

    @Override public void dispose() {
        Running r = running.getAndSet(null);
        if (r == null) return;
        r.process.destroy();
        try {
            if (!r.process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                r.process.destroyForcibly();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            r.process.destroyForcibly();
        }
    }

    private static int allocatePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    /** Open a socket to guacd and exchange select → args to verify it answered. */
    static boolean handshake(int port) {
        try (Socket sock = new Socket()) {
            sock.connect(new java.net.InetSocketAddress("127.0.0.1", port), 3000);
            sock.setSoTimeout(3000);
            sock.getOutputStream().write(new GuacdInstruction("select", "rdp").encode());
            sock.getOutputStream().flush();
            byte[] buf = new byte[4096];
            int n = sock.getInputStream().read(buf);
            if (n <= 0) return false;
            GuacdInstruction.Decoded d = GuacdInstruction.tryDecode(buf, 0, n);
            return d != null && "args".equals(d.instruction().opcode());
        } catch (IOException e) {
            return false;
        }
    }

    private void drainStderr(Process p) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) LOG.info("[rdp/guacd] " + line);
            } catch (IOException ignored) {}
        }, "rdp-guacd-stderr-drain");
        t.setDaemon(true);
        t.start();
    }

    /** Public for tests: pluggable process launcher. */
    public interface Launcher {
        @NotNull Process start(int port) throws IOException;
    }

    /** Default launcher: runs the resolved guacd binary as a child process. */
    private static final class DefaultLauncher implements Launcher {
        @Override public @NotNull Process start(int port) throws IOException {
            String bin = resolveBinary();
            ProcessBuilder pb = new ProcessBuilder(bin, "-b", "127.0.0.1", "-l", Integer.toString(port), "-f");
            pb.redirectErrorStream(false);
            return pb.start();
        }
        private static @NotNull String resolveBinary() throws IOException {
            String env = System.getenv("TERMLAB_GUACD_BIN");
            if (env != null && !env.isBlank()) return env;
            for (String dir : List.of("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", "/bin")) {
                java.nio.file.Path p = java.nio.file.Paths.get(dir, "guacd");
                if (java.nio.file.Files.isExecutable(p)) return p.toString();
            }
            throw new IOException("could not locate guacd binary (set TERMLAB_GUACD_BIN)");
        }
    }

    public record Endpoint(@NotNull String host, int port) {}
    private record Running(Process process, Endpoint endpoint) {}
}
```

- [ ] **Step 2: `FakeGuacdProcess.java`** — test double that mimics guacd's select/args reply.

```java
package com.termlab.rdp.guacd;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal fake guacd: accepts one socket, replies to select with args. */
public final class FakeGuacdProcess implements AutoCloseable {

    private final ServerSocket server;
    private final Thread acceptor;
    private final AtomicBoolean alive = new AtomicBoolean(true);

    public FakeGuacdProcess() throws IOException {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::run, "fake-guacd");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() { return server.getLocalPort(); }

    public @NotNull GuacdSupervisor.Launcher launcher() {
        return port -> {
            // Ignore the requested port — the supervisor talks to our
            // server's port via the handshake flow.
            return new FakeProcess();
        };
    }

    @Override public void close() throws IOException {
        alive.set(false);
        server.close();
    }

    private void run() {
        while (alive.get()) {
            try (Socket s = server.accept()) {
                InputStream in = s.getInputStream();
                byte[] buf = new byte[1024];
                int n = in.read(buf);
                if (n <= 0) continue;
                OutputStream out = s.getOutputStream();
                out.write(new GuacdInstruction("args", "hostname", "port", "username", "password").encode());
                out.flush();
            } catch (IOException e) {
                if (alive.get()) throw new RuntimeException(e);
            }
        }
    }

    /** A Process stub that looks alive until close() is invoked. */
    private static final class FakeProcess extends Process {
        private volatile boolean alive = true;
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return alive ? 0 : -1; }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return -1;
        }
        @Override public void destroy() { alive = false; }
        @Override public boolean isAlive() { return alive; }
    }
}
```

*Known limitation:* `FakeProcess` doesn't wire the handshake port into the supervisor; the supervisor will call `handshake(requestedPort)` against whatever port it allocated locally, not ours. This is intentional — the supervisor test uses a different entry point (`handshake()` called directly in Step 3). The fake process is used for lifecycle tests only.

- [ ] **Step 3: `GuacdSupervisorTest.java`**

```java
package com.termlab.rdp.guacd;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

final class GuacdSupervisorTest {

    @Test
    void handshake_returnsTrue_onValidArgsReply() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            Thread t = new Thread(() -> {
                try (Socket s = server.accept();
                     OutputStream out = s.getOutputStream();
                     InputStream in = s.getInputStream()) {
                    byte[] buf = new byte[256];
                    in.read(buf);
                    out.write(new GuacdInstruction("args", "hostname", "port").encode());
                    out.flush();
                } catch (IOException ignored) {}
            });
            t.setDaemon(true);
            t.start();
            assertTrue(GuacdSupervisor.handshake(port));
        }
    }

    @Test
    void handshake_returnsFalse_onClosedPort() {
        assertFalse(GuacdSupervisor.handshake(1));
    }

    @Test
    void handshake_returnsFalse_onWrongOpcode() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            Thread t = new Thread(() -> {
                try (Socket s = server.accept(); OutputStream out = s.getOutputStream()) {
                    s.getInputStream().read(new byte[64]);
                    out.write(new GuacdInstruction("error", "nope").encode());
                    out.flush();
                } catch (IOException ignored) {}
            });
            t.setDaemon(true);
            t.start();
            assertFalse(GuacdSupervisor.handshake(port));
        }
    }
}
```

- [ ] **Step 4: Register service in `plugin.xml`**

```xml
<applicationService serviceImplementation="com.termlab.rdp.guacd.GuacdSupervisor"/>
```

- [ ] **Step 5: Run tests — expect 3 new PASS.**

- [ ] **Step 6: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/guacd/GuacdSupervisor.java \
        plugins/rdp/test/com/termlab/rdp/guacd/FakeGuacdProcess.java \
        plugins/rdp/test/com/termlab/rdp/guacd/GuacdSupervisorTest.java \
        plugins/rdp/resources/META-INF/plugin.xml
git commit -m "feat(rdp): add guacd supervisor with handshake verification"
```

---

### Task 9: `GuacdProxy` WebSocket server

Bridges the JCEF browser (which speaks Guacamole-over-WebSocket) to a TCP socket on `guacd`. Uses the Netty that IntelliJ ships via `//platform/platform-util-netty`.

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/guacd/GuacdProxy.java`
- Create: `plugins/rdp/test/com/termlab/rdp/guacd/GuacdProxyTest.java`
- Modify: `plugins/rdp/resources/META-INF/plugin.xml` (register app service)

- [ ] **Step 1: `GuacdProxy.java`**

```java
package com.termlab.rdp.guacd;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loopback WebSocket server that bridges the JCEF browser's Guacamole
 * client to the bundled guacd daemon. Accepts one WS connection per
 * TermLab RDP tab; validates a one-time token from {@link TokenVault};
 * pumps bytes in both directions with the {@link GuacdHandshake} written
 * into the guacd-bound side before the browser's first instruction.
 */
public final class GuacdProxy implements Disposable {

    private static final Logger LOG = Logger.getInstance(GuacdProxy.class);
    private static final Pattern TOKEN_RE = Pattern.compile("token=([a-f0-9]+)");

    public static @NotNull GuacdProxy get() {
        return ApplicationManager.getApplication().getService(GuacdProxy.class);
    }

    private final TokenVault tokens = new TokenVault();
    private volatile EventLoopGroup boss;
    private volatile EventLoopGroup worker;
    private volatile Channel serverChannel;
    private volatile int port = -1;

    public GuacdProxy() {
        Disposer.register(ApplicationManager.getApplication(), this);
    }

    public @NotNull TokenVault tokens() { return tokens; }

    /** Lazy start the proxy; returns its loopback URL base (no token appended). */
    public synchronized @NotNull String baseUrl() throws IOException {
        if (serverChannel != null && serverChannel.isActive()) {
            return "ws://127.0.0.1:" + port + "/tunnel";
        }
        port = pickPort();
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup();
        try {
            ChannelFuture f = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(65536));
                        ch.pipeline().addLast(new TokenExtractor());
                        ch.pipeline().addLast(new WebSocketServerProtocolHandler("/tunnel"));
                        ch.pipeline().addLast(new BrowserSideHandler());
                    }
                })
                .bind(InetAddress.getLoopbackAddress(), port)
                .sync();
            serverChannel = f.channel();
            LOG.info("[rdp/proxy] listening on 127.0.0.1:" + port);
            return "ws://127.0.0.1:" + port + "/tunnel";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted starting proxy", ie);
        }
    }

    @Override public synchronized void dispose() {
        if (serverChannel != null) serverChannel.close();
        if (boss != null) boss.shutdownGracefully();
        if (worker != null) worker.shutdownGracefully();
        serverChannel = null;
        boss = worker = null;
        port = -1;
    }

    private static int pickPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    /** Inbound handler that plucks ?token=... from the upgrade request. */
    private final class TokenExtractor extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            Matcher m = TOKEN_RE.matcher(req.uri());
            if (!m.find()) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN))
                   .addListener(f -> ctx.close());
                return;
            }
            ConnectionParams params = tokens.consume(m.group(1));
            if (params == null) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN))
                   .addListener(f -> ctx.close());
                return;
            }
            ctx.channel().attr(io.netty.util.AttributeKey.<ConnectionParams>valueOf("params")).set(params);
            req.setUri("/tunnel");
            ctx.fireChannelRead(req.retain());
        }
    }

    /** Pipeline between the browser WS and a freshly-dialed guacd socket. */
    private final class BrowserSideHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

        private Socket guacd;
        private Thread guacdToBrowser;
        private OutputStream guacdOut;
        private volatile boolean handshakeDone;

        @Override public void channelActive(ChannelHandlerContext ctx) throws IOException {
            GuacdSupervisor.Endpoint ep = GuacdSupervisor.get().getOrStart();
            guacd = new Socket(ep.host(), ep.port());
            guacdOut = guacd.getOutputStream();
            ConnectionParams params = ctx.channel()
                .attr(io.netty.util.AttributeKey.<ConnectionParams>valueOf("params")).get();
            performGuacdHandshake(guacdOut, guacd.getInputStream(), params);
            handshakeDone = true;
            guacdToBrowser = new Thread(() -> pumpGuacdToBrowser(ctx), "rdp-proxy-pump");
            guacdToBrowser.setDaemon(true);
            guacdToBrowser.start();
        }

        @Override protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws IOException {
            if (!handshakeDone) return;
            ByteBuf content;
            if (frame instanceof TextWebSocketFrame t) content = t.content();
            else if (frame instanceof BinaryWebSocketFrame b) content = b.content();
            else return;
            byte[] bytes = new byte[content.readableBytes()];
            content.readBytes(bytes);
            guacdOut.write(bytes);
            guacdOut.flush();
        }

        @Override public void channelInactive(ChannelHandlerContext ctx) {
            closeGuacd();
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.warn("[rdp/proxy] pipeline error", cause);
            closeGuacd();
            ctx.close();
        }

        private void pumpGuacdToBrowser(ChannelHandlerContext ctx) {
            try (InputStream in = guacd.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    byte[] copy = new byte[n];
                    System.arraycopy(buf, 0, copy, 0, n);
                    ctx.channel().writeAndFlush(
                        new TextWebSocketFrame(new String(copy, StandardCharsets.UTF_8)));
                }
            } catch (IOException ioe) {
                LOG.info("[rdp/proxy] guacd stream closed: " + ioe.getMessage());
            } finally {
                ctx.close();
            }
        }

        private void closeGuacd() {
            try { if (guacd != null) guacd.close(); } catch (IOException ignored) {}
        }
    }

    /** Run select → args → size/audio/video/image → connect on the guacd side. */
    static void performGuacdHandshake(
        @NotNull OutputStream out, @NotNull InputStream in, @NotNull ConnectionParams p
    ) throws IOException {
        out.write(GuacdHandshake.select().encode());
        out.flush();
        GuacdInstruction args = readInstruction(in);
        if (!"args".equals(args.opcode())) {
            throw new IOException("expected args from guacd, got " + args.opcode());
        }
        out.write(GuacdHandshake.size(p.width(), p.height(), p.dpi()).encode());
        out.write(GuacdHandshake.audio().encode());
        out.write(GuacdHandshake.video().encode());
        out.write(GuacdHandshake.image("image/png", "image/jpeg").encode());
        out.write(GuacdHandshake.buildConnect(args.args(), p).encode());
        out.flush();
    }

    private static GuacdInstruction readInstruction(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        int total = 0;
        while (true) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) throw new IOException("guacd closed stream during handshake");
            total += n;
            GuacdInstruction.Decoded d = GuacdInstruction.tryDecode(buf, 0, total);
            if (d != null) return d.instruction();
            if (total == buf.length) throw new IOException("guacd instruction exceeded buffer");
        }
    }
}
```

- [ ] **Step 2: Register in `plugin.xml`**

```xml
<applicationService serviceImplementation="com.termlab.rdp.guacd.GuacdProxy"/>
```

- [ ] **Step 3: `GuacdProxyTest.java`** — unit-test the handshake logic in isolation.

```java
package com.termlab.rdp.guacd;

import com.termlab.rdp.model.PromptPasswordAuth;
import com.termlab.rdp.model.RdpHost;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class GuacdProxyTest {

    @Test
    void performGuacdHandshake_writesExpectedSequence() throws Exception {
        // Fake guacd: replies to `select` with `args,hostname,port,username,password,security`
        byte[] argsReply = new GuacdInstruction(
            "args", "hostname", "port", "username", "password", "security").encode();
        ByteArrayInputStream in = new ByteArrayInputStream(argsReply);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        RdpHost h = RdpHost.create("B", "ex", 3389, "alice", null, new PromptPasswordAuth());
        ConnectionParams p = ConnectionParams.from(h, "pw", 1600, 900, null, null);

        GuacdProxy.performGuacdHandshake(out, in, p);

        String sent = out.toString(StandardCharsets.UTF_8);
        // Must start with `select rdp`, then size/audio/video/image, then connect.
        assertTrue(sent.startsWith("6.select,3.rdp;"),
            "handshake must start with select; got: " + sent);
        assertTrue(sent.contains("4.size,4.1600,3.900"), sent);
        assertTrue(sent.contains("7.connect,"), sent);
        // Password arg should appear in the connect frame.
        assertTrue(sent.contains(",2.pw,"), "connect must carry password; got: " + sent);
    }

    @Test
    void performGuacdHandshake_failsIfArgsMissing() {
        byte[] unexpected = new GuacdInstruction("error", "boom").encode();
        ByteArrayInputStream in = new ByteArrayInputStream(unexpected);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RdpHost h = RdpHost.create("B", "ex", 3389, "alice", null, new PromptPasswordAuth());
        ConnectionParams p = ConnectionParams.from(h, "pw", 800, 600, null, null);
        assertThrows(java.io.IOException.class,
            () -> GuacdProxy.performGuacdHandshake(out, in, p));
    }

    @Test
    void tokenIsRequired_toConsume() {
        TokenVault v = new GuacdProxy().tokens();
        RdpHost h = RdpHost.create("B", "ex", 3389, "u", null, new PromptPasswordAuth());
        String t = v.issue(ConnectionParams.from(h, null, 100, 100, null, null));
        List<String> tokens = new ArrayList<>();
        tokens.add(t);
        assertNotNull(v.consume(tokens.get(0)));
        assertNull(v.consume(tokens.get(0)));
    }
}
```

- [ ] **Step 4: Run tests — expect 3 new PASS.**

- [ ] **Step 5: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/guacd/GuacdProxy.java \
        plugins/rdp/test/com/termlab/rdp/guacd/GuacdProxyTest.java \
        plugins/rdp/resources/META-INF/plugin.xml
git commit -m "feat(rdp): add WebSocket proxy bridging browser to guacd"
```

---

### Task 10: JCEF web assets

**Files:**
- Create: `plugins/rdp/resources/web/index.html`
- Create: `plugins/rdp/resources/web/client.js`
- Create: `plugins/rdp/resources/web/style.css`
- Create: `plugins/rdp/resources/web/guacamole-common-js/...` (vendored)

- [ ] **Step 1: Vendor `guacamole-common-js`**

Download `guacamole-common-js-1.5.5.zip` from the Apache Guacamole releases page and unpack:

```
mkdir -p plugins/rdp/resources/web/guacamole-common-js
curl -L -o /tmp/gcj.zip \
  "https://archive.apache.org/dist/guacamole/1.5.5/binary/guacamole-common-js-1.5.5.zip"
unzip -j /tmp/gcj.zip "guacamole-common-js-1.5.5/modules/*.js" \
  -d plugins/rdp/resources/web/guacamole-common-js/
```

Expected outcome: the `guacamole-common-js/` directory contains `all.js`, `blob-reader.js`, `blob-writer.js`, `client.js`, `keyboard.js`, `mouse.js`, `audio-player.js`, `video-player.js`, `input-stream.js`, `output-stream.js`, `tunnel.js`, `object.js`, etc.

Add a `VERSION` file documenting the pin:

```
echo "1.5.5" > plugins/rdp/resources/web/guacamole-common-js/VERSION
```

- [ ] **Step 2: `style.css`**

```css
html, body {
    margin: 0;
    padding: 0;
    height: 100%;
    background: #000;
    overflow: hidden;
    font-family: -apple-system, Segoe UI, Roboto, sans-serif;
}

#canvas-host {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
}

#canvas-host.dropping::after {
    content: "Drop to copy to remote";
    position: absolute;
    inset: 12px;
    border: 2px dashed #8fa8ff;
    color: #cfd8ff;
    background: rgba(20, 30, 60, 0.45);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 18px;
    border-radius: 6px;
    pointer-events: none;
}

#status-overlay {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #cbd5e1;
    font-size: 13px;
    pointer-events: none;
    background: #0a0a0a;
}
```

- [ ] **Step 3: `index.html`**

```html
<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <title>TermLab RDP</title>
  <link rel="stylesheet" href="style.css">
  <script src="guacamole-common-js/all.js"></script>
  <script defer src="client.js"></script>
</head>
<body>
  <div id="canvas-host"></div>
  <div id="status-overlay">Connecting…</div>
</body>
</html>
```

- [ ] **Step 4: `client.js`**

```js
(function () {
  "use strict";

  const overlay = document.getElementById("status-overlay");
  const host = document.getElementById("canvas-host");

  function setStatus(text) {
    if (!overlay) return;
    if (text == null) overlay.style.display = "none";
    else { overlay.style.display = "flex"; overlay.textContent = text; }
  }

  // The JVM-side JCEF tab calls window.termlabRdpStart(token, baseUrl)
  // once the token is minted and the proxy is ready.
  window.termlabRdpStart = function (token, baseUrl) {
    const tunnel = new Guacamole.WebSocketTunnel(baseUrl + "?token=" + token);
    const client = new Guacamole.Client(tunnel);

    // Mount the canvas.
    const display = client.getDisplay();
    host.appendChild(display.getElement());

    // Wire up input.
    const mouse = new Guacamole.Mouse(display.getElement());
    mouse.onmousedown = mouse.onmouseup = mouse.onmousemove = function (state) {
      client.sendMouseState(state);
    };
    const keyboard = new Guacamole.Keyboard(document);
    keyboard.onkeydown = keysym => client.sendKeyEvent(1, keysym);
    keyboard.onkeyup   = keysym => client.sendKeyEvent(0, keysym);

    // Clipboard: remote → local goes through a JVM-registered bridge.
    client.onclipboard = function (stream, mimetype) {
      if (!/^text/.test(mimetype)) return;
      const reader = new Guacamole.StringReader(stream);
      let acc = "";
      reader.ontext = t => acc += t;
      reader.onend = () => {
        if (window.termlabRdp && window.termlabRdpOnClipboard) {
          window.termlabRdpOnClipboard(acc);
        }
      };
    };

    // Status wiring.
    client.onstatechange = function (state) {
      switch (state) {
        case 0: setStatus("Connecting…"); break;
        case 1: setStatus("Waiting for server…"); break;
        case 2: setStatus("Authenticating…"); break;
        case 3: setStatus(null); break; // CONNECTED
        case 4: setStatus("Disconnecting…"); break;
        case 5: setStatus("Disconnected"); break;
      }
    };
    client.onerror = function (err) {
      setStatus("Error: " + err.message);
    };

    // Resize: when the container changes, tell the remote.
    function handleResize() {
      const rect = host.getBoundingClientRect();
      const w = Math.max(320, Math.floor(rect.width));
      const h = Math.max(240, Math.floor(rect.height));
      client.sendSize(w, h);
    }
    window.addEventListener("resize", handleResize);

    // Drag-drop: accept files and stream them into guacd as a file
    // instruction; guacd writes them into the shared drive.
    host.addEventListener("dragover", e => {
      e.preventDefault();
      host.classList.add("dropping");
    });
    host.addEventListener("dragleave", () => host.classList.remove("dropping"));
    host.addEventListener("drop", e => {
      e.preventDefault();
      host.classList.remove("dropping");
      const files = Array.from(e.dataTransfer.files || []);
      for (const f of files) uploadFile(client, f);
    });

    // Expose a JS hook for JVM-side local→remote clipboard writes.
    window.termlabRdpSetLocalClipboard = function (text) {
      const stream = client.createClipboardStream("text/plain");
      const writer = new Guacamole.StringWriter(stream);
      writer.sendText(text);
      writer.sendEnd();
    };

    client.connect();
  };

  function uploadFile(client, file) {
    const stream = client.createFileStream(file.type || "application/octet-stream", file.name);
    const writer = new Guacamole.BlobWriter(stream);
    writer.onack = () => { /* progress — ignored for MVP */ };
    writer.oncomplete = () => {
      if (window.termlabRdpOnUploadDone) window.termlabRdpOnUploadDone(file.name);
    };
    writer.sendBlob(file);
  }
})();
```

- [ ] **Step 5: Commit assets**

```
git add plugins/rdp/resources/web
git commit -m "feat(rdp): add vendored Guacamole JS client and JCEF page"
```

Note: if the vendored directory has many files, consider ensuring `.gitattributes` marks them as generated so diffs don't clutter reviews (optional).

---

### Task 11: Virtual file + virtual filesystem + editor provider

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/session/RdpSessionVirtualFile.java`
- Create: `plugins/rdp/src/com/termlab/rdp/session/RdpSessionVirtualFileSystem.java`
- Create: `plugins/rdp/src/com/termlab/rdp/session/RdpSessionEditorProvider.java`
- Create: `plugins/rdp/src/com/termlab/rdp/session/RdpSessionEditor.java`
- Modify: `plugins/rdp/resources/META-INF/plugin.xml` (register editor provider + VFS)

- [ ] **Step 1: `RdpSessionVirtualFileSystem.java`**

```java
package com.termlab.rdp.session;

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RdpSessionVirtualFileSystem extends DeprecatedVirtualFileSystem {

    public static final String PROTOCOL = "termlab-rdp";

    public static @NotNull RdpSessionVirtualFileSystem getInstance() {
        return (RdpSessionVirtualFileSystem)
            com.intellij.openapi.vfs.VirtualFileManager.getInstance().getFileSystem(PROTOCOL);
    }

    @Override public @NotNull String getProtocol() { return PROTOCOL; }
    @Override public @Nullable VirtualFile findFileByPath(@NotNull String path) { return null; }
    @Override public void refresh(boolean asynchronous) {}
    @Override public @Nullable VirtualFile refreshAndFindFileByPath(@NotNull String path) { return null; }
}
```

- [ ] **Step 2: `RdpSessionVirtualFile.java`**

```java
package com.termlab.rdp.session;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileSystem;
import com.termlab.rdp.model.RdpHost;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

/** Opaque virtual file representing one RDP session (host + unique instance). */
public final class RdpSessionVirtualFile extends VirtualFile {

    private final RdpHost host;
    private final UUID sessionId;
    private final String name;

    public RdpSessionVirtualFile(@NotNull RdpHost host) {
        this.host = host;
        this.sessionId = UUID.randomUUID();
        this.name = host.label() + ".rdp-session";
    }

    public @NotNull RdpHost host() { return host; }
    public @NotNull UUID sessionId() { return sessionId; }

    @Override public @NotNull String getName() { return name; }
    @Override public @NotNull VirtualFileSystem getFileSystem() { return RdpSessionVirtualFileSystem.getInstance(); }
    @Override public @NotNull String getPath() { return "/" + sessionId; }
    @Override public boolean isWritable() { return false; }
    @Override public boolean isDirectory() { return false; }
    @Override public boolean isValid() { return true; }
    @Override public @Nullable VirtualFile getParent() { return null; }
    @Override public VirtualFile[] getChildren() { return VirtualFile.EMPTY_ARRAY; }
    @Override public @NotNull java.io.OutputStream getOutputStream(Object requestor, long newModStamp, long newTimeStamp) {
        throw new UnsupportedOperationException();
    }
    @Override public byte @NotNull [] contentsToByteArray() { return new byte[0]; }
    @Override public long getTimeStamp() { return 0; }
    @Override public long getLength() { return 0; }
    @Override public void refresh(boolean asynchronous, boolean recursive, @Nullable Runnable postRunnable) {}
    @Override public @NotNull InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
}
```

- [ ] **Step 3: `RdpSessionEditor.java`**

```java
package com.termlab.rdp.session;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorLocation;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.beans.PropertyChangeListener;

public final class RdpSessionEditor extends UserDataHolderBase implements FileEditor {

    private final RdpSessionTab tab;
    private final RdpSessionVirtualFile file;

    public RdpSessionEditor(@NotNull Project project, @NotNull RdpSessionVirtualFile file) {
        this.file = file;
        this.tab = new RdpSessionTab(project, file);
    }

    @Override public @NotNull JComponent getComponent() { return tab; }
    @Override public @Nullable JComponent getPreferredFocusedComponent() { return tab.getPreferredFocus(); }
    @Override public @NotNull String getName() { return file.getName(); }
    @Override public @NotNull VirtualFile getFile() { return file; }
    @Override public @NotNull FileEditorState getState(@NotNull com.intellij.openapi.fileEditor.FileEditorStateLevel level) {
        return FileEditorState.INSTANCE;
    }
    @Override public void setState(@NotNull FileEditorState state) {}
    @Override public boolean isModified() { return false; }
    @Override public boolean isValid() { return true; }
    @Override public void addPropertyChangeListener(@NotNull PropertyChangeListener l) {}
    @Override public void removePropertyChangeListener(@NotNull PropertyChangeListener l) {}
    @Override public @Nullable FileEditorLocation getCurrentLocation() { return null; }
    @Override public void dispose() { tab.dispose(); }

    @Override public <T> @Nullable T getUserData(@NotNull Key<T> key) { return super.getUserData(key); }
    @Override public <T> void putUserData(@NotNull Key<T> key, @Nullable T value) { super.putUserData(key, value); }
}
```

- [ ] **Step 4: `RdpSessionEditorProvider.java`**

```java
package com.termlab.rdp.session;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public final class RdpSessionEditorProvider implements FileEditorProvider {

    private static final String ID = "termlab-rdp-session";

    @Override public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return file instanceof RdpSessionVirtualFile;
    }

    @Override public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        return new RdpSessionEditor(project, (RdpSessionVirtualFile) file);
    }

    @Override public @NotNull String getEditorTypeId() { return ID; }
    @Override public @NotNull FileEditorPolicy getPolicy() { return FileEditorPolicy.HIDE_DEFAULT_EDITOR; }
}
```

- [ ] **Step 5: Register in `plugin.xml`**

```xml
<virtualFileSystem implementationClass="com.termlab.rdp.session.RdpSessionVirtualFileSystem"
                   key="termlab-rdp" physical="false"/>
<fileEditorProvider implementation="com.termlab.rdp.session.RdpSessionEditorProvider"/>
```

- [ ] **Step 6: Build (no tests yet — UI code is exercised by the smoke test in Task 21)**

Run: `make termlab-build`
Expected: success.

- [ ] **Step 7: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/session \
        plugins/rdp/resources/META-INF/plugin.xml
git commit -m "feat(rdp): add session virtual file and editor provider"
```

---

### Task 12: `RdpSessionState`, `SharedFolderManager`, `RdpSessionRegistry`

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/session/RdpSessionState.java`
- Create: `plugins/rdp/src/com/termlab/rdp/session/SharedFolderManager.java`
- Create: `plugins/rdp/src/com/termlab/rdp/session/RdpSessionRegistry.java`
- Modify: `plugins/rdp/resources/META-INF/plugin.xml` (register `RdpSessionRegistry`)

- [ ] **Step 1: `RdpSessionState.java`**

```java
package com.termlab.rdp.session;

/** Lifecycle states surfaced in the RDP tab header. */
public enum RdpSessionState {
    /** Token issued, browser loading, proxy not yet opened. */
    CONNECTING,
    /** Browser WS is live and rendering frames. */
    CONNECTED,
    /** Network drop detected; auto-retry in progress. */
    RECONNECTING,
    /** Session ended (by user or by error). */
    DISCONNECTED,
    /** Unrecoverable failure; banner shown. */
    FAILED
}
```

- [ ] **Step 2: `SharedFolderManager.java`**

```java
package com.termlab.rdp.session;

import com.termlab.rdp.model.RdpHost;
import com.termlab.rdp.persistence.RdpPaths;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ensures a per-host shared folder exists on disk and that it contains a
 * friendly README explaining the two-way flow. guacd then mounts this
 * path as a Windows drive on the remote session.
 */
public final class SharedFolderManager {

    private static final Logger LOG = Logger.getInstance(SharedFolderManager.class);
    private static final String README =
        "This folder is shared with your RDP session as drive T:\n" +
        "\n" +
        "Drop files here on your Mac / Linux to make them visible inside\n" +
        "Windows as T:\\...\n" +
        "\n" +
        "Copy files into T:\\ inside Windows to pull them back to this\n" +
        "folder on your local machine.\n";

    private SharedFolderManager() {}

    public static @NotNull Path ensureFolderFor(@NotNull RdpHost host) throws IOException {
        Path root;
        if (host.sharedFolderPath() != null) {
            root = Path.of(host.sharedFolderPath());
        } else {
            root = RdpPaths.sharedRoot().resolve(sanitize(host.label()));
        }
        Files.createDirectories(root);
        Path readme = root.resolve("README.txt");
        if (!Files.exists(readme)) {
            Files.writeString(readme, README);
            try { readme.toFile().setReadOnly(); } catch (Exception ignored) {}
        }
        return root;
    }

    private static @NotNull String sanitize(@NotNull String label) {
        return label.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
```

- [ ] **Step 3: `RdpSessionRegistry.java`** — in-memory, single-use password handoff from the connect action to the tab.

```java
package com.termlab.rdp.session;

import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived password handoff between {@code ConnectToRdpHostAction}
 * and {@link RdpSessionTab}. Credentials live here only until the tab
 * consumes them via {@link #popPassword(UUID)}, then they're gone.
 */
public final class RdpSessionRegistry {

    public static @NotNull RdpSessionRegistry get() {
        return ApplicationManager.getApplication().getService(RdpSessionRegistry.class);
    }

    private final Map<UUID, String> pending = new ConcurrentHashMap<>();

    public void stashPassword(@NotNull UUID sessionId, @Nullable String password) {
        if (password != null) pending.put(sessionId, password);
    }

    public @Nullable String popPassword(@NotNull UUID sessionId) {
        return pending.remove(sessionId);
    }

    public int size() { return pending.size(); }
}
```

- [ ] **Step 4: Register in `plugin.xml`**

```xml
<applicationService serviceImplementation="com.termlab.rdp.session.RdpSessionRegistry"/>
```

- [ ] **Step 5: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/session/RdpSessionState.java \
        plugins/rdp/src/com/termlab/rdp/session/SharedFolderManager.java \
        plugins/rdp/src/com/termlab/rdp/session/RdpSessionRegistry.java \
        plugins/rdp/resources/META-INF/plugin.xml
git commit -m "feat(rdp): add session state, shared folder, and registry"
```

---

### Task 13: `RdpSessionTab` (JCEF hosting, header, footer)

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/session/RdpSessionTab.java`

This is the big UI class. It composes a JCEF browser with a header/footer strip, owns the session lifecycle, and wires a token through the proxy.

- [ ] **Step 1: `RdpSessionTab.java`**

```java
package com.termlab.rdp.session;

import com.termlab.rdp.guacd.ConnectionParams;
import com.termlab.rdp.guacd.GuacdProxy;
import com.termlab.rdp.model.PromptPasswordAuth;
import com.termlab.rdp.model.RdpHost;
import com.termlab.rdp.model.VaultAuth;
import com.termlab.rdp.persistence.RdpCertTrustStore;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.impl.content.ContentTabLabel;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

/**
 * Editor-tab contents for an active RDP session. Hosts a JCEF browser,
 * a header strip with status + controls, and a footer strip with
 * clipboard / shared-drive state.
 */
public final class RdpSessionTab extends JPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(RdpSessionTab.class);

    private final Project project;
    private final RdpSessionVirtualFile file;
    private final JBCefBrowser browser;
    private final JBLabel statusDot = new JBLabel("●");
    private final JBLabel identity = new JBLabel();
    private final JBLabel geometry = new JBLabel();
    private final JBLabel clipboardLabel = new JBLabel("clipboard: idle");
    private final JBLabel driveLabel = new JBLabel("shared drive: —");
    private volatile RdpSessionState state = RdpSessionState.CONNECTING;
    private Path sharedFolder;

    public RdpSessionTab(@NotNull Project project, @NotNull RdpSessionVirtualFile file) {
        super(new BorderLayout());
        this.project = project;
        this.file = file;
        if (!JBCefApp.isSupported()) {
            add(buildUnsupported(), BorderLayout.CENTER);
            this.browser = null;
            return;
        }
        this.browser = new JBCefBrowser();
        Disposer.register(this, browser);
        add(buildHeader(), BorderLayout.NORTH);
        add(browser.getComponent(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        ApplicationManager.getApplication().executeOnPooledThread(this::startSession);
    }

    public @Nullable JComponent getPreferredFocus() {
        return browser == null ? null : browser.getComponent();
    }

    @Override public void dispose() {
        // Registered children (browser, JS queries) dispose via Disposer.
    }

    private @NotNull JComponent buildUnsupported() {
        JLabel l = new JLabel("JCEF is not available in this runtime.", SwingConstants.CENTER);
        l.setForeground(JBColor.foreground());
        return l;
    }

    private @NotNull JComponent buildHeader() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        statusDot.setForeground(JBColor.GRAY);
        p.add(statusDot);
        identity.setText(file.host().username() + "@" + file.host().host() + ":" + file.host().port());
        p.add(identity);
        p.add(new JBLabel("•"));
        geometry.setText("--");
        p.add(geometry);

        p.add(Box.createHorizontalStrut(12));
        p.add(iconButton(AllIcons.Actions.Refresh, "Reconnect", e -> reconnect()));
        p.add(iconButton(AllIcons.Actions.MoveToWindow, "Fullscreen", e -> toggleFullscreen()));
        p.add(iconButton(AllIcons.Actions.Cancel, "Disconnect", e -> disconnect()));
        p.setBorder(JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0));
        return p;
    }

    private @NotNull JComponent buildFooter() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        p.add(clipboardLabel);
        p.add(new JBLabel("•"));
        p.add(driveLabel);
        p.setBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0));
        return p;
    }

    private JButton iconButton(Icon icon, String tooltip, java.awt.event.ActionListener listener) {
        JButton b = new JButton(icon);
        b.setToolTipText(tooltip);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.addActionListener(listener);
        return b;
    }

    private void startSession() {
        RdpHost host = file.host();
        try {
            sharedFolder = SharedFolderManager.ensureFolderFor(host);
            driveLabel.setText("shared drive: " + sharedFolder);

            String password = resolvePassword(host);
            // Placeholder for cert-trust: leaves pinned cert null at MVP;
            // RdpCertTrustStore integration arrives in Task 15.
            ConnectionParams params = ConnectionParams.from(
                host, password, 1600, 900, null, sharedFolder.toString());

            GuacdProxy proxy = GuacdProxy.get();
            String baseUrl = proxy.baseUrl();
            String token = proxy.tokens().issue(params);

            URL page = getClass().getClassLoader().getResource("web/index.html");
            if (page == null) throw new IOException("web/index.html not found on classpath");
            String html = java.nio.file.Files.readString(java.nio.file.Path.of(page.toURI()));

            SwingUtilities.invokeLater(() -> {
                browser.loadURL(page.toExternalForm());
                browser.getJBCefClient().addLoadHandler(new SimpleLoadHandler(token, baseUrl), browser.getCefBrowser());
            });
            setState(RdpSessionState.CONNECTED);
        } catch (Exception e) {
            LOG.warn("[rdp/session] failed to start session", e);
            setState(RdpSessionState.FAILED);
        }
    }

    private @Nullable String resolvePassword(@NotNull RdpHost host) {
        // ConnectToRdpHostAction resolves the password (prompt or vault)
        // and stashes it on RdpSessionRegistry under file.sessionId()
        // before openFile() fires. We pop it here — single-use.
        return com.termlab.rdp.session.RdpSessionRegistry.get().popPassword(file.sessionId());
    }

    private void setState(@NotNull RdpSessionState next) {
        this.state = next;
        SwingUtilities.invokeLater(() -> {
            switch (next) {
                case CONNECTING -> statusDot.setForeground(JBColor.GRAY);
                case CONNECTED -> statusDot.setForeground(JBColor.GREEN);
                case RECONNECTING -> statusDot.setForeground(JBColor.ORANGE);
                case DISCONNECTED -> statusDot.setForeground(JBColor.LIGHT_GRAY);
                case FAILED -> statusDot.setForeground(JBColor.RED);
            }
        });
    }

    private void reconnect() {
        setState(RdpSessionState.RECONNECTING);
        ApplicationManager.getApplication().executeOnPooledThread(this::startSession);
    }

    private void toggleFullscreen() {
        // MVP placeholder — full fullscreen (frameless window with
        // session detachment) is tracked as a phase-2 polish item.
    }

    private void disconnect() {
        setState(RdpSessionState.DISCONNECTED);
        if (browser != null) browser.loadHTML("<html><body style='background:#000'></body></html>");
    }

    /** Minimal load handler that fires termlabRdpStart once the page loads. */
    private final class SimpleLoadHandler extends org.cef.handler.CefLoadHandlerAdapter {
        private final String token;
        private final String baseUrl;

        SimpleLoadHandler(String token, String baseUrl) {
            this.token = token;
            this.baseUrl = baseUrl;
        }

        @Override public void onLoadEnd(org.cef.browser.CefBrowser b, org.cef.browser.CefFrame frame, int code) {
            if (!frame.isMain()) return;
            String js = "window.termlabRdpStart(\""
                + com.intellij.openapi.util.text.StringUtil.escapeStringCharacters(token) + "\", \""
                + com.intellij.openapi.util.text.StringUtil.escapeStringCharacters(baseUrl) + "\");";
            b.executeJavaScript(js, b.getURL(), 0);
        }
    }
}
```

Note: MVP fullscreen and password-prompt inlining are deferred; the file compiles and opens a working session when triggered from the connect action (Task 19), which pre-resolves vault credentials before the tab is created.

- [ ] **Step 2: Build & manual smoke**

Run: `make termlab-build`
Expected: success.

- [ ] **Step 3: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/session/RdpSessionTab.java
git commit -m "feat(rdp): add session tab with JCEF browser and state UI"
```

---

### Task 14: `RdpClipboardBridge`

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/session/RdpClipboardBridge.java`
- Modify: `plugins/rdp/src/com/termlab/rdp/session/RdpSessionTab.java` (wire it)

- [ ] **Step 1: `RdpClipboardBridge.java`**

```java
package com.termlab.rdp.session;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;

/**
 * Text-clipboard round-trip between the RDP session and the OS clipboard.
 * Remote → local: JS calls window.termlabRdpOnClipboard(text); the bridge
 * writes to CopyPasteManager. Local → remote: caller invokes
 * {@link #pushLocalClipboard(String)} from the EDT.
 */
public final class RdpClipboardBridge implements Disposable {

    private final JBCefBrowser browser;
    private final JBCefJSQuery query;

    public RdpClipboardBridge(@NotNull JBCefBrowser browser, @NotNull Disposable parent) {
        this.browser = browser;
        this.query = JBCefJSQuery.create(browser);
        Disposer.register(parent, this);
        Disposer.register(this, query);
        query.addHandler(text -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                CopyPasteManager.getInstance().setContents(new StringSelection(text));
            });
            return null;
        });
    }

    /** Injects the JS hook that forwards remote clipboard events here. */
    public void install() {
        String js =
            "window.termlabRdpOnClipboard = function (text) {" +
            query.inject("text") +
            "};";
        browser.getCefBrowser().executeJavaScript(js, browser.getCefBrowser().getURL(), 0);
    }

    public void pushLocalClipboard(@NotNull String text) {
        String escaped = com.intellij.openapi.util.text.StringUtil.escapeStringCharacters(text);
        browser.getCefBrowser().executeJavaScript(
            "window.termlabRdpSetLocalClipboard && window.termlabRdpSetLocalClipboard(\"" + escaped + "\")",
            browser.getCefBrowser().getURL(), 0);
    }

    public @org.jetbrains.annotations.Nullable String readLocalClipboard() {
        Transferable t = CopyPasteManager.getInstance().getContents();
        if (t == null || !t.isDataFlavorSupported(DataFlavor.stringFlavor)) return null;
        try { return (String) t.getTransferData(DataFlavor.stringFlavor); }
        catch (UnsupportedFlavorException | IOException e) { return null; }
    }

    @Override public void dispose() {}
}
```

- [ ] **Step 2: Wire in `RdpSessionTab`**

Inside `startSession()`, after the first `executeJavaScript(js, ...)` call in `SimpleLoadHandler.onLoadEnd`, add:

```java
SwingUtilities.invokeLater(() -> {
    RdpClipboardBridge bridge = new RdpClipboardBridge(browser, RdpSessionTab.this);
    bridge.install();
    clipboardLabel.setText("clipboard: synced");
});
```

- [ ] **Step 3: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/session/RdpClipboardBridge.java \
        plugins/rdp/src/com/termlab/rdp/session/RdpSessionTab.java
git commit -m "feat(rdp): add remote↔local clipboard bridge"
```

---

### Task 15: `RdpCertTrustPromptDialog`

First-connect and mismatch prompts for RDP server certificates.

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/ui/RdpCertTrustPromptDialog.java`

- [ ] **Step 1: `RdpCertTrustPromptDialog.java`**

```java
package com.termlab.rdp.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Blocking modal prompt used on first-connect (UNKNOWN) and mismatch
 * (MISMATCH) paths. The Trust action is explicit and is never the
 * default button on mismatch.
 */
public final class RdpCertTrustPromptDialog extends DialogWrapper {

    public enum Kind { FIRST_CONNECT, MISMATCH }

    public record Result(boolean trusted) {}

    private final Kind kind;
    private final String hostLabel;
    private final String subject;
    private final String fingerprint;
    private final @Nullable String previousFingerprint;
    private boolean trusted = false;

    public static @NotNull Result askFirstConnect(
        @Nullable Project project, @NotNull String hostLabel,
        @NotNull String subject, @NotNull String fingerprint
    ) {
        RdpCertTrustPromptDialog d = new RdpCertTrustPromptDialog(
            project, Kind.FIRST_CONNECT, hostLabel, subject, fingerprint, null);
        d.show();
        return new Result(d.trusted);
    }

    public static @NotNull Result askMismatch(
        @Nullable Project project, @NotNull String hostLabel,
        @NotNull String subject, @NotNull String fingerprint,
        @NotNull String previousFingerprint
    ) {
        RdpCertTrustPromptDialog d = new RdpCertTrustPromptDialog(
            project, Kind.MISMATCH, hostLabel, subject, fingerprint, previousFingerprint);
        d.show();
        return new Result(d.trusted);
    }

    private RdpCertTrustPromptDialog(@Nullable Project project, @NotNull Kind kind,
                                     @NotNull String hostLabel, @NotNull String subject,
                                     @NotNull String fingerprint, @Nullable String previousFingerprint) {
        super(project, false);
        this.kind = kind;
        this.hostLabel = hostLabel;
        this.subject = subject;
        this.fingerprint = fingerprint;
        this.previousFingerprint = previousFingerprint;
        setTitle(kind == Kind.FIRST_CONNECT ? "Trust RDP Server Certificate?" : "RDP Server Certificate Changed");
        init();
    }

    @Override protected @Nullable JComponent createCenterPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = JBUI.insets(4);
        c.gridx = 0; c.gridy = 0;
        p.add(new JBLabel(kind == Kind.FIRST_CONNECT
            ? "You are connecting to " + hostLabel + " for the first time."
            : "The certificate for " + hostLabel + " has changed since the last connection."), c);
        c.gridy++;
        p.add(new JBLabel("Subject:  " + subject), c);
        c.gridy++;
        p.add(new JBLabel("SHA-256:  " + fingerprint), c);
        if (previousFingerprint != null) {
            c.gridy++;
            p.add(new JBLabel("Previous: " + previousFingerprint), c);
        }
        c.gridy++;
        p.add(new JBLabel(kind == Kind.FIRST_CONNECT
            ? "Trust and connect, or abort?"
            : "This could indicate MITM. Trust new certificate, or abort?"), c);
        return p;
    }

    @Override protected Action @NotNull [] createActions() {
        Action trust = new AbstractAction(kind == Kind.FIRST_CONNECT ? "Trust and connect" : "Trust new") {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                trusted = true;
                close(OK_EXIT_CODE);
            }
        };
        Action abort = new AbstractAction("Abort") {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                trusted = false;
                close(CANCEL_EXIT_CODE);
            }
        };
        if (kind == Kind.MISMATCH) {
            abort.putValue(Action.DEFAULT, Boolean.TRUE);
            return new Action[] { trust, abort };
        }
        return new Action[] { trust, abort };
    }
}
```

- [ ] **Step 2: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/ui/RdpCertTrustPromptDialog.java
git commit -m "feat(rdp): add cert trust prompt dialog"
```

---

### Task 16: Tool window — cell renderer, main panel, factory

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/toolwindow/RdpHostCellRenderer.java`
- Create: `plugins/rdp/src/com/termlab/rdp/toolwindow/RdpHostsToolWindow.java`
- Create: `plugins/rdp/src/com/termlab/rdp/toolwindow/RdpHostsToolWindowFactory.java`
- Modify: `plugins/rdp/resources/META-INF/plugin.xml` (register tool window)

- [ ] **Step 1: `RdpHostCellRenderer.java`**

```java
package com.termlab.rdp.toolwindow;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.termlab.rdp.model.RdpHost;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public final class RdpHostCellRenderer extends JPanel implements ListCellRenderer<RdpHost> {

    private final JBLabel icon = new JBLabel(AllIcons.Nodes.Desktop);
    private final JBLabel label = new JBLabel();
    private final JBLabel subtitle = new JBLabel();

    public RdpHostCellRenderer() {
        super(new BorderLayout(8, 0));
        setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        add(icon, BorderLayout.WEST);
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.setOpaque(false);
        text.add(label);
        text.add(subtitle);
        add(text, BorderLayout.CENTER);
    }

    @Override public Component getListCellRendererComponent(
        JList<? extends RdpHost> list, RdpHost value, int index, boolean selected, boolean focused
    ) {
        label.setText(value.label());
        subtitle.setText(value.username() + "@" + value.host() + ":" + value.port());
        subtitle.setForeground(selected ? JBColor.foreground() : JBColor.GRAY);
        setBackground(selected ? list.getSelectionBackground() : list.getBackground());
        label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
        return this;
    }
}
```

- [ ] **Step 2: `RdpHostsToolWindow.java`** — modeled after `HostsToolWindow`.

```java
package com.termlab.rdp.toolwindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.termlab.rdp.actions.ConnectToRdpHostAction;
import com.termlab.rdp.model.RdpHost;
import com.termlab.rdp.persistence.RdpHostStore;
import com.termlab.rdp.ui.RdpHostEditDialog;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;

public final class RdpHostsToolWindow extends JPanel {

    private final Project project;
    private final RdpHostStore store;
    private final DefaultListModel<RdpHost> listModel = new DefaultListModel<>();
    private final JBList<RdpHost> list = new JBList<>(listModel);

    public RdpHostsToolWindow(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.store = ApplicationManager.getApplication().getService(RdpHostStore.class);

        list.setCellRenderer(new RdpHostCellRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    RdpHost sel = list.getSelectedValue();
                    if (sel != null) ConnectToRdpHostAction.run(project, sel);
                }
            }
        });
        list.addMouseListener(new PopupHandler() {
            @Override public void invokePopup(Component comp, int x, int y) {
                int i = list.locationToIndex(new Point(x, y));
                if (i >= 0) list.setSelectedIndex(i);
                showContextMenu(comp, x, y);
            }
        });

        add(buildToolbar().getComponent(), BorderLayout.NORTH);
        add(new JBScrollPane(list), BorderLayout.CENTER);

        refreshFromStore();
        store.addChangeListener(this::refreshFromStore);
    }

    private void refreshFromStore() {
        RdpHost previous = list.getSelectedValue();
        listModel.clear();
        for (RdpHost h : store.getHosts()) listModel.addElement(h);
        if (previous != null) {
            for (int i = 0; i < listModel.size(); i++) {
                if (listModel.get(i).id().equals(previous.id())) {
                    list.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    private @NotNull ActionToolbar buildToolbar() {
        DefaultActionGroup g = new DefaultActionGroup();
        g.add(new AddAction());
        g.add(new EditAction());
        g.add(new DeleteAction());
        g.addSeparator();
        g.add(new RefreshAction());
        ActionToolbar tb = ActionManager.getInstance().createActionToolbar("RdpHostsToolbar", g, true);
        tb.setTargetComponent(this);
        return tb;
    }

    private void showContextMenu(Component comp, int x, int y) {
        RdpHost sel = list.getSelectedValue();
        if (sel == null) return;
        JPopupMenu m = new JPopupMenu();
        JMenuItem connect = new JMenuItem("Connect");
        connect.addActionListener(e -> ConnectToRdpHostAction.run(project, sel));
        m.add(connect);
        JMenuItem edit = new JMenuItem("Edit…");
        edit.addActionListener(e -> editSelected());
        m.add(edit);
        JMenuItem dup = new JMenuItem("Duplicate");
        dup.addActionListener(e -> duplicateSelected());
        m.add(dup);
        m.addSeparator();
        JMenuItem del = new JMenuItem("Delete");
        del.addActionListener(e -> deleteSelected());
        m.add(del);
        m.show(comp, x, y);
    }

    private void addHost() {
        RdpHost created = RdpHostEditDialog.show(project, null);
        if (created == null) return;
        store.addHost(created);
        saveAndRefresh();
    }

    private void editSelected() {
        RdpHost sel = list.getSelectedValue();
        if (sel == null) return;
        RdpHost edited = RdpHostEditDialog.show(project, sel);
        if (edited == null) return;
        store.updateHost(edited);
        saveAndRefresh();
    }

    private void duplicateSelected() {
        RdpHost sel = list.getSelectedValue();
        if (sel == null) return;
        store.addHost(RdpHost.create(
            sel.label() + " (copy)", sel.host(), sel.port(), sel.username(), sel.domain(), sel.auth()));
        saveAndRefresh();
    }

    private void deleteSelected() {
        RdpHost sel = list.getSelectedValue();
        if (sel == null) return;
        int rc = Messages.showYesNoDialog(project,
            "Delete RDP host \"" + sel.label() + "\"?",
            "Delete RDP Host", Messages.getQuestionIcon());
        if (rc != Messages.YES) return;
        store.removeHost(sel.id());
        saveAndRefresh();
    }

    private void reloadFromDisk() {
        try { store.reload(); }
        catch (IOException e) {
            Messages.showErrorDialog(project, "Could not reload from disk:\n" + e.getMessage(), "Reload Failed");
            return;
        }
        refreshFromStore();
    }

    private void saveAndRefresh() {
        try { store.save(); }
        catch (IOException e) {
            Messages.showErrorDialog(project, "Could not save RDP hosts:\n" + e.getMessage(), "Save Failed");
        }
        refreshFromStore();
    }

    private final class AddAction extends AnAction {
        AddAction() { super("Add Host", "Add a new RDP host", AllIcons.General.Add); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { addHost(); }
    }
    private final class EditAction extends AnAction {
        EditAction() { super("Edit Host", "Edit the selected host", AllIcons.Actions.Edit); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { editSelected(); }
        @Override public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(list.getSelectedValue() != null);
        }
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    }
    private final class DeleteAction extends AnAction {
        DeleteAction() { super("Delete Host", "Delete the selected host", AllIcons.General.Remove); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { deleteSelected(); }
        @Override public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(list.getSelectedValue() != null);
        }
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    }
    private final class RefreshAction extends AnAction {
        RefreshAction() { super("Refresh", "Reload from disk", AllIcons.Actions.Refresh); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { reloadFromDisk(); }
    }
}
```

- [ ] **Step 3: `RdpHostsToolWindowFactory.java`**

```java
package com.termlab.rdp.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class RdpHostsToolWindowFactory implements ToolWindowFactory {
    @Override public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
        RdpHostsToolWindow panel = new RdpHostsToolWindow(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        window.getContentManager().addContent(content);
    }
}
```

- [ ] **Step 4: Register in `plugin.xml`**

```xml
<toolWindow id="Remote Desktops"
            anchor="right"
            icon="AllIcons.Nodes.Desktop"
            factoryClass="com.termlab.rdp.toolwindow.RdpHostsToolWindowFactory"/>
```

- [ ] **Step 5: Build**

Run: `make termlab-build`
Expected: compile errors only for the symbols still missing (`ConnectToRdpHostAction`, `RdpHostEditDialog`). These arrive in tasks 17 & 19. Stub them temporarily so this task compiles in isolation:

```java
// plugins/rdp/src/com/termlab/rdp/actions/ConnectToRdpHostAction.java — stub
package com.termlab.rdp.actions;

import com.intellij.openapi.project.Project;
import com.termlab.rdp.model.RdpHost;
import org.jetbrains.annotations.NotNull;

public final class ConnectToRdpHostAction {
    public static void run(@NotNull Project project, @NotNull RdpHost host) {
        throw new UnsupportedOperationException("wired in Task 19");
    }
}
```

```java
// plugins/rdp/src/com/termlab/rdp/ui/RdpHostEditDialog.java — stub
package com.termlab.rdp.ui;

import com.intellij.openapi.project.Project;
import com.termlab.rdp.model.RdpHost;
import org.jetbrains.annotations.Nullable;

public final class RdpHostEditDialog {
    public static @Nullable RdpHost show(@Nullable Project project, @Nullable RdpHost existing) {
        throw new UnsupportedOperationException("wired in Task 17");
    }
}
```

Build again; expect success.

- [ ] **Step 6: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/toolwindow \
        plugins/rdp/src/com/termlab/rdp/actions/ConnectToRdpHostAction.java \
        plugins/rdp/src/com/termlab/rdp/ui/RdpHostEditDialog.java \
        plugins/rdp/resources/META-INF/plugin.xml
git commit -m "feat(rdp): add Remote Desktops tool window"
```

---

### Task 17: `RdpHostEditDialog`

Replace the Task-16 stub with the real two-tab dialog (Connection + Display & Experience).

**Files:**
- Replace: `plugins/rdp/src/com/termlab/rdp/ui/RdpHostEditDialog.java`

- [ ] **Step 1: `RdpHostEditDialog.java`**

```java
package com.termlab.rdp.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.termlab.rdp.model.PromptPasswordAuth;
import com.termlab.rdp.model.RdpAuth;
import com.termlab.rdp.model.RdpHost;
import com.termlab.rdp.model.RdpSecurity;
import com.termlab.rdp.model.VaultAuth;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Two-tab modal dialog for creating or editing an {@link RdpHost}.
 * Returns the edited host, or {@code null} when the user cancels.
 */
public final class RdpHostEditDialog extends DialogWrapper {

    // Connection tab
    private final JBTextField labelField = new JBTextField();
    private final JBTextField hostField = new JBTextField();
    private final JBTextField portField = new JBTextField(String.valueOf(RdpHost.DEFAULT_PORT));
    private final JBTextField userField = new JBTextField();
    private final JBTextField domainField = new JBTextField();
    private final JRadioButton authVault = new JRadioButton("Vault");
    private final JRadioButton authPrompt = new JRadioButton("Prompt each connect");

    // Display tab
    private final JComboBox<RdpSecurity> securityCombo = new JComboBox<>(RdpSecurity.values());
    private final JComboBox<Integer> colorDepthCombo = new JComboBox<>(new Integer[]{16, 24, 32});
    private final JComboBox<String> sizeCombo = new JComboBox<>(new String[]{"Fit to tab", "1280×720", "1600×900", "1920×1080", "2560×1440"});
    private final JComboBox<Integer> dpiCombo = new JComboBox<>(new Integer[]{100, 125, 150, 175, 200});
    private final JBCheckBox multiMon = new JBCheckBox("Span all monitors");
    private final JBCheckBox clipboard = new JBCheckBox("Enable clipboard sharing", true);
    private final JBCheckBox drive = new JBCheckBox("Enable shared drive", true);
    private final JBTextField drivePath = new JBTextField();
    private final JBCheckBox sendCmdAsCtrl = new JBCheckBox("Send Cmd as Ctrl (macOS)");
    private final JBCheckBox ignoreCert = new JBCheckBox("Ignore certificate errors (insecure)");

    private final @Nullable RdpHost existing;
    private @Nullable RdpHost result;

    public static @Nullable RdpHost show(@Nullable Project project, @Nullable RdpHost existing) {
        RdpHostEditDialog d = new RdpHostEditDialog(project, existing);
        if (!d.showAndGet()) return null;
        return d.result;
    }

    private RdpHostEditDialog(@Nullable Project project, @Nullable RdpHost existing) {
        super(project, false);
        this.existing = existing;
        setTitle(existing == null ? "Add Remote Desktop Host" : "Edit Remote Desktop Host");
        init();
        populate();
    }

    @Override protected @Nullable JComponent createCenterPanel() {
        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("Connection", buildConnectionTab());
        tabs.addTab("Display & Experience", buildDisplayTab());
        tabs.setPreferredSize(JBUI.size(520, 380));
        return tabs;
    }

    private JComponent buildConnectionTab() {
        ButtonGroup grp = new ButtonGroup();
        grp.add(authVault);
        grp.add(authPrompt);
        authPrompt.setSelected(true);
        JPanel authRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        authRow.add(authVault);
        authRow.add(authPrompt);
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(new JBLabel("Label:"), labelField)
            .addLabeledComponent(new JBLabel("Host:"), hostField)
            .addLabeledComponent(new JBLabel("Port:"), portField)
            .addLabeledComponent(new JBLabel("Username:"), userField)
            .addLabeledComponent(new JBLabel("Domain:"), domainField)
            .addLabeledComponent(new JBLabel("Authentication:"), authRow)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
    }

    private JComponent buildDisplayTab() {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(new JBLabel("Security:"), securityCombo)
            .addLabeledComponent(new JBLabel("Color depth:"), colorDepthCombo)
            .addLabeledComponent(new JBLabel("Initial size:"), sizeCombo)
            .addLabeledComponent(new JBLabel("DPI scale:"), dpiCombo)
            .addComponent(multiMon)
            .addComponent(clipboard)
            .addComponent(drive)
            .addLabeledComponent(new JBLabel("Shared folder:"), drivePath)
            .addComponent(sendCmdAsCtrl)
            .addComponent(ignoreCert)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
    }

    private void populate() {
        if (existing == null) {
            securityCombo.setSelectedItem(RdpSecurity.NLA);
            colorDepthCombo.setSelectedItem(RdpHost.DEFAULT_COLOR_DEPTH);
            dpiCombo.setSelectedItem(RdpHost.DEFAULT_DPI_SCALE);
            sizeCombo.setSelectedIndex(0);
            return;
        }
        labelField.setText(existing.label());
        hostField.setText(existing.host());
        portField.setText(String.valueOf(existing.port()));
        userField.setText(existing.username());
        if (existing.domain() != null) domainField.setText(existing.domain());
        switch (existing.auth()) {
            case VaultAuth v -> authVault.setSelected(true);
            case PromptPasswordAuth p -> authPrompt.setSelected(true);
        }
        securityCombo.setSelectedItem(existing.security());
        colorDepthCombo.setSelectedItem(existing.colorDepth());
        dpiCombo.setSelectedItem(existing.dpiScale());
        if (existing.initialWidth() != null && existing.initialHeight() != null) {
            String wh = existing.initialWidth() + "×" + existing.initialHeight();
            for (int i = 0; i < sizeCombo.getItemCount(); i++) {
                if (sizeCombo.getItemAt(i).equals(wh)) { sizeCombo.setSelectedIndex(i); break; }
            }
        } else {
            sizeCombo.setSelectedIndex(0);
        }
        multiMon.setSelected(existing.multiMonitor());
        clipboard.setSelected(existing.enableClipboard());
        drive.setSelected(existing.enableDriveShare());
        if (existing.sharedFolderPath() != null) drivePath.setText(existing.sharedFolderPath());
        sendCmdAsCtrl.setSelected(existing.sendCmdAsCtrl());
        ignoreCert.setSelected(existing.ignoreCertErrors());
    }

    @Override protected void doOKAction() {
        int port;
        try { port = Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException nfe) {
            setErrorText("Port must be a number", portField);
            return;
        }
        if (labelField.getText().trim().isEmpty()) { setErrorText("Label is required", labelField); return; }
        if (hostField.getText().trim().isEmpty()) { setErrorText("Host is required", hostField); return; }
        if (userField.getText().trim().isEmpty()) { setErrorText("Username is required", userField); return; }

        RdpAuth auth = authVault.isSelected() ? new VaultAuth(null) : new PromptPasswordAuth();
        Integer[] wh = parseSize();

        UUID id = existing != null ? existing.id() : UUID.randomUUID();
        Instant created = existing != null ? existing.createdAt() : Instant.now();
        result = new RdpHost(
            id,
            labelField.getText().trim(),
            hostField.getText().trim(),
            port,
            userField.getText().trim(),
            nullIfBlank(domainField.getText()),
            auth,
            (RdpSecurity) securityCombo.getSelectedItem(),
            (Integer) colorDepthCombo.getSelectedItem(),
            wh[0], wh[1],
            (Integer) dpiCombo.getSelectedItem(),
            multiMon.isSelected(),
            clipboard.isSelected(),
            drive.isSelected(),
            nullIfBlank(drivePath.getText()),
            sendCmdAsCtrl.isSelected(),
            ignoreCert.isSelected(),
            created,
            Instant.now()
        );
        super.doOKAction();
    }

    private Integer @NotNull [] parseSize() {
        String sel = String.valueOf(sizeCombo.getSelectedItem());
        if (sel == null || sel.startsWith("Fit")) return new Integer[]{null, null};
        String[] parts = sel.split("×");
        try {
            return new Integer[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (Exception e) { return new Integer[]{null, null}; }
    }

    private static @Nullable String nullIfBlank(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
```

- [ ] **Step 2: Build**

Run: `make termlab-build`
Expected: success.

- [ ] **Step 3: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/ui/RdpHostEditDialog.java
git commit -m "feat(rdp): add host edit dialog with connection and display tabs"
```

---

### Task 18: `RdpHostPicker`

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/ui/RdpHostPicker.java`

- [ ] **Step 1: `RdpHostPicker.java`**

```java
package com.termlab.rdp.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.termlab.rdp.model.RdpHost;
import com.termlab.rdp.toolwindow.RdpHostCellRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public final class RdpHostPicker extends DialogWrapper {

    private final JBList<RdpHost> list;
    private @Nullable RdpHost chosen;

    public static @Nullable RdpHost pick(@Nullable Project project, @NotNull List<RdpHost> hosts) {
        if (hosts.isEmpty()) return null;
        RdpHostPicker p = new RdpHostPicker(project, hosts);
        if (!p.showAndGet()) return null;
        return p.chosen;
    }

    private RdpHostPicker(@Nullable Project project, @NotNull List<RdpHost> hosts) {
        super(project, false);
        setTitle("Select a Remote Desktop Host");
        DefaultListModel<RdpHost> model = new DefaultListModel<>();
        for (RdpHost h : hosts) model.addElement(h);
        list = new JBList<>(model);
        list.setCellRenderer(new RdpHostCellRenderer());
        list.setSelectedIndex(0);
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) { chosen = list.getSelectedValue(); close(OK_EXIT_CODE); }
            }
        });
        init();
    }

    @Override protected @Nullable JComponent createCenterPanel() {
        JBScrollPane sp = new JBScrollPane(list);
        sp.setPreferredSize(new Dimension(400, 300));
        return sp;
    }

    @Override protected void doOKAction() {
        chosen = list.getSelectedValue();
        super.doOKAction();
    }
}
```

- [ ] **Step 2: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/ui/RdpHostPicker.java
git commit -m "feat(rdp): add host picker dialog"
```

---

### Task 19: Actions

Replace the Task-16 stub for `ConnectToRdpHostAction` with the real entry point and add the rest.

**Files:**
- Replace: `plugins/rdp/src/com/termlab/rdp/actions/ConnectToRdpHostAction.java`
- Create: `plugins/rdp/src/com/termlab/rdp/actions/NewRdpSessionAction.java`
- Create: `plugins/rdp/src/com/termlab/rdp/actions/AddRdpHostAction.java`
- Create: `plugins/rdp/src/com/termlab/rdp/actions/OpenRdpSharedFolderAction.java`
- Create: `plugins/rdp/src/com/termlab/rdp/actions/ReconnectRdpAction.java`
- Create: `plugins/rdp/src/com/termlab/rdp/actions/DisconnectRdpAction.java`
- Create: `plugins/rdp/src/com/termlab/rdp/actions/SendCtrlAltDelAction.java`

- [ ] **Step 1: `ConnectToRdpHostAction.java`** — resolves the password (vault lookup or prompt), stashes it in `RdpSessionRegistry`, then opens the session tab.

```java
package com.termlab.rdp.actions;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.termlab.rdp.model.PromptPasswordAuth;
import com.termlab.rdp.model.RdpHost;
import com.termlab.rdp.model.VaultAuth;
import com.termlab.rdp.session.RdpSessionRegistry;
import com.termlab.rdp.session.RdpSessionVirtualFile;
import com.termlab.sdk.CredentialProvider;
import com.termlab.sdk.CredentialProvider.Credential;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Non-action helper invoked by the tool window, palette, and
 * {@link NewRdpSessionAction}. Resolves the host's password, stashes it
 * in {@link RdpSessionRegistry}, then opens an editor tab backed by a
 * new {@link RdpSessionVirtualFile}.
 */
public final class ConnectToRdpHostAction {

    private static final Logger LOG = Logger.getInstance(ConnectToRdpHostAction.class);
    private static final ExtensionPointName<CredentialProvider> CREDENTIAL_EP =
        ExtensionPointName.create("com.termlab.core.credentialProvider");

    private ConnectToRdpHostAction() {}

    public static void run(@NotNull Project project, @NotNull RdpHost host) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                String password = resolvePassword(project, host);
                if (password == null && host.auth() instanceof VaultAuth) {
                    // Vault lookup returned nothing and the user didn't
                    // pick an alternative — abort.
                    return;
                }
                RdpSessionVirtualFile file = new RdpSessionVirtualFile(host);
                RdpSessionRegistry.get().stashPassword(file.sessionId(), password);
                FileEditorManager.getInstance(project).openFile(file, true);
            } catch (Exception e) {
                LOG.warn("[rdp] connect failed", e);
                Messages.showErrorDialog(project,
                    "Could not open RDP session:\n" + e.getMessage(),
                    "RDP Connection Failed");
            }
        });
    }

    private static @Nullable String resolvePassword(@NotNull Project project, @NotNull RdpHost host) {
        return switch (host.auth()) {
            case PromptPasswordAuth p -> promptPassword(project, host);
            case VaultAuth v -> resolveFromVault(project, host, v);
        };
    }

    private static @Nullable String promptPassword(@NotNull Project project, @NotNull RdpHost host) {
        JPasswordField field = new JPasswordField(24);
        JPanel body = FormBuilder.createFormBuilder()
            .addLabeledComponent(new JBLabel("Password for " + host.username() + "@" + host.host() + ":"), field)
            .getPanel();
        int rc = JOptionPane.showConfirmDialog(null, body, "RDP Password",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (rc != JOptionPane.OK_OPTION) return null;
        char[] chars = field.getPassword();
        String pw = new String(chars);
        java.util.Arrays.fill(chars, '\0');
        return pw;
    }

    private static @Nullable String resolveFromVault(@NotNull Project project,
                                                     @NotNull RdpHost host,
                                                     @NotNull VaultAuth auth) {
        if (auth.credentialId() == null) {
            Credential c = promptForCredential();
            return extractPassword(c);
        }
        for (CredentialProvider provider : CREDENTIAL_EP.getExtensionList()) {
            if (!provider.ensureAvailable()) continue;
            Credential c = provider.getCredential(auth.credentialId());
            if (c != null) return extractPassword(c);
        }
        Messages.showErrorDialog(project,
            "Could not resolve credential from the vault for " + host.label() + ".",
            "RDP Credential Resolution Failed");
        return null;
    }

    private static @Nullable Credential promptForCredential() {
        for (CredentialProvider provider : CREDENTIAL_EP.getExtensionList()) {
            if (!provider.ensureAvailable()) continue;
            Credential c = provider.promptForCredential();
            if (c != null) return c;
        }
        return null;
    }

    private static @Nullable String extractPassword(@Nullable Credential c) {
        if (c == null || c.password() == null) return null;
        try { return new String(c.password()); }
        finally { c.destroy(); }
    }
}
```

Vault resolution is now in-plan for MVP; `RdpSessionTab.resolvePassword` reads it back out of the registry.

- [ ] **Step 2: `NewRdpSessionAction.java`**

```java
package com.termlab.rdp.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.termlab.rdp.model.RdpHost;
import com.termlab.rdp.persistence.RdpHostStore;
import com.termlab.rdp.ui.RdpHostPicker;
import org.jetbrains.annotations.NotNull;

public final class NewRdpSessionAction extends AnAction {

    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        RdpHostStore store = ApplicationManager.getApplication().getService(RdpHostStore.class);
        if (store.getHosts().isEmpty()) {
            Messages.showInfoMessage(project,
                "No RDP hosts saved yet. Add one from the Remote Desktops tool window.",
                "No RDP Hosts");
            return;
        }
        RdpHost chosen = RdpHostPicker.pick(project, store.getHosts());
        if (chosen != null) ConnectToRdpHostAction.run(project, chosen);
    }
}
```

- [ ] **Step 3: `AddRdpHostAction.java`**

```java
package com.termlab.rdp.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.Messages;
import com.termlab.rdp.model.RdpHost;
import com.termlab.rdp.persistence.RdpHostStore;
import com.termlab.rdp.ui.RdpHostEditDialog;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public final class AddRdpHostAction extends AnAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        RdpHost created = RdpHostEditDialog.show(e.getProject(), null);
        if (created == null) return;
        RdpHostStore store = ApplicationManager.getApplication().getService(RdpHostStore.class);
        store.addHost(created);
        try { store.save(); }
        catch (IOException ex) {
            Messages.showErrorDialog(e.getProject(),
                "Could not save RDP hosts:\n" + ex.getMessage(), "Save Failed");
        }
    }
}
```

- [ ] **Step 4: `OpenRdpSharedFolderAction.java`**

```java
package com.termlab.rdp.actions;

import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.termlab.rdp.session.RdpSessionVirtualFile;
import com.termlab.rdp.session.SharedFolderManager;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public final class OpenRdpSharedFolderAction extends AnAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        VirtualFile current = FileEditorManager.getInstance(project).getSelectedFiles().length > 0
            ? FileEditorManager.getInstance(project).getSelectedFiles()[0] : null;
        if (!(current instanceof RdpSessionVirtualFile session)) return;
        try {
            Path shared = SharedFolderManager.ensureFolderFor(session.host());
            RevealFileAction.openDirectory(shared.toFile());
        } catch (Exception ignored) {}
    }
}
```

- [ ] **Step 5: `ReconnectRdpAction.java` / `DisconnectRdpAction.java` / `SendCtrlAltDelAction.java`**

```java
package com.termlab.rdp.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

/** Placeholder: context action on the RDP tab; real wiring in Task 21. */
public final class ReconnectRdpAction extends AnAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        // Reconnect is triggered by the tab's own header button in MVP.
        // This action exists so the palette can surface it; forwarding is
        // added once the tab exposes a service-locator for the current
        // session (tracked as a follow-up).
    }
}
```

```java
package com.termlab.rdp.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.termlab.rdp.session.RdpSessionVirtualFile;
import org.jetbrains.annotations.NotNull;

public final class DisconnectRdpAction extends AnAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        FileEditorManager fm = FileEditorManager.getInstance(project);
        var files = fm.getSelectedFiles();
        for (var f : files) {
            if (f instanceof RdpSessionVirtualFile) fm.closeFile(f);
        }
    }
}
```

```java
package com.termlab.rdp.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

/** Sends Ctrl+Alt+Del to the active RDP tab. Wired in Task 21. */
public final class SendCtrlAltDelAction extends AnAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        // Sends a scripted key sequence through the session's JBCefBrowser.
        // Minimal MVP implementation: no-op; real wiring lands alongside
        // the tab's session-locator service.
    }
}
```

- [ ] **Step 6: Build**

Run: `make termlab-build`
Expected: success.

- [ ] **Step 7: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/actions
git commit -m "feat(rdp): add RDP actions (connect/new session/add/open folder/etc.)"
```

---

### Task 20: `RdpHostsSearchEverywhereContributor`

**Files:**
- Create: `plugins/rdp/src/com/termlab/rdp/palette/RdpHostsSearchEverywhereContributor.java`
- Modify: `plugins/rdp/resources/META-INF/plugin.xml` (register contributor)

- [ ] **Step 1: `RdpHostsSearchEverywhereContributor.java`** — mirror `HostsSearchEverywhereContributor`.

```java
package com.termlab.rdp.palette;

import com.intellij.ide.actions.searcheverywhere.AbstractGotoSEContributor;
import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.util.Processor;
import com.termlab.rdp.actions.ConnectToRdpHostAction;
import com.termlab.rdp.model.RdpHost;
import com.termlab.rdp.persistence.RdpHostStore;
import com.termlab.rdp.toolwindow.RdpHostCellRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Locale;

public final class RdpHostsSearchEverywhereContributor implements SearchEverywhereContributor<RdpHost> {

    private final Project project;

    public RdpHostsSearchEverywhereContributor(@NotNull AnActionEvent e) {
        this.project = e.getProject();
    }

    @Override public @NotNull String getSearchProviderId() { return "RdpHostsContributor"; }
    @Override public @NotNull String getGroupName() { return "Remote Desktops"; }
    @Override public int getSortWeight() { return 500; }
    @Override public boolean showInFindResults() { return false; }

    @Override public void fetchElements(@NotNull String pattern,
                                        @NotNull ProgressIndicator indicator,
                                        @NotNull Processor<? super RdpHost> consumer) {
        String p = pattern.toLowerCase(Locale.ROOT);
        var store = ApplicationManager.getApplication().getService(RdpHostStore.class);
        for (RdpHost h : store.getHosts()) {
            if (indicator.isCanceled()) break;
            if (p.isEmpty()
                || h.label().toLowerCase(Locale.ROOT).contains(p)
                || h.host().toLowerCase(Locale.ROOT).contains(p)
                || h.username().toLowerCase(Locale.ROOT).contains(p)) {
                if (!consumer.process(h)) break;
            }
        }
    }

    @Override public boolean processSelectedItem(@NotNull RdpHost selected, int modifiers, @NotNull String text) {
        if (project == null) return false;
        ConnectToRdpHostAction.run(project, selected);
        return true;
    }

    @Override public @NotNull ListCellRenderer<? super RdpHost> getElementsRenderer() {
        return new RdpHostCellRenderer();
    }

    @Override public @Nullable String getDataForItem(@NotNull RdpHost element, @NotNull String dataId) {
        return null;
    }

    public static final class Factory implements SearchEverywhereContributorFactory<RdpHost> {
        @Override public @NotNull SearchEverywhereContributor<RdpHost> createContributor(@NotNull AnActionEvent e) {
            return new RdpHostsSearchEverywhereContributor(e);
        }
    }
}
```

- [ ] **Step 2: Register in `plugin.xml`**

```xml
<searchEverywhereContributor
    implementation="com.termlab.rdp.palette.RdpHostsSearchEverywhereContributor$Factory"/>
```

- [ ] **Step 3: Commit**

```
git add plugins/rdp/src/com/termlab/rdp/palette \
        plugins/rdp/resources/META-INF/plugin.xml
git commit -m "feat(rdp): add Search Everywhere contributor for RDP hosts"
```

---

### Task 21: Final `plugin.xml` wiring + smoke test + README user guide

**Files:**
- Modify: `plugins/rdp/resources/META-INF/plugin.xml` (register actions with shortcuts)
- Modify: `README.md` (add shortcut to the "Core Product Shortcuts" table)

- [ ] **Step 1: Final `plugin.xml`**

Append the actions block and confirm the full file reads:

```xml
<idea-plugin>
    <id>com.termlab.rdp</id>
    <name>TermLab Remote Desktop</name>
    <vendor>TermLab</vendor>
    <description>Embedded RDP (Windows Remote Desktop) sessions with
    clipboard sync and shared-drive drag-and-drop, rendered via
    Apache Guacamole.</description>

    <depends>com.termlab.core</depends>
    <depends>com.termlab.vault</depends>
    <depends>com.intellij.modules.platform</depends>

    <extensions defaultExtensionNs="com.intellij">
        <applicationService serviceImplementation="com.termlab.rdp.persistence.RdpHostStore"/>
        <applicationService serviceImplementation="com.termlab.rdp.persistence.RdpCertTrustStore"/>
        <applicationService serviceImplementation="com.termlab.rdp.guacd.GuacdSupervisor"/>
        <applicationService serviceImplementation="com.termlab.rdp.guacd.GuacdProxy"/>

        <virtualFileSystem implementationClass="com.termlab.rdp.session.RdpSessionVirtualFileSystem"
                           key="termlab-rdp" physical="false"/>
        <fileEditorProvider implementation="com.termlab.rdp.session.RdpSessionEditorProvider"/>

        <toolWindow id="Remote Desktops"
                    anchor="right"
                    icon="AllIcons.Nodes.Desktop"
                    factoryClass="com.termlab.rdp.toolwindow.RdpHostsToolWindowFactory"/>

        <searchEverywhereContributor
            implementation="com.termlab.rdp.palette.RdpHostsSearchEverywhereContributor$Factory"/>
    </extensions>

    <actions>
        <action id="TermLab.Rdp.NewSession"
                class="com.termlab.rdp.actions.NewRdpSessionAction"
                text="New Remote Desktop Session…"
                description="Pick an RDP host and connect in a new tab">
            <keyboard-shortcut keymap="$default" first-keystroke="meta shift L"/>
            <keyboard-shortcut keymap="$default" first-keystroke="ctrl shift L"/>
        </action>
        <action id="TermLab.Rdp.AddHost"
                class="com.termlab.rdp.actions.AddRdpHostAction"
                text="Add Remote Desktop Host…"
                description="Add a new RDP host"/>
        <action id="TermLab.Rdp.OpenSharedFolder"
                class="com.termlab.rdp.actions.OpenRdpSharedFolderAction"
                text="Open RDP Shared Folder"
                description="Open the shared folder for the active RDP session"/>
        <action id="TermLab.Rdp.Reconnect"
                class="com.termlab.rdp.actions.ReconnectRdpAction"
                text="Reconnect RDP Session"/>
        <action id="TermLab.Rdp.Disconnect"
                class="com.termlab.rdp.actions.DisconnectRdpAction"
                text="Disconnect RDP Session"/>
        <action id="TermLab.Rdp.SendCtrlAltDel"
                class="com.termlab.rdp.actions.SendCtrlAltDelAction"
                text="Send Ctrl+Alt+Del"/>
    </actions>
</idea-plugin>
```

- [ ] **Step 2: Update `README.md` shortcut table**

Add in the appropriate alphabetical position:

```
| New Remote Desktop Session | `Cmd+Shift+L` | `Ctrl+Shift+L` |
```

- [ ] **Step 3: End-to-end smoke test (manual)**

1. Ensure `guacd` is installed: `scripts/resolve-guacd.sh` prints a valid path.
2. Build & run: `make termlab`
3. Open the Remote Desktops tool window on the right.
4. Click `+` and create a host: host `<some windows box>`, port 3389, username `<user>`, domain optional, auth = "Prompt each connect".
5. Double-click the host. Expect:
   - Token issued; JCEF browser loads `web/index.html`.
   - Browser opens a WS to `127.0.0.1:<proxyPort>/tunnel?token=…`.
   - `GuacdSupervisor` starts `guacd` on a loopback port; log shows `[rdp/guacd] started`.
   - `GuacdProxy` performs the handshake; server starts rendering frames.
   - Status dot flips grey → green; header shows `user@host:port`.
6. Copy text on the Windows side → paste locally: text appears.
7. Drop a local file on the canvas: file appears in `~/TermLab/RDP Shared/<label>/` on the remote `T:\` drive.
8. Close the tab — session disconnects; guacd stays alive for subsequent connects.
9. Exit TermLab — guacd process terminates (`ps` verifies).

If any step fails, investigate `idea.log` under `~/Library/Logs/TermLab` (macOS) or the workspace equivalent, filtering for `[rdp]`.

- [ ] **Step 4: Commit**

```
git add plugins/rdp/resources/META-INF/plugin.xml README.md
git commit -m "feat(rdp): finalize plugin.xml wiring and document shortcut"
```

- [ ] **Step 5: Final tag-free branch commit review**

```
git log --oneline $(git merge-base HEAD main)..HEAD
```

Expected: a clean series of `feat(rdp): …` commits from Task 1 through Task 21.

---

## Known limitations carried forward (tracked as phase-2)

These are intentionally deferred from this plan and documented in the spec:

- `Reconnect` / `SendCtrlAltDel` palette actions forwarding to the active tab (MVP exposes them in the header; palette stubs register but don't forward yet).
- True fullscreen (frameless window) toggle.
- Guacamole `clipboard` file-list round-trip (text works in MVP).
- Cert trust integration in the connect path (`RdpCertTrustPromptDialog` exists; wiring into the handshake is trivial but deferred from MVP).
- Auto-reconnect on mid-session network drop (manual reconnect works).
- Opt-in integration tests against a live Windows target.




