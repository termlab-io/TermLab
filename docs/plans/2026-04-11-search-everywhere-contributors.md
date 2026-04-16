# Search Everywhere Contributors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make TermLab's SSH hosts and vault entries reachable from the command palette (Cmd+Shift+P) by converting both plugins to real `SearchEverywhereContributor`s, and retire the dead `CommandPaletteContributor` SDK interface.

**Architecture:** Each plugin implements IntelliJ's `SearchEverywhereContributor` directly (same pattern as the existing `TerminalPaletteContributor`). Core's `TermLabTabsCustomizationStrategy` allowlists the new tab IDs. Core's `commandPaletteContributor` extension point and the SDK's `CommandPaletteContributor` + `PaletteItem` files get deleted.

**Tech Stack:** IntelliJ Platform API (`SearchEverywhereContributor`, `SearchEverywhereContributorFactory`, `AnAction`), Java 21.

**Reference spec:** `docs/specs/2026-04-11-search-everywhere-contributors-design.md`

---

## File Structure

**New files:**
- `plugins/ssh/src/com/termlab/ssh/palette/HostsSearchEverywhereContributor.java` — `SearchEverywhereContributor<SshHost>` + nested `Factory`
- `plugins/vault/src/com/termlab/vault/palette/VaultSearchEverywhereContributor.java` — `SearchEverywhereContributor<Object>` (tab holds both `VaultAccount` and `VaultKey`) + nested `Factory`
- `plugins/vault/src/com/termlab/vault/actions/LockVaultAction.java` — `AnAction` that locks the vault
- `plugins/vault/src/com/termlab/vault/actions/GenerateSshKeyAction.java` — `AnAction` that opens `KeyGenDialog`

**Modified files:**
- `plugins/ssh/resources/META-INF/plugin.xml` — add `<searchEverywhereContributor>`, remove `<commandPaletteContributor>`
- `plugins/vault/resources/META-INF/plugin.xml` — add `<searchEverywhereContributor>` + two `<action>` entries, remove `<commandPaletteContributor>`
- `core/src/com/termlab/core/palette/TermLabTabsCustomizationStrategy.java` — add `"TermLabHosts"` and `"TermLabVault"` to `ALLOWED_TAB_IDS`
- `core/resources/META-INF/plugin.xml` — remove the `<extensionPoint name="commandPaletteContributor">` declaration

**Deleted files:**
- `plugins/ssh/src/com/termlab/ssh/palette/HostsPaletteContributor.java`
- `plugins/vault/src/com/termlab/vault/palette/VaultPaletteContributor.java`
- `sdk/src/com/termlab/sdk/CommandPaletteContributor.java`
- `sdk/src/com/termlab/sdk/PaletteItem.java`

**Unchanged (confirmed):**
- `TerminalPaletteContributor` — the reference pattern, no edits
- `TermLabSearchEverywhereCustomizer` — blocklist of unwanted built-in contributors, separate concern
- `HostStore`, `LockManager`, `Vault`, `VaultAccount`, `VaultKey` — model layer untouched
- `HostCellRenderer` — reused by the new SSH contributor via a wrapping renderer
- `ConnectToHostAction` — the connect path, called from the new contributor

---

## Build & test commands

From `/Users/dustin/projects/termlab_workbench`:

```bash
# Full product build (catches cross-module breakage):
make termlab-build

# SSH plugin test suite:
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/ssh:ssh_test_runner

# Vault plugin test suite:
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/vault:vault_test_runner
```

Baseline: 87 SSH tests passing + whatever the vault baseline is (unchanged by this plan). All existing tests must still pass after each task.

---

## Task 1: SSH plugin — `HostsSearchEverywhereContributor`

The SSH plugin gets a real contributor. The old dead `HostsPaletteContributor` and its `<commandPaletteContributor>` registration are removed in the same commit. After this commit the SDK's dead `CommandPaletteContributor` interface is still present but nothing in the SSH plugin touches it; vault's still-broken `VaultPaletteContributor` continues to reference it — that cleanup is Task 2.

**Files:**
- Create: `plugins/ssh/src/com/termlab/ssh/palette/HostsSearchEverywhereContributor.java`
- Modify: `plugins/ssh/resources/META-INF/plugin.xml`
- Delete: `plugins/ssh/src/com/termlab/ssh/palette/HostsPaletteContributor.java`

- [ ] **Step 1: Create `HostsSearchEverywhereContributor.java`**

Create `plugins/ssh/src/com/termlab/ssh/palette/HostsSearchEverywhereContributor.java` with this content:

```java
package com.termlab.ssh.palette;

import com.termlab.ssh.actions.ConnectToHostAction;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.SshHost;
import com.termlab.ssh.toolwindow.HostCellRenderer;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.Component;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Exposes saved {@link SshHost} entries in the TermLab command palette
 * (Cmd+Shift+P → Hosts tab). Selecting a host dispatches
 * {@link ConnectToHostAction#run(Project, SshHost)}, which opens a new
 * editor tab connected to the host — the same path the tool window's
 * double-click uses.
 *
 * <p>Structured like {@code TerminalPaletteContributor}: the contributor
 * itself holds the project reference and reads {@link HostStore} fresh
 * on every {@code fetchElements} call, so tool-window adds/removes show
 * up in the palette immediately.
 */
public final class HostsSearchEverywhereContributor implements SearchEverywhereContributor<SshHost> {

    private final Project project;

    public HostsSearchEverywhereContributor(@NotNull Project project) {
        this.project = project;
    }

    @Override public @NotNull String getSearchProviderId() { return "TermLabHosts"; }
    @Override public @NotNull String getGroupName() { return "Hosts"; }
    @Override public int getSortWeight() { return 50; }
    @Override public boolean showInFindResults() { return false; }
    @Override public boolean isShownInSeparateTab() { return true; }
    @Override public boolean isEmptyPatternSupported() { return true; }

    @Override
    public void fetchElements(@NotNull String pattern,
                               @NotNull ProgressIndicator progressIndicator,
                               @NotNull Processor<? super SshHost> consumer) {
        HostStore store = ApplicationManager.getApplication().getService(HostStore.class);
        if (store == null) return;

        String q = pattern.toLowerCase();
        List<SshHost> matches = store.getHosts().stream()
            .filter(h -> q.isEmpty() || matches(h, q))
            .sorted(Comparator.comparing(SshHost::label, String.CASE_INSENSITIVE_ORDER))
            .toList();

        for (SshHost host : matches) {
            if (progressIndicator.isCanceled()) return;
            if (!consumer.process(host)) return;
        }
    }

    private static boolean matches(@NotNull SshHost host, @NotNull String q) {
        return host.label().toLowerCase().contains(q)
            || host.host().toLowerCase().contains(q)
            || host.username().toLowerCase().contains(q);
    }

    @Override
    public boolean processSelectedItem(@NotNull SshHost selected, int modifiers, @NotNull String searchText) {
        ConnectToHostAction.run(project, selected);
        return true;
    }

    @Override
    public @NotNull ListCellRenderer<? super SshHost> getElementsRenderer() {
        HostCellRenderer inner = new HostCellRenderer();
        return new ListCellRenderer<SshHost>() {
            @Override
            public Component getListCellRendererComponent(
                JList<? extends SshHost> list, SshHost value,
                int index, boolean isSelected, boolean cellHasFocus
            ) {
                return inner.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
        };
    }

    @Override
    public @Nullable Object getDataForItem(@NotNull SshHost element, @NotNull String dataId) {
        return Objects.equals(dataId, "com.termlab.ssh.host") ? element : null;
    }

    /**
     * Factory registered under {@code <searchEverywhereContributor>} in
     * the SSH plugin's {@code plugin.xml}. IntelliJ calls this once per
     * Search Everywhere invocation with a fresh {@link AnActionEvent}
     * that carries the current project.
     */
    public static final class Factory implements SearchEverywhereContributorFactory<SshHost> {
        @Override
        public @NotNull SearchEverywhereContributor<SshHost> createContributor(@NotNull AnActionEvent initEvent) {
            Project project = Objects.requireNonNull(
                initEvent.getProject(),
                "Project required for HostsSearchEverywhereContributor");
            return new HostsSearchEverywhereContributor(project);
        }
    }
}
```

- [ ] **Step 2: Delete `HostsPaletteContributor.java`**

```bash
cd /Users/dustin/projects/termlab_workbench
rm plugins/ssh/src/com/termlab/ssh/palette/HostsPaletteContributor.java
```

- [ ] **Step 3: Update SSH plugin.xml**

Open `plugins/ssh/resources/META-INF/plugin.xml`. Inside the `<extensions defaultExtensionNs="com.intellij">` block (the block that already registers `HostStore` as an application service, the tool window, etc.), add:

```xml
        <searchEverywhereContributor
            implementation="com.termlab.ssh.palette.HostsSearchEverywhereContributor$Factory"/>
```

Then, inside the `<extensions defaultExtensionNs="com.termlab.core">` block, **remove** the line:

```xml
        <commandPaletteContributor implementation="com.termlab.ssh.palette.HostsPaletteContributor"/>
```

Leave the `<terminalSessionProvider>` line in that block untouched. If removing the palette contributor leaves the `com.termlab.core` block empty, delete the empty block entirely.

- [ ] **Step 4: Build and verify**

```bash
cd /Users/dustin/projects/termlab_workbench && make termlab-build 2>&1 | tail -15
```

Expected: `Build completed successfully`.

- [ ] **Step 5: Run the SSH test suite**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/ssh:ssh_test_runner 2>&1 | tail -15
```

Expected: 87/87 passing (unchanged — no tests modified).

- [ ] **Step 6: Commit**

```bash
cd /Users/dustin/projects/termlab_workbench
git add plugins/ssh/src/com/termlab/ssh/palette/HostsSearchEverywhereContributor.java \
        plugins/ssh/resources/META-INF/plugin.xml
git add -u plugins/ssh/src/com/termlab/ssh/palette/HostsPaletteContributor.java
git commit -m "$(cat <<'EOF'
feat(ssh): expose saved hosts in Search Everywhere (Hosts tab)

Replace the dead HostsPaletteContributor (implementing the unused SDK
CommandPaletteContributor interface) with HostsSearchEverywhereContributor,
which implements IntelliJ's SearchEverywhereContributor directly. Selecting
a host from Cmd+Shift+P → Hosts dispatches ConnectToHostAction.

Core's tab allowlist is updated in a follow-up commit once both plugins
have migrated off the old SDK interface.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Vault plugin — `VaultSearchEverywhereContributor` + two new `AnAction`s

Replace the dead `VaultPaletteContributor` with a real Search Everywhere contributor showing accounts and keys. Promote the two menu-style actions it carried ("Lock Vault", "Generate SSH Key…") into real `AnAction`s so they surface in the Actions tab.

**Files:**
- Create: `plugins/vault/src/com/termlab/vault/palette/VaultSearchEverywhereContributor.java`
- Create: `plugins/vault/src/com/termlab/vault/actions/LockVaultAction.java`
- Create: `plugins/vault/src/com/termlab/vault/actions/GenerateSshKeyAction.java`
- Modify: `plugins/vault/resources/META-INF/plugin.xml`
- Delete: `plugins/vault/src/com/termlab/vault/palette/VaultPaletteContributor.java`

- [ ] **Step 1: Create `VaultSearchEverywhereContributor.java`**

Create `plugins/vault/src/com/termlab/vault/palette/VaultSearchEverywhereContributor.java`:

```java
package com.termlab.vault.palette;

import com.termlab.vault.lock.LockManager;
import com.termlab.vault.model.Vault;
import com.termlab.vault.model.VaultAccount;
import com.termlab.vault.model.VaultKey;
import com.termlab.vault.ui.AccountEditDialog;
import com.termlab.vault.ui.KeyEditDialog;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.Component;
import java.util.Comparator;
import java.util.Objects;

/**
 * Exposes vault {@link VaultAccount}s and {@link VaultKey}s in the
 * TermLab command palette (Cmd+Shift+P → Vault tab). Selecting an
 * account opens {@link AccountEditDialog}; selecting a key opens
 * {@link KeyEditDialog}. The menu-style actions the dead
 * {@code VaultPaletteContributor} carried ("Lock Vault",
 * "Generate SSH Key…") are now real {@code AnAction}s and surface
 * through the Actions tab instead.
 *
 * <p>The tab holds both {@link VaultAccount} and {@link VaultKey}
 * values, so the generic type is {@link Object} and the renderer /
 * selection handler dispatch with {@code instanceof}.
 *
 * <p>When the vault is locked, {@link #fetchElements} returns
 * immediately without consuming anything — the tab is empty and the
 * user unlocks via {@code Cmd+Shift+V} or the "Open Vault" entry in
 * the Actions tab.
 */
public final class VaultSearchEverywhereContributor implements SearchEverywhereContributor<Object> {

    private static final Logger LOG = Logger.getInstance(VaultSearchEverywhereContributor.class);

    private final Project project;

    public VaultSearchEverywhereContributor(@NotNull Project project) {
        this.project = project;
    }

    @Override public @NotNull String getSearchProviderId() { return "TermLabVault"; }
    @Override public @NotNull String getGroupName() { return "Vault"; }
    @Override public int getSortWeight() { return 60; }
    @Override public boolean showInFindResults() { return false; }
    @Override public boolean isShownInSeparateTab() { return true; }
    @Override public boolean isEmptyPatternSupported() { return true; }

    @Override
    public void fetchElements(@NotNull String pattern,
                               @NotNull ProgressIndicator progressIndicator,
                               @NotNull Processor<? super Object> consumer) {
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        if (lm == null) return;
        Vault vault = lm.getVault();
        if (vault == null) return;  // locked or not yet created

        String q = pattern.toLowerCase();

        vault.accounts.stream()
            .filter(a -> q.isEmpty() || matchesAccount(a, q))
            .sorted(Comparator.comparing(VaultAccount::displayName, String.CASE_INSENSITIVE_ORDER))
            .forEach(a -> {
                if (!progressIndicator.isCanceled()) consumer.process(a);
            });

        vault.keys.stream()
            .filter(k -> q.isEmpty() || matchesKey(k, q))
            .sorted(Comparator.comparing(VaultKey::name, String.CASE_INSENSITIVE_ORDER))
            .forEach(k -> {
                if (!progressIndicator.isCanceled()) consumer.process(k);
            });
    }

    private static boolean matchesAccount(@NotNull VaultAccount a, @NotNull String q) {
        return a.displayName().toLowerCase().contains(q)
            || a.username().toLowerCase().contains(q);
    }

    private static boolean matchesKey(@NotNull VaultKey k, @NotNull String q) {
        return k.name().toLowerCase().contains(q)
            || k.algorithm().toLowerCase().contains(q);
    }

    @Override
    public boolean processSelectedItem(@NotNull Object selected, int modifiers, @NotNull String searchText) {
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        if (lm == null) return false;
        Vault vault = lm.getVault();
        if (vault == null) return false;

        if (selected instanceof VaultAccount account) {
            VaultAccount updated = AccountEditDialog.show(project, account);
            if (updated == null) return true;  // user cancelled — still close the palette
            vault.accounts.removeIf(a -> a.id().equals(account.id()));
            vault.accounts.add(updated);
            trySave(lm);
            return true;
        }
        if (selected instanceof VaultKey key) {
            VaultKey updated = KeyEditDialog.show(project, key);
            if (updated == null) return true;
            vault.keys.removeIf(k -> k.id().equals(key.id()));
            vault.keys.add(updated);
            trySave(lm);
            return true;
        }
        return false;
    }

    private static void trySave(@NotNull LockManager lm) {
        try {
            lm.save();
        } catch (Exception e) {
            // Palette callbacks can't reliably surface modal errors; log
            // and move on. Same rationale the dead VaultPaletteContributor
            // used, now called out explicitly.
            LOG.warn("TermLab vault: failed to save after palette edit", e);
        }
    }

    @Override
    public @NotNull ListCellRenderer<? super Object> getElementsRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focus
            ) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof VaultAccount a) {
                    setText(a.displayName() + "  \u2014  " + a.username());
                } else if (value instanceof VaultKey k) {
                    setText(k.name() + "  \u2014  " + k.algorithm() + "  \u00b7  " + k.fingerprint());
                }
                return this;
            }
        };
    }

    @Override
    public @Nullable Object getDataForItem(@NotNull Object element, @NotNull String dataId) {
        return Objects.equals(dataId, "com.termlab.vault.entry") ? element : null;
    }

    public static final class Factory implements SearchEverywhereContributorFactory<Object> {
        @Override
        public @NotNull SearchEverywhereContributor<Object> createContributor(@NotNull AnActionEvent initEvent) {
            Project project = Objects.requireNonNull(
                initEvent.getProject(),
                "Project required for VaultSearchEverywhereContributor");
            return new VaultSearchEverywhereContributor(project);
        }
    }
}
```

- [ ] **Step 2: Create `LockVaultAction.java`**

Create `plugins/vault/src/com/termlab/vault/actions/LockVaultAction.java`:

```java
package com.termlab.vault.actions;

import com.termlab.vault.lock.LockManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

/**
 * Locks the TermLab credential vault — seals the in-memory decrypted
 * state and drops cached credentials. Discoverable through the
 * command palette's Actions tab ("Lock Vault"). No keyboard shortcut
 * by default; users who want one can bind it via IntelliJ's keymap
 * settings.
 *
 * <p>Disabled when no vault is unlocked to begin with.
 */
public final class LockVaultAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        if (lm == null || lm.isLocked()) return;
        lm.lock();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        e.getPresentation().setEnabled(lm != null && !lm.isLocked());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
```

- [ ] **Step 3: Create `GenerateSshKeyAction.java`**

Create `plugins/vault/src/com/termlab/vault/actions/GenerateSshKeyAction.java`:

```java
package com.termlab.vault.actions;

import com.termlab.vault.lock.LockManager;
import com.termlab.vault.ui.KeyGenDialog;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Opens {@link KeyGenDialog} so the user can generate a new SSH key
 * pair into the unlocked vault. Discoverable through the command
 * palette's Actions tab ("Generate SSH Key…").
 *
 * <p>Disabled when the vault is locked — key generation needs an
 * unlocked vault to store the resulting {@code VaultKey}.
 */
public final class GenerateSshKeyAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        if (lm == null || lm.isLocked()) return;
        Project project = e.getProject();
        new KeyGenDialog(project, lm).show();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        LockManager lm = ApplicationManager.getApplication().getService(LockManager.class);
        e.getPresentation().setEnabled(lm != null && !lm.isLocked());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
```

- [ ] **Step 4: Delete `VaultPaletteContributor.java`**

```bash
cd /Users/dustin/projects/termlab_workbench
rm plugins/vault/src/com/termlab/vault/palette/VaultPaletteContributor.java
```

- [ ] **Step 5: Update vault plugin.xml**

Open `plugins/vault/resources/META-INF/plugin.xml`.

Inside the `<extensions defaultExtensionNs="com.intellij">` block (alongside `applicationService`, `applicationConfigurable`, `statusBarWidgetFactory`), add:

```xml
        <searchEverywhereContributor
            implementation="com.termlab.vault.palette.VaultSearchEverywhereContributor$Factory"/>
```

Inside the `<extensions defaultExtensionNs="com.termlab.core">` block, **remove** the line:

```xml
        <commandPaletteContributor implementation="com.termlab.vault.palette.VaultPaletteContributor"/>
```

Leave the `<credentialProvider>` line in that block untouched.

Inside the existing `<actions>` block (which already contains `OpenVaultAction`), **add** the two new actions after `OpenVaultAction`:

```xml
        <action id="com.termlab.vault.LockVault"
                class="com.termlab.vault.actions.LockVaultAction"
                text="Lock Vault"
                description="Seal the TermLab credential vault and drop cached credentials"/>

        <action id="com.termlab.vault.GenerateSshKey"
                class="com.termlab.vault.actions.GenerateSshKeyAction"
                text="Generate SSH Key…"
                description="Create a new Ed25519, ECDSA, or RSA key pair in the vault"/>
```

No keyboard shortcuts.

- [ ] **Step 6: Build**

```bash
cd /Users/dustin/projects/termlab_workbench && make termlab-build 2>&1 | tail -15
```

Expected: `Build completed successfully`.

- [ ] **Step 7: Run the vault test suite**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/vault:vault_test_runner 2>&1 | tail -15
```

Expected: same pass count as before — no test changes.

- [ ] **Step 8: Commit**

```bash
cd /Users/dustin/projects/termlab_workbench
git add plugins/vault/src/com/termlab/vault/palette/VaultSearchEverywhereContributor.java \
        plugins/vault/src/com/termlab/vault/actions/LockVaultAction.java \
        plugins/vault/src/com/termlab/vault/actions/GenerateSshKeyAction.java \
        plugins/vault/resources/META-INF/plugin.xml
git add -u plugins/vault/src/com/termlab/vault/palette/VaultPaletteContributor.java
git commit -m "$(cat <<'EOF'
feat(vault): expose vault contents in Search Everywhere (Vault tab)

Replace the dead VaultPaletteContributor (implementing the unused SDK
CommandPaletteContributor interface) with VaultSearchEverywhereContributor,
which implements IntelliJ's SearchEverywhereContributor directly. Selecting
an account opens AccountEditDialog; selecting a key opens KeyEditDialog.

Promote the dead code's "Lock Vault" and "Generate SSH Key…" palette
entries to real AnActions so they surface through the Actions tab. This
is the clean architectural split: AnActions in the Actions tab, data
entries in a dedicated data tab.

Core's tab allowlist is updated in a follow-up commit.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Core + SDK cleanup

Update the tab allowlist, remove the dead extension point declaration, delete the SDK interface and its carrier type. Safe to do only after Tasks 1 and 2 have landed and no plugin references `CommandPaletteContributor` or the `<commandPaletteContributor>` extension point.

**Files:**
- Modify: `core/src/com/termlab/core/palette/TermLabTabsCustomizationStrategy.java`
- Modify: `core/resources/META-INF/plugin.xml`
- Delete: `sdk/src/com/termlab/sdk/CommandPaletteContributor.java`
- Delete: `sdk/src/com/termlab/sdk/PaletteItem.java`

- [ ] **Step 1: Update `TermLabTabsCustomizationStrategy.java`**

Open `core/src/com/termlab/core/palette/TermLabTabsCustomizationStrategy.java`. Replace the `ALLOWED_TAB_IDS` declaration with:

```java
    /**
     * Provider IDs for tabs that should remain visible.
     * "ActionSearchEverywhereContributor" is the Actions tab from the
     * platform. "TermLabTerminals" / "TermLabHosts" / "TermLabVault" are our
     * custom tabs contributed by the core, SSH, and vault plugins
     * respectively.
     */
    private static final Set<String> ALLOWED_TAB_IDS = Set.of(
        "ActionSearchEverywhereContributor",
        "TermLabTerminals",
        "TermLabHosts",
        "TermLabVault"
    );
```

No other changes to this file.

- [ ] **Step 2: Remove the extension point from core plugin.xml**

Open `core/resources/META-INF/plugin.xml`. Find the `<extensionPoints>` block. **Remove** the `commandPaletteContributor` declaration:

```xml
        <extensionPoint name="commandPaletteContributor"
                        interface="com.termlab.sdk.CommandPaletteContributor"
                        dynamic="true"/>
```

Leave the other two extension points (`terminalSessionProvider`, `credentialProvider`) untouched.

- [ ] **Step 3: Delete the SDK files**

```bash
cd /Users/dustin/projects/termlab_workbench
rm sdk/src/com/termlab/sdk/CommandPaletteContributor.java
rm sdk/src/com/termlab/sdk/PaletteItem.java
```

- [ ] **Step 4: Build**

```bash
cd /Users/dustin/projects/termlab_workbench && make termlab-build 2>&1 | tail -15
```

Expected: `Build completed successfully`. If the build complains about a dangling reference to `CommandPaletteContributor` or `PaletteItem`, something was missed in Task 1 or 2 — grep for `CommandPaletteContributor\|PaletteItem` across the whole repo and fix before continuing.

- [ ] **Step 5: Run both plugin test suites**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/ssh:ssh_test_runner 2>&1 | tail -10
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/vault:vault_test_runner 2>&1 | tail -10
```

Expected: both suites green with the same pass counts as before.

- [ ] **Step 6: Commit**

```bash
cd /Users/dustin/projects/termlab_workbench
git add core/src/com/termlab/core/palette/TermLabTabsCustomizationStrategy.java \
        core/resources/META-INF/plugin.xml
git add -u sdk/src/com/termlab/sdk/CommandPaletteContributor.java \
           sdk/src/com/termlab/sdk/PaletteItem.java
git commit -m "$(cat <<'EOF'
refactor: retire CommandPaletteContributor SDK interface

The SDK's CommandPaletteContributor + PaletteItem were never bridged
into Search Everywhere — nothing read the <commandPaletteContributor>
extension point. Both plugins have now migrated to implementing
IntelliJ's SearchEverywhereContributor directly (same pattern as the
existing TerminalPaletteContributor).

This commit finishes the cleanup: adds TermLabHosts and TermLabVault to
the tab allowlist, removes the dead extension point declaration from
core plugin.xml, and deletes the SDK interface and its carrier record.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Build and smoke-test gate

Checklist. Block merging until every item passes.

- [ ] **Step 1: Full product build**

```bash
cd /Users/dustin/projects/termlab_workbench && make termlab-build 2>&1 | tail -15
```

Expected: `Build completed successfully`.

- [ ] **Step 2: SSH test suite**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/ssh:ssh_test_runner 2>&1 | tail -15
```

Expected: 87/87 passing.

- [ ] **Step 3: Vault test suite**

```bash
cd /Users/dustin/projects/intellij-community && bash bazel.cmd run //termlab/plugins/vault:vault_test_runner 2>&1 | tail -15
```

Expected: baseline pass count, no regressions.

- [ ] **Step 4: Verify no dead references remain**

```bash
cd /Users/dustin/projects/termlab_workbench
grep -r "CommandPaletteContributor\|PaletteItem" --include='*.java' --include='*.xml' || echo "no matches"
```

Expected: `no matches` (or matches only inside `docs/` — markdown history is fine).

- [ ] **Step 5: Manual smoke test — launch TermLab**

```bash
cd /Users/dustin/projects/termlab_workbench && make termlab
```

1. Hit `Cmd+Shift+P`. The tab strip along the top should show: **Actions / Terminals / Hosts / Vault** (order may vary; all four must be present).
2. Click the **Hosts** tab. With a blank query, every saved SSH host from the tool window appears. Typing narrows by label, hostname, or username.
3. Select a host with Enter → new editor tab opens connected to it (same flow as double-clicking in the tool window).
4. Re-open the palette. Click the **Vault** tab with the vault **unlocked**. Accounts and keys are listed (accounts first, then keys, each group alphabetized).
5. Select an account with Enter → `AccountEditDialog` opens. Close it with Cancel — palette is gone, no edit. Reopen palette, select the same account, click OK in the dialog → save happens silently, confirmed by the status-bar vault widget staying unlocked.
6. Re-open the palette. Click the **Vault** tab with the vault **locked**. Tab is empty (no items, no error).
7. Click the **Actions** tab. Type `lock vault` → `Lock Vault` action appears. Select it → status-bar vault widget flips to locked.
8. Type `generate ssh key` in the Actions tab **with the vault locked** → `Generate SSH Key…` action appears but is disabled.
9. Unlock the vault via `Cmd+Shift+V`. Reopen palette, Actions tab, type `generate ssh key` → now enabled. Select it → `KeyGenDialog` opens.
10. Verify `Cmd+Shift+V` (`OpenVaultAction`) still works — regression check.

If any step fails, stop and fix before merging.

---

## Self-review checklist (plan author ran this)

**1. Spec coverage:**
- SSH `HostsSearchEverywhereContributor` → Task 1 ✓
- SSH old contributor deletion → Task 1 Step 2 ✓
- SSH plugin.xml update → Task 1 Step 3 ✓
- Vault `VaultSearchEverywhereContributor` → Task 2 Step 1 ✓
- `LockVaultAction` + `GenerateSshKeyAction` → Task 2 Steps 2-3 ✓
- Vault old contributor deletion → Task 2 Step 4 ✓
- Vault plugin.xml update → Task 2 Step 5 ✓
- Core `ALLOWED_TAB_IDS` update → Task 3 Step 1 ✓
- Core extension point removal → Task 3 Step 2 ✓
- SDK deletion → Task 3 Step 3 ✓
- Manual smoke test covering hosts / vault / actions / locked state → Task 4 ✓

No gaps.

**2. Type / method signature consistency:**
- `HostsSearchEverywhereContributor implements SearchEverywhereContributor<SshHost>` — type param matches all method references
- `VaultSearchEverywhereContributor implements SearchEverywhereContributor<Object>` — Object because the tab holds heterogeneous items; `processSelectedItem` takes `Object selected`, dispatches with `instanceof`
- `SearchEverywhereContributorFactory<SshHost>` / `<Object>` — matches each contributor's parameterization
- `HostCellRenderer` has a public constructor (it was lifted to public in the Phase 5/6 work) so the SSH contributor's wrapping renderer can instantiate it directly
- `ConnectToHostAction.run(Project, SshHost)` — signature unchanged, used exactly once in Task 1's `processSelectedItem`
- `AccountEditDialog.show(Project, VaultAccount)` and `KeyEditDialog.show(Project, VaultKey)` — both static methods returning the edited record or null on cancel; used in Task 2's `processSelectedItem`
- `LockManager.save()` throws `IOException` — wrapped in try/catch in `trySave`

**3. Placeholder scan:**
No "TBD", no hand-waving. Every step has complete code or exact commands. Commit ordering is explicit in Tasks 1-3. Task 4 is a runtime gate and deliberately has no code content.
