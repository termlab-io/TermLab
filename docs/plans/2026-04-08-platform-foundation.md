# Conch Platform Foundation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a working terminal workstation on IntelliJ Platform — terminal tabs in the editor area, local PTY, splits, workspace persistence, CWD-synced file explorer, command palette, and mini-window.

**Architecture:** IntelliJ Platform plugin that replaces the editor area with JediTerm terminal instances. A custom `FileEditorProvider` maps terminal sessions to editor tabs. The `TerminalSessionProvider` extension point allows plugins to supply `TtyConnector` implementations. The core ships with a local PTY provider; SSH and other providers come in later plans.

**Tech Stack:** Java 21, IntelliJ Platform 2024.3 (Community Edition), JediTerm (standalone library), pty4j, Gradle with IntelliJ Platform Gradle Plugin 2.x

**Reference spec:** `docs/superpowers/specs/2026-04-08-conch-workstation-design.md`

---

## File Structure

```
conch_2/
├── build.gradle.kts                              # Root build (version catalog, common config)
├── settings.gradle.kts                           # Multi-module project settings
├── gradle.properties                             # IntelliJ Platform version, JVM args
├── conch-sdk/
│   ├── build.gradle.kts                          # SDK module build (no platform dependency)
│   └── src/main/java/com/conch/sdk/
│       ├── TerminalSessionProvider.java          # Extension point: supply TtyConnector
│       ├── CommandPaletteContributor.java         # Extension point: add palette items
│       ├── CredentialProvider.java                # Extension point: supply credentials
│       └── PaletteItem.java                       # Data class for palette search results
├── conch-core/
│   ├── build.gradle.kts                          # Core plugin build (platform dependency)
│   └── src/
│       ├── main/
│       │   ├── java/com/conch/core/
│       │   │   ├── ConchStartupActivity.java     # Project startup: restore workspace
│       │   │   ├── terminal/
│       │   │   │   ├── ConchTerminalFileType.java       # FileType for terminal sessions
│       │   │   │   ├── ConchTerminalVirtualFile.java    # VirtualFile representing a session
│       │   │   │   ├── ConchTerminalEditorProvider.java # FileEditorProvider for terminals
│       │   │   │   ├── ConchTerminalEditor.java         # FileEditor wrapping JediTermWidget
│       │   │   │   ├── ConchTerminalSettings.java       # JediTerm settings provider
│       │   │   │   └── LocalPtySessionProvider.java     # Built-in local PTY provider
│       │   │   ├── workspace/
│       │   │   │   ├── WorkspaceState.java              # Data model: tabs, layout, tool windows
│       │   │   │   ├── WorkspaceManager.java            # Save/load workspace state
│       │   │   │   └── WorkspaceSerializer.java         # JSON serialization
│       │   │   ├── explorer/
│       │   │   │   └── CwdSyncManager.java              # Sync Project View to terminal CWD
│       │   │   ├── palette/
│       │   │   │   └── TerminalPaletteContributor.java  # Search open terminals
│       │   │   ├── miniwindow/
│       │   │   │   └── MiniTerminalWindow.java          # Bare-frame quick terminal
│       │   │   └── actions/
│       │   │       ├── NewTerminalTabAction.java
│       │   │       ├── CloseTerminalTabAction.java
│       │   │       ├── SplitTerminalHorizontalAction.java
│       │   │       ├── SplitTerminalVerticalAction.java
│       │   │       ├── OpenMiniWindowAction.java
│       │   │       ├── SaveWorkspaceAction.java
│       │   │       └── LoadWorkspaceAction.java
│       │   └── resources/
│       │       └── META-INF/
│       │           └── plugin.xml                # Plugin descriptor: extensions, actions, EPs
│       └── test/java/com/conch/core/
│           └── workspace/
│               └── WorkspaceSerializerTest.java  # Unit tests for workspace serialization
└── docs/
    └── superpowers/
        ├── specs/
        └── plans/
```

---

## Task 1: Project Scaffolding

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `conch-sdk/build.gradle.kts`
- Create: `conch-core/build.gradle.kts`
- Create: `conch-core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Initialize Gradle wrapper**

Run:
```bash
cd /Users/dustin/projects/conch_2
gradle wrapper --gradle-version 8.12
```

Expected: `gradle/wrapper/` directory created, `gradlew` and `gradlew.bat` created.

- [ ] **Step 2: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "conch"

include("conch-sdk")
include("conch-core")
```

- [ ] **Step 3: Create `gradle.properties`**

```properties
# IntelliJ Platform
platformVersion=2024.3.1
platformType=IC

# Build
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m
org.gradle.parallel=true
kotlin.stdlib.default.dependency=false
```

- [ ] **Step 4: Create root `build.gradle.kts`**

```kotlin
plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1" apply false
}

group = "com.conch"
version = "0.1.0"

subprojects {
    apply(plugin = "java")

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    repositories {
        mavenCentral()
    }
}
```

- [ ] **Step 5: Create `conch-sdk/build.gradle.kts`**

The SDK module has no IntelliJ Platform dependency — it's a plain Java library so plugin authors don't need the platform to compile against it.

```kotlin
plugins {
    id("java-library")
}

group = "com.conch"
version = "0.1.0"

dependencies {
    api("org.jetbrains:annotations:26.0.1")
}
```

- [ ] **Step 6: Create `conch-core/build.gradle.kts`**

```kotlin
plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

group = "com.conch"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":conch-sdk"))

    // JediTerm standalone (not the IntelliJ terminal plugin)
    implementation("org.jetbrains.jediterm:jediterm-core:3.1.0")
    implementation("org.jetbrains.jediterm:jediterm-ui:3.1.0")

    // PTY for local terminals
    implementation("com.pty4j:pty4j:0.12.13")

    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        instrumentationTools()

        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.conch.core"
        name = "Conch Core"
        version = project.version.toString()
    }
}

tasks {
    runIde {
        jvmArgs("-Xmx2g")
    }
}
```

- [ ] **Step 7: Create minimal `conch-core/src/main/resources/META-INF/plugin.xml`**

```xml
<idea-plugin>
    <id>com.conch.core</id>
    <name>Conch Core</name>
    <version>0.1.0</version>
    <vendor>Conch</vendor>
    <description>Terminal-driven workstation built on IntelliJ Platform</description>

    <depends>com.intellij.modules.platform</depends>

    <extensions defaultExtensionNs="com.intellij">
    </extensions>

    <actions>
    </actions>
</idea-plugin>
```

- [ ] **Step 8: Verify the platform launches**

Run:
```bash
./gradlew :conch-core:runIde
```

Expected: IntelliJ CE launches with the Conch Core plugin loaded. Check via Help > About > Plugin list. Close the IDE.

- [ ] **Step 9: Commit**

```bash
git init
echo ".gradle/\nbuild/\n.idea/\n*.iml\nout/\n.DS_Store" > .gitignore
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle/ conch-sdk/ conch-core/ docs/
git commit -m "feat: scaffold IntelliJ Platform project with SDK and core modules"
```

---

## Task 2: Plugin SDK Interfaces

**Files:**
- Create: `conch-sdk/src/main/java/com/conch/sdk/TerminalSessionProvider.java`
- Create: `conch-sdk/src/main/java/com/conch/sdk/CommandPaletteContributor.java`
- Create: `conch-sdk/src/main/java/com/conch/sdk/CredentialProvider.java`
- Create: `conch-sdk/src/main/java/com/conch/sdk/PaletteItem.java`

- [ ] **Step 1: Create `TerminalSessionProvider.java`**

```java
package com.conch.sdk;

import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Extension point for plugins that provide terminal session backends.
 * Implementations supply a TtyConnector that JediTerm renders.
 * The core ships a local PTY provider; plugins add SSH, Docker, serial, etc.
 */
public interface TerminalSessionProvider {

    /** Unique identifier for this provider (e.g., "com.conch.local-pty"). */
    @NotNull String getId();

    /** Human-readable name shown in UI (e.g., "Local Terminal", "SSH"). */
    @NotNull String getDisplayName();

    /** Icon for tab bar and menus. */
    @Nullable Icon getIcon();

    /**
     * Whether this provider can open a session immediately without user input.
     * Local PTY returns true. SSH (needs host selection) returns false.
     */
    boolean canQuickOpen();

    /**
     * Create a new terminal session. May show UI to collect parameters
     * (e.g., host picker for SSH). Returns null if the user cancels.
     *
     * @param context provides access to project and application services
     * @return a connected TtyConnector, or null if cancelled
     */
    @Nullable TtyConnector createSession(@NotNull SessionContext context);

    /**
     * Context passed to createSession. Wraps project and app services
     * without requiring plugins to depend on IntelliJ Platform directly.
     */
    interface SessionContext {
        /** The working directory to start the session in (for local PTY). */
        @Nullable String getWorkingDirectory();
    }
}
```

- [ ] **Step 2: Create `PaletteItem.java`**

```java
package com.conch.sdk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * A single item that appears in the command palette search results.
 */
public final class PaletteItem {
    private final String id;
    private final String displayName;
    private final String description;
    private final Icon icon;
    private final Runnable action;

    public PaletteItem(@NotNull String id,
                       @NotNull String displayName,
                       @Nullable String description,
                       @Nullable Icon icon,
                       @NotNull Runnable action) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
        this.action = action;
    }

    public @NotNull String getId() { return id; }
    public @NotNull String getDisplayName() { return displayName; }
    public @Nullable String getDescription() { return description; }
    public @Nullable Icon getIcon() { return icon; }
    public @NotNull Runnable getAction() { return action; }
}
```

- [ ] **Step 3: Create `CommandPaletteContributor.java`**

```java
package com.conch.sdk;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Extension point for plugins that contribute searchable items
 * to the command palette. SSH plugin contributes hosts,
 * vault plugin contributes accounts, etc.
 */
public interface CommandPaletteContributor {

    /** Tab label in the command palette (e.g., "Hosts", "Vault"). */
    @NotNull String getTabName();

    /** Weight for ordering tabs. Lower values appear first. */
    int getTabWeight();

    /**
     * Search for items matching the query string.
     * Called on each keystroke in the command palette.
     *
     * @param query the user's search text (may be empty)
     * @return matching items, ordered by relevance
     */
    @NotNull List<PaletteItem> search(@NotNull String query);
}
```

- [ ] **Step 4: Create `CredentialProvider.java`**

```java
package com.conch.sdk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Extension point for plugins that supply credentials.
 * The vault plugin implements this; SSH plugin consumes it.
 * Future: 1Password, HashiCorp Vault integrations.
 */
public interface CredentialProvider {

    /** Human-readable name of this provider (e.g., "Conch Vault"). */
    @NotNull String getDisplayName();

    /** Whether the credential store is currently unlocked and available. */
    boolean isAvailable();

    /**
     * Retrieve credentials for a specific account.
     * Returns null if the account is not found or the store is locked.
     *
     * @param accountId the UUID of the credential account
     * @return credentials, or null
     */
    @Nullable Credential getCredential(@NotNull UUID accountId);

    /**
     * Prompt the user to select a credential account.
     * Shows a picker UI. Returns null if cancelled.
     *
     * @return selected credentials, or null
     */
    @Nullable Credential promptForCredential();

    /** Credential data. Consumers must zero the char arrays after use. */
    record Credential(
        @NotNull UUID accountId,
        @NotNull String displayName,
        @NotNull String username,
        @NotNull AuthMethod authMethod,
        char @Nullable [] password,
        @Nullable String keyPath,
        char @Nullable [] keyPassphrase
    ) {
        /** Zero all sensitive fields. Call this when done with the credential. */
        public void destroy() {
            if (password != null) java.util.Arrays.fill(password, '\0');
            if (keyPassphrase != null) java.util.Arrays.fill(keyPassphrase, '\0');
        }
    }

    enum AuthMethod { PASSWORD, KEY, KEY_AND_PASSWORD }
}
```

- [ ] **Step 5: Verify SDK compiles**

Run:
```bash
./gradlew :conch-sdk:build
```

Expected: BUILD SUCCESSFUL. Note: The SDK depends on JediTerm for the `TtyConnector` type in `TerminalSessionProvider`. Add to `conch-sdk/build.gradle.kts`:

```kotlin
dependencies {
    api("org.jetbrains:annotations:26.0.1")
    api("org.jetbrains.jediterm:jediterm-core:3.1.0")
}
```

Then re-run:
```bash
./gradlew :conch-sdk:build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add conch-sdk/
git commit -m "feat: define plugin SDK interfaces — TerminalSessionProvider, CommandPaletteContributor, CredentialProvider"
```

---

## Task 3: Terminal FileType, VirtualFile, and EditorProvider

**Files:**
- Create: `conch-core/src/main/java/com/conch/core/terminal/ConchTerminalFileType.java`
- Create: `conch-core/src/main/java/com/conch/core/terminal/ConchTerminalVirtualFile.java`
- Create: `conch-core/src/main/java/com/conch/core/terminal/ConchTerminalEditorProvider.java`
- Create: `conch-core/src/main/java/com/conch/core/terminal/ConchTerminalEditor.java`
- Create: `conch-core/src/main/java/com/conch/core/terminal/ConchTerminalSettings.java`
- Modify: `conch-core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `ConchTerminalFileType.java`**

A marker FileType for terminal sessions. Not registered for file extensions — only used programmatically.

```java
package com.conch.core.terminal;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public final class ConchTerminalFileType implements FileType {

    public static final ConchTerminalFileType INSTANCE = new ConchTerminalFileType();

    private ConchTerminalFileType() {}

    @Override public @NotNull String getName() { return "ConchTerminal"; }
    @Override public @NotNull String getDescription() { return "Conch Terminal Session"; }
    @Override public @NotNull String getDefaultExtension() { return ""; }
    @Override public @Nullable Icon getIcon() { return AllIcons.Debugger.Console; }
    @Override public boolean isBinary() { return true; }
    @Override public boolean isReadOnly() { return true; }
}
```

- [ ] **Step 2: Create `ConchTerminalVirtualFile.java`**

```java
package com.conch.core.terminal;

import com.conch.sdk.TerminalSessionProvider;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A virtual file representing a terminal session.
 * Each open terminal tab corresponds to one of these.
 */
public final class ConchTerminalVirtualFile extends LightVirtualFile {

    private final String sessionId;
    private final TerminalSessionProvider provider;
    private String currentWorkingDirectory;

    public ConchTerminalVirtualFile(@NotNull String title,
                                     @NotNull TerminalSessionProvider provider) {
        super(title, ConchTerminalFileType.INSTANCE, "");
        this.sessionId = UUID.randomUUID().toString();
        this.provider = provider;
    }

    public @NotNull String getSessionId() { return sessionId; }
    public @NotNull TerminalSessionProvider getProvider() { return provider; }

    public @Nullable String getCurrentWorkingDirectory() { return currentWorkingDirectory; }
    public void setCurrentWorkingDirectory(@Nullable String cwd) { this.currentWorkingDirectory = cwd; }

    @Override
    public boolean isWritable() { return false; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConchTerminalVirtualFile other)) return false;
        return sessionId.equals(other.sessionId);
    }

    @Override
    public int hashCode() { return sessionId.hashCode(); }
}
```

- [ ] **Step 3: Create `ConchTerminalSettings.java`**

JediTerm settings provider with sensible defaults.

```java
package com.conch.core.terminal;

import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

public final class ConchTerminalSettings extends DefaultSettingsProvider {

    @Override
    public @NotNull Font getTerminalFont() {
        // JetBrains Mono ships with IntelliJ Platform
        return new Font("JetBrains Mono", Font.PLAIN, 14);
    }

    @Override
    public float getTerminalFontSize() {
        return 14.0f;
    }

    @Override
    public boolean audibleBell() {
        return false;
    }

    @Override
    public boolean enableMouseReporting() {
        return true;
    }

    @Override
    public int getBufferMaxLinesCount() {
        return 10000;
    }

    @Override
    public boolean scrollToBottomOnTyping() {
        return true;
    }
}
```

- [ ] **Step 4: Create `ConchTerminalEditor.java`**

```java
package com.conch.core.terminal;

import com.conch.sdk.TerminalSessionProvider;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.beans.PropertyChangeListener;

public final class ConchTerminalEditor extends UserDataHolderBase implements FileEditor {

    private final Project project;
    private final ConchTerminalVirtualFile file;
    private final JediTermWidget terminalWidget;
    private TtyConnector connector;

    public ConchTerminalEditor(@NotNull Project project,
                                @NotNull ConchTerminalVirtualFile file) {
        this.project = project;
        this.file = file;
        this.terminalWidget = new JediTermWidget(new ConchTerminalSettings());

        initTerminalSession();
    }

    private void initTerminalSession() {
        String cwd = file.getCurrentWorkingDirectory();
        if (cwd == null) {
            cwd = System.getProperty("user.home");
        }
        String workDir = cwd;

        TerminalSessionProvider.SessionContext context = () -> workDir;
        connector = file.getProvider().createSession(context);

        if (connector != null) {
            terminalWidget.createTerminalSession(connector);
            terminalWidget.start();
        }
    }

    @Override
    public @NotNull JComponent getComponent() {
        return terminalWidget;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return terminalWidget;
    }

    @Override
    public @NotNull String getName() {
        return "Terminal";
    }

    @Override
    public boolean isModified() { return false; }

    @Override
    public boolean isValid() {
        return connector != null && connector.isConnected();
    }

    @Override
    public void setState(@NotNull FileEditorState state) {}

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {}

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {}

    @Override
    public void dispose() {
        if (connector != null) {
            try { connector.close(); } catch (Exception ignored) {}
        }
    }

    public @NotNull ConchTerminalVirtualFile getTerminalFile() { return file; }
    public @NotNull JediTermWidget getTerminalWidget() { return terminalWidget; }
}
```

- [ ] **Step 5: Create `ConchTerminalEditorProvider.java`**

```java
package com.conch.core.terminal;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public final class ConchTerminalEditorProvider implements FileEditorProvider, DumbAware {

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return file instanceof ConchTerminalVirtualFile;
    }

    @Override
    public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        return new ConchTerminalEditor(project, (ConchTerminalVirtualFile) file);
    }

    @Override
    public @NotNull String getEditorTypeId() {
        return "conch-terminal-editor";
    }

    @Override
    public @NotNull FileEditorPolicy getPolicy() {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
    }
}
```

- [ ] **Step 6: Register the editor provider and extension points in `plugin.xml`**

Update `conch-core/src/main/resources/META-INF/plugin.xml`:

```xml
<idea-plugin>
    <id>com.conch.core</id>
    <name>Conch Core</name>
    <version>0.1.0</version>
    <vendor>Conch</vendor>
    <description>Terminal-driven workstation built on IntelliJ Platform</description>

    <depends>com.intellij.modules.platform</depends>

    <!-- Conch-defined extension points for plugins to implement -->
    <extensionPoints>
        <extensionPoint name="terminalSessionProvider"
                        interface="com.conch.sdk.TerminalSessionProvider"
                        dynamic="true"/>
        <extensionPoint name="commandPaletteContributor"
                        interface="com.conch.sdk.CommandPaletteContributor"
                        dynamic="true"/>
        <extensionPoint name="credentialProvider"
                        interface="com.conch.sdk.CredentialProvider"
                        dynamic="true"/>
    </extensionPoints>

    <extensions defaultExtensionNs="com.intellij">
        <fileEditorProvider
            implementation="com.conch.core.terminal.ConchTerminalEditorProvider"/>
    </extensions>

    <actions>
    </actions>
</idea-plugin>
```

These extension points allow future plugins (SSH, Docker, serial, etc.) to register their own `TerminalSessionProvider` implementations. The core discovers them via `ExtensionPointName.getExtensionList()`. For Plan 1, the local PTY provider is used directly; in Plan 3 the SSH plugin will register via these extension points.

- [ ] **Step 7: Verify the build compiles**

Run:
```bash
./gradlew :conch-core:build
```

Expected: BUILD SUCCESSFUL. Don't run the IDE yet — we need the PTY provider first.

- [ ] **Step 8: Commit**

```bash
git add conch-core/src/main/java/com/conch/core/terminal/ conch-core/src/main/resources/META-INF/plugin.xml
git commit -m "feat: terminal FileType, VirtualFile, EditorProvider, and JediTerm Editor"
```

---

## Task 4: Local PTY Session Provider and New Tab Action

**Files:**
- Create: `conch-core/src/main/java/com/conch/core/terminal/LocalPtySessionProvider.java`
- Create: `conch-core/src/main/java/com/conch/core/actions/NewTerminalTabAction.java`
- Modify: `conch-core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `LocalPtySessionProvider.java`**

```java
package com.conch.core.terminal;

import com.conch.sdk.TerminalSessionProvider;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ProcessTtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class LocalPtySessionProvider implements TerminalSessionProvider {

    @Override
    public @NotNull String getId() { return "com.conch.local-pty"; }

    @Override
    public @NotNull String getDisplayName() { return "Local Terminal"; }

    @Override
    public @Nullable Icon getIcon() { return null; }

    @Override
    public boolean canQuickOpen() { return true; }

    @Override
    public @Nullable TtyConnector createSession(@NotNull SessionContext context) {
        try {
            String shell = System.getenv("SHELL");
            if (shell == null || shell.isBlank()) {
                shell = "/bin/zsh";
            }

            String workDir = context.getWorkingDirectory();
            if (workDir == null) {
                workDir = System.getProperty("user.home");
            }

            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("TERM", "xterm-256color");
            env.put("COLORTERM", "truecolor");

            PtyProcess process = new PtyProcessBuilder()
                .setCommand(new String[]{shell, "-l"})
                .setDirectory(workDir)
                .setEnvironment(env)
                .setInitialColumns(120)
                .setInitialRows(40)
                .start();

            return new ProcessTtyConnector(process, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start local PTY: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 2: Create `NewTerminalTabAction.java`**

```java
package com.conch.core.actions;

import com.conch.core.terminal.ConchTerminalVirtualFile;
import com.conch.core.terminal.LocalPtySessionProvider;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class NewTerminalTabAction extends AnAction implements DumbAware {

    private final LocalPtySessionProvider ptyProvider = new LocalPtySessionProvider();

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        ConchTerminalVirtualFile terminalFile =
            new ConchTerminalVirtualFile("Terminal", ptyProvider);

        FileEditorManager.getInstance(project).openFile(terminalFile, true);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
```

- [ ] **Step 3: Register action in `plugin.xml`**

Update the `<actions>` section of `plugin.xml`:

```xml
    <actions>
        <action id="Conch.NewTerminalTab"
                class="com.conch.core.actions.NewTerminalTabAction"
                text="New Terminal Tab"
                description="Open a new local terminal tab">
            <keyboard-shortcut keymap="$default"
                               first-keystroke="meta T"/>
        </action>
    </actions>
```

- [ ] **Step 4: Verify — open a working terminal tab**

Run:
```bash
./gradlew :conch-core:runIde
```

In the launched IDE:
1. Open any project (or create a new empty project)
2. Press `Cmd+T`
3. A terminal tab should appear in the editor area
4. Type `ls` and press Enter — you should see directory output
5. Type `echo $TERM` — should show `xterm-256color`
6. Verify the terminal is interactive (try `top`, `vim`, arrow keys, colors)

Expected: A fully working terminal in the editor area.

- [ ] **Step 5: Commit**

```bash
git add conch-core/src/main/java/com/conch/core/terminal/LocalPtySessionProvider.java conch-core/src/main/java/com/conch/core/actions/NewTerminalTabAction.java conch-core/src/main/resources/META-INF/plugin.xml
git commit -m "feat: local PTY provider and new terminal tab action (Cmd+T)"
```

---

## Task 5: Tab Management Actions

**Files:**
- Create: `conch-core/src/main/java/com/conch/core/actions/CloseTerminalTabAction.java`
- Create: `conch-core/src/main/java/com/conch/core/actions/SplitTerminalHorizontalAction.java`
- Create: `conch-core/src/main/java/com/conch/core/actions/SplitTerminalVerticalAction.java`
- Modify: `conch-core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `CloseTerminalTabAction.java`**

```java
package com.conch.core.actions;

import com.conch.core.terminal.ConchTerminalVirtualFile;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public final class CloseTerminalTabAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        FileEditorManager manager = FileEditorManager.getInstance(project);
        VirtualFile selectedFile = manager.getSelectedEditor() != null
            ? manager.getSelectedEditor().getFile()
            : null;

        if (selectedFile instanceof ConchTerminalVirtualFile) {
            manager.closeFile(selectedFile);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean enabled = false;
        if (project != null) {
            FileEditorManager manager = FileEditorManager.getInstance(project);
            var editor = manager.getSelectedEditor();
            enabled = editor != null && editor.getFile() instanceof ConchTerminalVirtualFile;
        }
        e.getPresentation().setEnabledAndVisible(enabled);
    }
}
```

- [ ] **Step 2: Create `SplitTerminalHorizontalAction.java`**

This leverages IntelliJ's built-in split editor support. We open a new terminal in the existing split group.

```java
package com.conch.core.actions;

import com.conch.core.terminal.ConchTerminalVirtualFile;
import com.conch.core.terminal.LocalPtySessionProvider;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class SplitTerminalHorizontalAction extends AnAction implements DumbAware {

    private final LocalPtySessionProvider ptyProvider = new LocalPtySessionProvider();

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(project);

        // Split the current editor area horizontally (side-by-side),
        // then open a new terminal in the new split
        manager.createSplitter(javax.swing.SwingConstants.VERTICAL, manager.getCurrentWindow());

        ConchTerminalVirtualFile terminalFile =
            new ConchTerminalVirtualFile("Terminal", ptyProvider);
        manager.openFile(terminalFile, true);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
```

- [ ] **Step 3: Create `SplitTerminalVerticalAction.java`**

```java
package com.conch.core.actions;

import com.conch.core.terminal.ConchTerminalVirtualFile;
import com.conch.core.terminal.LocalPtySessionProvider;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class SplitTerminalVerticalAction extends AnAction implements DumbAware {

    private final LocalPtySessionProvider ptyProvider = new LocalPtySessionProvider();

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(project);

        // Split horizontally (top-bottom)
        manager.createSplitter(javax.swing.SwingConstants.HORIZONTAL, manager.getCurrentWindow());

        ConchTerminalVirtualFile terminalFile =
            new ConchTerminalVirtualFile("Terminal", ptyProvider);
        manager.openFile(terminalFile, true);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
```

- [ ] **Step 4: Register all actions in `plugin.xml`**

Replace the `<actions>` section:

```xml
    <actions>
        <group id="Conch.TerminalActions" text="Terminal" popup="true">
            <add-to-group group-id="MainMenu" anchor="before" relative-to-action="HelpMenu"/>

            <action id="Conch.NewTerminalTab"
                    class="com.conch.core.actions.NewTerminalTabAction"
                    text="New Terminal Tab"
                    description="Open a new local terminal tab">
                <keyboard-shortcut keymap="$default"
                                   first-keystroke="meta T"/>
            </action>

            <action id="Conch.CloseTerminalTab"
                    class="com.conch.core.actions.CloseTerminalTabAction"
                    text="Close Terminal Tab"
                    description="Close the active terminal tab">
                <keyboard-shortcut keymap="$default"
                                   first-keystroke="meta W"/>
            </action>

            <action id="Conch.SplitHorizontal"
                    class="com.conch.core.actions.SplitTerminalHorizontalAction"
                    text="Split Terminal Right"
                    description="Split the editor area and open a new terminal to the right">
                <keyboard-shortcut keymap="$default"
                                   first-keystroke="meta D"/>
            </action>

            <action id="Conch.SplitVertical"
                    class="com.conch.core.actions.SplitTerminalVerticalAction"
                    text="Split Terminal Down"
                    description="Split the editor area and open a new terminal below">
                <keyboard-shortcut keymap="$default"
                                   first-keystroke="meta shift D"/>
            </action>
        </group>
    </actions>
```

- [ ] **Step 5: Verify all tab actions**

Run:
```bash
./gradlew :conch-core:runIde
```

Test in the launched IDE:
1. `Cmd+T` — new terminal tab appears
2. `Cmd+T` again — second tab, both appear in tab bar
3. `Cmd+D` — splits horizontally, new terminal in right pane
4. `Cmd+Shift+D` — splits vertically, new terminal below
5. `Cmd+W` — closes the active terminal tab
6. Tab navigation with `Cmd+Shift+[` / `Cmd+Shift+]` should work via IntelliJ's built-in tab navigation (already registered in the platform)

Expected: Full tab management with splits.

Note: `Cmd+1..9` for direct tab access and `Alt+1..9` for tool windows are built into the IntelliJ Platform and should work automatically.

- [ ] **Step 6: Commit**

```bash
git add conch-core/src/main/java/com/conch/core/actions/ conch-core/src/main/resources/META-INF/plugin.xml
git commit -m "feat: tab management actions — close, split horizontal, split vertical"
```

---

## Task 6: Workspace Persistence

**Files:**
- Create: `conch-core/src/main/java/com/conch/core/workspace/WorkspaceState.java`
- Create: `conch-core/src/main/java/com/conch/core/workspace/WorkspaceSerializer.java`
- Create: `conch-core/src/main/java/com/conch/core/workspace/WorkspaceManager.java`
- Create: `conch-core/src/main/java/com/conch/core/ConchStartupActivity.java`
- Create: `conch-core/src/test/java/com/conch/core/workspace/WorkspaceSerializerTest.java`
- Modify: `conch-core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write failing test for workspace serialization**

```java
package com.conch.core.workspace;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class WorkspaceSerializerTest {

    @Test
    public void roundTripsEmptyWorkspace() {
        WorkspaceState state = new WorkspaceState("default", List.of());
        String json = WorkspaceSerializer.toJson(state);
        WorkspaceState restored = WorkspaceSerializer.fromJson(json);

        assertEquals("default", restored.name());
        assertTrue(restored.tabs().isEmpty());
    }

    @Test
    public void roundTripsWorkspaceWithTabs() {
        List<WorkspaceState.TabState> tabs = List.of(
            new WorkspaceState.TabState(
                "tab-1", "Terminal", "com.conch.local-pty",
                "/Users/dustin/projects", null
            ),
            new WorkspaceState.TabState(
                "tab-2", "prod-server", "com.conch.ssh",
                null, "server-uuid-123"
            )
        );

        WorkspaceState state = new WorkspaceState("my-workspace", tabs);
        String json = WorkspaceSerializer.toJson(state);
        WorkspaceState restored = WorkspaceSerializer.fromJson(json);

        assertEquals("my-workspace", restored.name());
        assertEquals(2, restored.tabs().size());

        WorkspaceState.TabState tab1 = restored.tabs().get(0);
        assertEquals("tab-1", tab1.sessionId());
        assertEquals("Terminal", tab1.title());
        assertEquals("com.conch.local-pty", tab1.providerId());
        assertEquals("/Users/dustin/projects", tab1.workingDirectory());
        assertNull(tab1.connectionId());

        WorkspaceState.TabState tab2 = restored.tabs().get(1);
        assertEquals("com.conch.ssh", tab2.providerId());
        assertEquals("server-uuid-123", tab2.connectionId());
    }

    @Test
    public void handlesNullFieldsGracefully() {
        WorkspaceState.TabState tab = new WorkspaceState.TabState(
            "id", "title", "provider", null, null
        );
        WorkspaceState state = new WorkspaceState("test", List.of(tab));
        String json = WorkspaceSerializer.toJson(state);
        WorkspaceState restored = WorkspaceSerializer.fromJson(json);

        assertNull(restored.tabs().get(0).workingDirectory());
        assertNull(restored.tabs().get(0).connectionId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :conch-core:test --tests "com.conch.core.workspace.WorkspaceSerializerTest"
```

Expected: FAIL — classes `WorkspaceState` and `WorkspaceSerializer` do not exist.

- [ ] **Step 3: Create `WorkspaceState.java`**

```java
package com.conch.core.workspace;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Serializable snapshot of the current workspace.
 * Captures open terminal tabs and their state.
 */
public record WorkspaceState(
    @NotNull String name,
    @NotNull List<TabState> tabs
) {
    /**
     * State of a single terminal tab.
     */
    public record TabState(
        @NotNull String sessionId,
        @NotNull String title,
        @NotNull String providerId,
        @Nullable String workingDirectory,
        @Nullable String connectionId
    ) {}
}
```

- [ ] **Step 4: Create `WorkspaceSerializer.java`**

```java
package com.conch.core.workspace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNull;

/**
 * JSON serialization for workspace state.
 * Uses Gson (bundled with IntelliJ Platform).
 */
public final class WorkspaceSerializer {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    private WorkspaceSerializer() {}

    public static @NotNull String toJson(@NotNull WorkspaceState state) {
        return GSON.toJson(state);
    }

    public static @NotNull WorkspaceState fromJson(@NotNull String json) {
        return GSON.fromJson(json, WorkspaceState.class);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run:
```bash
./gradlew :conch-core:test --tests "com.conch.core.workspace.WorkspaceSerializerTest"
```

Expected: All 3 tests PASS.

Note: Gson is bundled with the IntelliJ Platform runtime. If the test classpath doesn't include it, add to `conch-core/build.gradle.kts`:

```kotlin
testImplementation("com.google.code.gson:gson:2.11.0")
```

- [ ] **Step 6: Create `WorkspaceManager.java`**

```java
package com.conch.core.workspace;

import com.conch.core.terminal.ConchTerminalEditor;
import com.conch.core.terminal.ConchTerminalVirtualFile;
import com.conch.core.terminal.LocalPtySessionProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.PROJECT)
public final class WorkspaceManager {

    private static final Logger LOG = Logger.getInstance(WorkspaceManager.class);
    private static final Path WORKSPACES_DIR = Path.of(
        System.getProperty("user.home"), ".config", "conch", "workspaces"
    );
    private static final String DEFAULT_WORKSPACE = "default.json";

    private final Project project;
    private String activeWorkspaceName = "default";

    public WorkspaceManager(@NotNull Project project) {
        this.project = project;
    }

    public static WorkspaceManager getInstance(@NotNull Project project) {
        return project.getService(WorkspaceManager.class);
    }

    /**
     * Capture the current state of all open terminal tabs.
     */
    public @NotNull WorkspaceState captureState() {
        FileEditorManager editorManager = FileEditorManager.getInstance(project);
        List<WorkspaceState.TabState> tabs = new ArrayList<>();

        for (VirtualFile file : editorManager.getOpenFiles()) {
            if (file instanceof ConchTerminalVirtualFile termFile) {
                tabs.add(new WorkspaceState.TabState(
                    termFile.getSessionId(),
                    termFile.getName(),
                    termFile.getProvider().getId(),
                    termFile.getCurrentWorkingDirectory(),
                    null // connectionId — set by SSH plugin in future
                ));
            }
        }

        return new WorkspaceState(activeWorkspaceName, tabs);
    }

    /**
     * Save the current workspace to disk.
     */
    public void save() {
        try {
            Files.createDirectories(WORKSPACES_DIR);
            WorkspaceState state = captureState();
            String json = WorkspaceSerializer.toJson(state);
            Path file = WORKSPACES_DIR.resolve(activeWorkspaceName + ".json");

            // Atomic write: temp file then rename
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            LOG.info("Workspace saved: " + file);
        } catch (IOException e) {
            LOG.error("Failed to save workspace", e);
        }
    }

    /**
     * Restore a workspace from disk. Opens terminal tabs.
     */
    public void restore() {
        restore(DEFAULT_WORKSPACE);
    }

    public void restore(@NotNull String fileName) {
        Path file = WORKSPACES_DIR.resolve(fileName);
        if (!Files.exists(file)) {
            // First launch — open a single terminal tab
            openDefaultTerminal();
            return;
        }

        try {
            String json = Files.readString(file);
            WorkspaceState state = WorkspaceSerializer.fromJson(json);
            activeWorkspaceName = state.name();

            if (state.tabs().isEmpty()) {
                openDefaultTerminal();
                return;
            }

            LocalPtySessionProvider ptyProvider = new LocalPtySessionProvider();
            FileEditorManager editorManager = FileEditorManager.getInstance(project);

            for (WorkspaceState.TabState tab : state.tabs()) {
                // For now, only restore local PTY tabs.
                // SSH tabs will show as "disconnected" once the SSH plugin exists.
                if ("com.conch.local-pty".equals(tab.providerId())) {
                    ConchTerminalVirtualFile termFile =
                        new ConchTerminalVirtualFile(tab.title(), ptyProvider);
                    termFile.setCurrentWorkingDirectory(tab.workingDirectory());
                    editorManager.openFile(termFile, false);
                }
            }
        } catch (IOException e) {
            LOG.error("Failed to restore workspace", e);
            openDefaultTerminal();
        }
    }

    public void saveAs(@NotNull String name) {
        activeWorkspaceName = name;
        save();
    }

    public @NotNull List<String> listWorkspaces() {
        List<String> names = new ArrayList<>();
        try {
            if (Files.exists(WORKSPACES_DIR)) {
                try (var stream = Files.list(WORKSPACES_DIR)) {
                    stream.filter(p -> p.toString().endsWith(".json"))
                        .forEach(p -> {
                            String fileName = p.getFileName().toString();
                            names.add(fileName.substring(0, fileName.length() - 5));
                        });
                }
            }
        } catch (IOException e) {
            LOG.error("Failed to list workspaces", e);
        }
        return names;
    }

    private void openDefaultTerminal() {
        LocalPtySessionProvider ptyProvider = new LocalPtySessionProvider();
        ConchTerminalVirtualFile termFile =
            new ConchTerminalVirtualFile("Terminal", ptyProvider);
        FileEditorManager.getInstance(project).openFile(termFile, true);
    }
}
```

- [ ] **Step 7: Create `ConchStartupActivity.java`**

Restores workspace on project open, saves on project close.

```java
package com.conch.core;

import com.conch.core.workspace.WorkspaceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Restores the workspace when a project opens.
 * Workspace save is handled via ProjectManagerListener (see plugin.xml).
 */
public final class ConchStartupActivity implements ProjectActivity {

    @Override
    public @Nullable Object execute(@NotNull Project project,
                                      @NotNull Continuation<? super Unit> continuation) {
        WorkspaceManager.getInstance(project).restore();
        return Unit.INSTANCE;
    }
}
```

- [ ] **Step 8: Register startup activity and project close listener in `plugin.xml`**

Add to the `<extensions>` section:

```xml
        <postStartupActivity
            implementation="com.conch.core.ConchStartupActivity"/>

        <projectService
            serviceImplementation="com.conch.core.workspace.WorkspaceManager"/>
```

Note: For saving workspace on project close, we need a `ProjectManagerListener`. Create a small listener class:

Create `conch-core/src/main/java/com/conch/core/ConchProjectCloseListener.java`:

```java
package com.conch.core;

import com.conch.core.workspace.WorkspaceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import org.jetbrains.annotations.NotNull;

public final class ConchProjectCloseListener implements ProjectManagerListener {

    @Override
    public void projectClosing(@NotNull Project project) {
        WorkspaceManager.getInstance(project).save();
    }
}
```

Add to `plugin.xml` `<extensions>` section:

```xml
        <projectCloseListener
            implementation="com.conch.core.ConchProjectCloseListener"/>
```

- [ ] **Step 9: Run serialization tests**

Run:
```bash
./gradlew :conch-core:test --tests "com.conch.core.workspace.WorkspaceSerializerTest"
```

Expected: All 3 tests PASS.

- [ ] **Step 10: Verify workspace persistence manually**

Run:
```bash
./gradlew :conch-core:runIde
```

1. Open a project, press `Cmd+T` twice (two terminal tabs)
2. In one terminal, `cd /tmp`
3. Close the IDE
4. Check `~/.config/conch/workspaces/default.json` exists and contains tab state
5. Relaunch with `./gradlew :conch-core:runIde`
6. The two terminal tabs should be restored (the one that was in `/tmp` should start in `/tmp`)

Expected: Workspace round-trips across IDE restarts.

- [ ] **Step 11: Commit**

```bash
git add conch-core/src/main/java/com/conch/core/workspace/ conch-core/src/main/java/com/conch/core/ConchStartupActivity.java conch-core/src/main/java/com/conch/core/ConchProjectCloseListener.java conch-core/src/test/java/com/conch/core/workspace/ conch-core/src/main/resources/META-INF/plugin.xml
git commit -m "feat: workspace persistence — auto-save on close, restore on launch"
```

---

## Task 7: CWD File Explorer Sync

**Files:**
- Create: `conch-core/src/main/java/com/conch/core/explorer/CwdSyncManager.java`
- Modify: `conch-core/src/main/java/com/conch/core/terminal/ConchTerminalEditor.java`
- Modify: `conch-core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `CwdSyncManager.java`**

Listens for CWD changes from the active terminal and navigates the Project View.

```java
package com.conch.core.explorer;

import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.PROJECT)
public final class CwdSyncManager {

    private static final Logger LOG = Logger.getInstance(CwdSyncManager.class);
    private final Project project;
    private String lastSyncedPath;

    public CwdSyncManager(@NotNull Project project) {
        this.project = project;
    }

    public static CwdSyncManager getInstance(@NotNull Project project) {
        return project.getService(CwdSyncManager.class);
    }

    /**
     * Called when the terminal's working directory changes (OSC 7).
     * Navigates the Project View to the new directory.
     */
    public void onWorkingDirectoryChanged(@Nullable String path) {
        if (path == null || path.equals(lastSyncedPath)) return;
        lastSyncedPath = path;

        ApplicationManager.getApplication().invokeLater(() -> {
            VirtualFile dir = LocalFileSystem.getInstance().findFileByPath(path);
            if (dir != null && dir.isDirectory()) {
                ProjectView projectView = ProjectView.getInstance(project);
                projectView.select(null, dir, true);
                LOG.info("CWD synced to: " + path);
            }
        });
    }
}
```

- [ ] **Step 2: Add CWD tracking to `ConchTerminalEditor.java`**

JediTerm processes OSC sequences internally. We intercept CWD changes by subclassing or wrapping the terminal's command handler. The simplest approach: poll the PTY's foreground process CWD, or parse OSC 7 from the output stream.

For a pragmatic v1, we wrap the `TtyConnector` to intercept output and parse OSC 7 sequences:

Create `conch-core/src/main/java/com/conch/core/terminal/OscTrackingTtyConnector.java`:

```java
package com.conch.core.terminal;

import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps a TtyConnector and intercepts OSC 7 (working directory) sequences.
 * Passes the CWD to a listener whenever it changes.
 */
public final class OscTrackingTtyConnector implements TtyConnector {

    // OSC 7 format: \033]7;file://hostname/path\033\\ or \033]7;file://hostname/path\007
    private static final Pattern OSC7_PATTERN = Pattern.compile(
        "\\033\\]7;file://[^/]*(/.+?)(?:\\033\\\\|\\007)"
    );

    private final TtyConnector delegate;
    private final Consumer<String> cwdListener;
    private final StringBuilder buffer = new StringBuilder();

    public OscTrackingTtyConnector(@NotNull TtyConnector delegate,
                                    @NotNull Consumer<String> cwdListener) {
        this.delegate = delegate;
        this.cwdListener = cwdListener;
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        int count = delegate.read(buf, offset, length);
        if (count > 0) {
            buffer.append(buf, offset, count);
            extractOsc7();
            // Keep buffer bounded — only need the tail for partial sequence matching
            if (buffer.length() > 4096) {
                buffer.delete(0, buffer.length() - 512);
            }
        }
        return count;
    }

    private void extractOsc7() {
        Matcher matcher = OSC7_PATTERN.matcher(buffer);
        String lastCwd = null;
        int lastEnd = 0;
        while (matcher.find()) {
            lastCwd = matcher.group(1);
            lastEnd = matcher.end();
        }
        if (lastCwd != null) {
            cwdListener.accept(lastCwd);
            buffer.delete(0, lastEnd);
        }
    }

    @Override public boolean isConnected() { return delegate.isConnected(); }
    @Override public void write(byte[] bytes) throws IOException { delegate.write(bytes); }
    @Override public void write(String string) throws IOException { delegate.write(string); }
    @Override public int waitFor() throws InterruptedException { return delegate.waitFor(); }
    @Override public boolean ready() throws IOException { return delegate.ready(); }
    @Override public String getName() { return delegate.getName(); }
    @Override public void close() { delegate.close(); }
    @Override public void resize(@NotNull Dimension termWinSize) { delegate.resize(termWinSize); }
}
```

- [ ] **Step 3: Wire CWD tracking into `ConchTerminalEditor`**

Update `ConchTerminalEditor.java` — modify the `initTerminalSession()` method to wrap the connector:

```java
    private void initTerminalSession() {
        String cwd = file.getCurrentWorkingDirectory();
        if (cwd == null) {
            cwd = System.getProperty("user.home");
        }
        String workDir = cwd;

        TerminalSessionProvider.SessionContext context = () -> workDir;
        TtyConnector rawConnector = file.getProvider().createSession(context);

        if (rawConnector != null) {
            // Wrap with OSC 7 tracking for CWD sync
            connector = new OscTrackingTtyConnector(rawConnector, newCwd -> {
                file.setCurrentWorkingDirectory(newCwd);
                CwdSyncManager cwdSync = CwdSyncManager.getInstance(project);
                cwdSync.onWorkingDirectoryChanged(newCwd);
            });

            terminalWidget.createTerminalSession(connector);
            terminalWidget.start();
        }
    }
```

Add these imports to `ConchTerminalEditor.java`:
```java
import com.conch.core.explorer.CwdSyncManager;
```

- [ ] **Step 4: Register CwdSyncManager service in `plugin.xml`**

Add to `<extensions>`:

```xml
        <projectService
            serviceImplementation="com.conch.core.explorer.CwdSyncManager"/>
```

- [ ] **Step 5: Verify CWD sync**

Run:
```bash
./gradlew :conch-core:runIde
```

1. Open a project with the Project View visible (View > Tool Windows > Project)
2. `Cmd+T` to open a terminal
3. `cd /tmp` in the terminal
4. The Project View should navigate to `/tmp`
5. `cd ~/projects` — Project View should follow

Note: OSC 7 emission depends on the shell. If it doesn't work immediately, add to `~/.zshrc`:
```bash
function chpwd() { printf '\033]7;file://%s%s\033\\' "$HOST" "$PWD" }
```

Expected: File explorer follows terminal CWD.

- [ ] **Step 6: Commit**

```bash
git add conch-core/src/main/java/com/conch/core/explorer/ conch-core/src/main/java/com/conch/core/terminal/OscTrackingTtyConnector.java conch-core/src/main/java/com/conch/core/terminal/ConchTerminalEditor.java conch-core/src/main/resources/META-INF/plugin.xml
git commit -m "feat: CWD-aware file explorer — OSC 7 tracking syncs Project View"
```

---

## Task 8: Command Palette

**Files:**
- Create: `conch-core/src/main/java/com/conch/core/palette/TerminalPaletteContributor.java`
- Modify: `conch-core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `TerminalPaletteContributor.java`**

A Search Everywhere contributor that searches open terminal tabs.

```java
package com.conch.core.palette;

import com.conch.core.terminal.ConchTerminalVirtualFile;
import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.searcheverywhere.AbstractGotoSEContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.intellij.ide.util.gotoByName.FilteringGotoByModel;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Search Everywhere contributor that lists and searches open terminal tabs.
 * Selecting a result focuses that terminal tab.
 */
public final class TerminalPaletteContributor implements SearchEverywhereContributor<Object> {

    private final Project project;

    public TerminalPaletteContributor(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String getSearchProviderId() {
        return "ConchTerminals";
    }

    @Override
    public @NotNull String getGroupName() {
        return "Terminals";
    }

    @Override
    public int getSortWeight() {
        return 100;
    }

    @Override
    public boolean showInFindResults() {
        return false;
    }

    @Override
    public void fetchElements(@NotNull String pattern,
                               @NotNull ProgressIndicator progressIndicator,
                               @NotNull Processor<? super Object> consumer) {
        FileEditorManager manager = FileEditorManager.getInstance(project);
        String lowerPattern = pattern.toLowerCase();

        for (VirtualFile file : manager.getOpenFiles()) {
            if (progressIndicator.isCanceled()) return;

            if (file instanceof ConchTerminalVirtualFile termFile) {
                String title = termFile.getName().toLowerCase();
                String cwd = termFile.getCurrentWorkingDirectory();
                String cwdLower = cwd != null ? cwd.toLowerCase() : "";

                if (lowerPattern.isEmpty()
                    || title.contains(lowerPattern)
                    || cwdLower.contains(lowerPattern)) {
                    consumer.process(termFile);
                }
            }
        }
    }

    @Override
    public boolean processSelectedItem(@NotNull Object selected, int modifiers, @NotNull String searchText) {
        if (selected instanceof ConchTerminalVirtualFile termFile) {
            FileEditorManager.getInstance(project).openFile(termFile, true);
            return true;
        }
        return false;
    }

    @Override
    public @NotNull ListCellRenderer<? super Object> getElementsRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof ConchTerminalVirtualFile termFile) {
                    String label = termFile.getName();
                    String cwd = termFile.getCurrentWorkingDirectory();
                    if (cwd != null) {
                        label += "  —  " + cwd;
                    }
                    setText(label);
                    setIcon(AllIcons.Debugger.Console);
                }
                return this;
            }
        };
    }

    @Override
    public @NotNull Object getDataForItem(@NotNull Object element, @NotNull String dataId) {
        return element;
    }

    /**
     * Factory that registers this contributor with Search Everywhere.
     */
    public static final class Factory implements SearchEverywhereContributorFactory<Object> {

        @Override
        public @NotNull SearchEverywhereContributor<Object> createContributor(@NotNull AnActionEvent initEvent) {
            Project project = initEvent.getProject();
            assert project != null;
            return new TerminalPaletteContributor(project);
        }
    }
}
```

- [ ] **Step 2: Register the contributor and configure shortcuts in `plugin.xml`**

Add to `<extensions>`:

```xml
        <searchEverywhereContributor
            implementation="com.conch.core.palette.TerminalPaletteContributor$Factory"/>
```

Add to `<actions>` to remap Search Everywhere to `Cmd+Shift+P`:

```xml
        <action id="Conch.CommandPalette"
                class="com.intellij.ide.actions.SearchEverywhereAction"
                text="Command Palette"
                description="Open the Conch command palette">
            <keyboard-shortcut keymap="$default"
                               first-keystroke="meta shift P"/>
        </action>
```

- [ ] **Step 3: Disable default Search Everywhere contributors**

To hide the Files, Classes, Symbols, and Text tabs, we don't need to do anything special — those contributors are registered by language plugins (Java, Kotlin, etc.) which we won't bundle. Without those plugins, only "All" and "Actions" tabs appear from the platform, plus our "Terminals" tab.

Verify this when testing — if unwanted tabs appear, they can be suppressed by creating a `SearchEverywhereContributor` with the same ID that returns no results, or by unregistering them in a startup activity.

- [ ] **Step 4: Verify command palette**

Run:
```bash
./gradlew :conch-core:runIde
```

1. Open a project, `Cmd+T` to create a few terminals
2. `cd` to different directories in each
3. Press `Cmd+Shift+P` — the Search Everywhere dialog should open
4. You should see tabs: "All", "Actions", "Terminals"
5. Type part of a directory name — matching terminal tabs should appear
6. Select one — it should focus that terminal tab
7. The "Actions" tab should list all registered Conch actions

Expected: Command palette with Terminals and Actions tabs only.

- [ ] **Step 5: Commit**

```bash
git add conch-core/src/main/java/com/conch/core/palette/ conch-core/src/main/resources/META-INF/plugin.xml
git commit -m "feat: command palette — search terminals and actions via Cmd+Shift+P"
```

---

## Task 9: Mini-Window

**Files:**
- Create: `conch-core/src/main/java/com/conch/core/miniwindow/MiniTerminalWindow.java`
- Create: `conch-core/src/main/java/com/conch/core/actions/OpenMiniWindowAction.java`
- Modify: `conch-core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `MiniTerminalWindow.java`**

A bare JFrame with just a JediTerm widget. No tool windows, no chrome.

```java
package com.conch.core.miniwindow;

import com.conch.core.terminal.ConchTerminalSettings;
import com.conch.core.terminal.LocalPtySessionProvider;
import com.conch.sdk.TerminalSessionProvider;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * A minimal, chrome-free terminal window for quick one-off commands.
 * Opened via Cmd+Shift+N. Ephemeral — not part of workspace persistence.
 */
public final class MiniTerminalWindow {

    private final JFrame frame;
    private final JediTermWidget terminalWidget;
    private TtyConnector connector;

    public MiniTerminalWindow() {
        frame = new JFrame("Conch — Quick Terminal");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(800, 500);
        frame.setLocationRelativeTo(null);

        terminalWidget = new JediTermWidget(new ConchTerminalSettings());

        // Start a local PTY session
        LocalPtySessionProvider ptyProvider = new LocalPtySessionProvider();
        TerminalSessionProvider.SessionContext context =
            () -> System.getProperty("user.home");
        connector = ptyProvider.createSession(context);

        if (connector != null) {
            terminalWidget.createTerminalSession(connector);
            terminalWidget.start();
        }

        frame.getContentPane().add(terminalWidget, BorderLayout.CENTER);

        // Close window on Cmd+W or Escape
        KeyStroke cmdW = KeyStroke.getKeyStroke(KeyEvent.VK_W,
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);

        JRootPane rootPane = frame.getRootPane();
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(cmdW, "closeWindow");
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(escape, "closeWindow");
        rootPane.getActionMap().put("closeWindow", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                close();
            }
        });

        // Clean up PTY on window close
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (connector != null) {
                    try { connector.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    public void show() {
        frame.setVisible(true);
        terminalWidget.requestFocusInWindow();
    }

    public void close() {
        frame.dispose();
    }
}
```

- [ ] **Step 2: Create `OpenMiniWindowAction.java`**

```java
package com.conch.core.actions;

import com.conch.core.miniwindow.MiniTerminalWindow;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

public final class OpenMiniWindowAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        new MiniTerminalWindow().show();
    }
}
```

- [ ] **Step 3: Register the action in `plugin.xml`**

Add inside the `Conch.TerminalActions` group:

```xml
            <separator/>

            <action id="Conch.OpenMiniWindow"
                    class="com.conch.core.actions.OpenMiniWindowAction"
                    text="Quick Terminal"
                    description="Open a lightweight terminal window">
                <keyboard-shortcut keymap="$default"
                                   first-keystroke="meta shift N"/>
            </action>
```

- [ ] **Step 4: Verify mini-window**

Run:
```bash
./gradlew :conch-core:runIde
```

1. Press `Cmd+Shift+N` — a bare terminal window should appear
2. Type commands, verify it works (ls, cd, etc.)
3. `Cmd+W` or `Escape` should close it
4. Open multiple mini-windows — each should be independent
5. Close the main IDE — mini-windows should also close (JFrame disposal)
6. Relaunch IDE — no mini-windows should restore (they're ephemeral)

Expected: Quick, disposable terminal windows.

- [ ] **Step 5: Commit**

```bash
git add conch-core/src/main/java/com/conch/core/miniwindow/ conch-core/src/main/java/com/conch/core/actions/OpenMiniWindowAction.java conch-core/src/main/resources/META-INF/plugin.xml
git commit -m "feat: mini-window — ephemeral quick terminal via Cmd+Shift+N"
```

---

## Task 10: Platform Stripping and Save/Load Workspace Actions

**Files:**
- Create: `conch-core/src/main/java/com/conch/core/actions/SaveWorkspaceAction.java`
- Create: `conch-core/src/main/java/com/conch/core/actions/LoadWorkspaceAction.java`
- Modify: `conch-core/src/main/resources/META-INF/plugin.xml`
- Modify: `conch-core/build.gradle.kts`

- [ ] **Step 1: Create `SaveWorkspaceAction.java`**

```java
package com.conch.core.actions;

import com.conch.core.workspace.WorkspaceManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

public final class SaveWorkspaceAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        String name = Messages.showInputDialog(
            project,
            "Workspace name:",
            "Save Workspace As",
            null
        );

        if (name != null && !name.isBlank()) {
            WorkspaceManager.getInstance(project).saveAs(name.trim());
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
```

- [ ] **Step 2: Create `LoadWorkspaceAction.java`**

```java
package com.conch.core.actions;

import com.conch.core.workspace.WorkspaceManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class LoadWorkspaceAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        WorkspaceManager manager = WorkspaceManager.getInstance(project);
        List<String> workspaces = manager.listWorkspaces();

        if (workspaces.isEmpty()) {
            return;
        }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(workspaces)
            .setTitle("Load Workspace")
            .setItemChosenCallback(name -> {
                // Close all open terminal tabs first
                FileEditorManager editorManager = FileEditorManager.getInstance(project);
                for (VirtualFile file : editorManager.getOpenFiles()) {
                    editorManager.closeFile(file);
                }
                // Restore the selected workspace
                manager.restore(name + ".json");
            })
            .createPopup()
            .showCenteredInCurrentWindow(project);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
```

- [ ] **Step 3: Register workspace actions in `plugin.xml`**

Add inside the `Conch.TerminalActions` group:

```xml
            <separator/>

            <action id="Conch.SaveWorkspace"
                    class="com.conch.core.actions.SaveWorkspaceAction"
                    text="Save Workspace As..."
                    description="Save the current workspace layout with a name">
                <keyboard-shortcut keymap="$default"
                                   first-keystroke="meta shift S"/>
            </action>

            <action id="Conch.LoadWorkspace"
                    class="com.conch.core.actions.LoadWorkspaceAction"
                    text="Load Workspace"
                    description="Load a saved workspace">
            </action>
```

- [ ] **Step 4: Configure platform stripping in `build.gradle.kts`**

Update `conch-core/build.gradle.kts` to exclude unwanted bundled plugins and configure the run task:

```kotlin
intellijPlatform {
    pluginConfiguration {
        id = "com.conch.core"
        name = "Conch Core"
        version = project.version.toString()
    }
}

tasks {
    runIde {
        jvmArgs(
            "-Xmx2g",
            // Security hardening (disable for development, enable for production)
            // "-XX:+DisableAttachMechanism",
        )

        // Disable IntelliJ's built-in tips, welcome screen behavior
        systemProperty("nosplash", "true")
        systemProperty("ide.no.tips.dialog", "true")
        systemProperty("ide.show.tips.on.startup.default.value", "false")

        // Disable plugins we don't need (when running in development mode,
        // we're inside IntelliJ CE which has many bundled plugins).
        // In production packaging, we control the plugin list directly.
    }
}
```

Note: Full platform stripping (excluding bundled plugins from the distribution) is a packaging concern. During development with `runIde`, you're running inside IntelliJ CE. For production, you'll define the product's plugin list when configuring jpackage. This is addressed in Plan 4 (packaging & distribution).

- [ ] **Step 5: Verify the complete feature set**

Run:
```bash
./gradlew :conch-core:runIde
```

Full verification checklist:
1. `Cmd+T` — new terminal tab in editor area
2. Type commands, verify terminal works (colors, scrollback, interactive apps)
3. `Cmd+T` again — second tab appears
4. `Cmd+D` — splits horizontally with new terminal
5. `Cmd+Shift+D` — splits vertically with new terminal
6. `Cmd+W` — closes active terminal
7. `cd /some/path` — Project View navigates to that directory
8. `Cmd+Shift+P` — command palette opens with Terminals and Actions tabs
9. Type terminal name in palette — matches appear, selecting focuses the tab
10. `Cmd+Shift+N` — mini-window opens, independent terminal, closeable
11. `Cmd+Shift+S` — save workspace dialog, enter a name
12. Close and relaunch IDE — workspace restores
13. Load Workspace (via command palette action) — switches to saved workspace

Expected: A functional terminal workstation.

- [ ] **Step 6: Commit**

```bash
git add conch-core/
git commit -m "feat: workspace save/load actions, platform stripping config — foundation complete"
```

---

## Final `plugin.xml` Reference

After all tasks, the complete `plugin.xml` should look like this:

```xml
<idea-plugin>
    <id>com.conch.core</id>
    <name>Conch Core</name>
    <version>0.1.0</version>
    <vendor>Conch</vendor>
    <description>Terminal-driven workstation built on IntelliJ Platform</description>

    <depends>com.intellij.modules.platform</depends>

    <!-- Conch-defined extension points for plugins to implement -->
    <extensionPoints>
        <extensionPoint name="terminalSessionProvider"
                        interface="com.conch.sdk.TerminalSessionProvider"
                        dynamic="true"/>
        <extensionPoint name="commandPaletteContributor"
                        interface="com.conch.sdk.CommandPaletteContributor"
                        dynamic="true"/>
        <extensionPoint name="credentialProvider"
                        interface="com.conch.sdk.CredentialProvider"
                        dynamic="true"/>
    </extensionPoints>

    <extensions defaultExtensionNs="com.intellij">
        <fileEditorProvider
            implementation="com.conch.core.terminal.ConchTerminalEditorProvider"/>

        <postStartupActivity
            implementation="com.conch.core.ConchStartupActivity"/>

        <projectService
            serviceImplementation="com.conch.core.workspace.WorkspaceManager"/>

        <projectService
            serviceImplementation="com.conch.core.explorer.CwdSyncManager"/>

        <searchEverywhereContributor
            implementation="com.conch.core.palette.TerminalPaletteContributor$Factory"/>
    </extensions>

    <actions>
        <action id="Conch.CommandPalette"
                class="com.intellij.ide.actions.SearchEverywhereAction"
                text="Command Palette"
                description="Open the Conch command palette">
            <keyboard-shortcut keymap="$default"
                               first-keystroke="meta shift P"/>
        </action>

        <group id="Conch.TerminalActions" text="Terminal" popup="true">
            <add-to-group group-id="MainMenu" anchor="before" relative-to-action="HelpMenu"/>

            <action id="Conch.NewTerminalTab"
                    class="com.conch.core.actions.NewTerminalTabAction"
                    text="New Terminal Tab"
                    description="Open a new local terminal tab">
                <keyboard-shortcut keymap="$default"
                                   first-keystroke="meta T"/>
            </action>

            <action id="Conch.CloseTerminalTab"
                    class="com.conch.core.actions.CloseTerminalTabAction"
                    text="Close Terminal Tab"
                    description="Close the active terminal tab">
                <keyboard-shortcut keymap="$default"
                                   first-keystroke="meta W"/>
            </action>

            <action id="Conch.SplitHorizontal"
                    class="com.conch.core.actions.SplitTerminalHorizontalAction"
                    text="Split Terminal Right"
                    description="Split and open a new terminal to the right">
                <keyboard-shortcut keymap="$default"
                                   first-keystroke="meta D"/>
            </action>

            <action id="Conch.SplitVertical"
                    class="com.conch.core.actions.SplitTerminalVerticalAction"
                    text="Split Terminal Down"
                    description="Split and open a new terminal below">
                <keyboard-shortcut keymap="$default"
                                   first-keystroke="meta shift D"/>
            </action>

            <separator/>

            <action id="Conch.OpenMiniWindow"
                    class="com.conch.core.actions.OpenMiniWindowAction"
                    text="Quick Terminal"
                    description="Open a lightweight terminal window">
                <keyboard-shortcut keymap="$default"
                                   first-keystroke="meta shift N"/>
            </action>

            <separator/>

            <action id="Conch.SaveWorkspace"
                    class="com.conch.core.actions.SaveWorkspaceAction"
                    text="Save Workspace As..."
                    description="Save the current workspace layout">
                <keyboard-shortcut keymap="$default"
                                   first-keystroke="meta shift S"/>
            </action>

            <action id="Conch.LoadWorkspace"
                    class="com.conch.core.actions.LoadWorkspaceAction"
                    text="Load Workspace"
                    description="Load a saved workspace">
            </action>
        </group>
    </actions>
</idea-plugin>
```

---

## Implementation Notes

**IntelliJ Platform API verification:** Some APIs used in this plan (particularly `FileEditorManagerEx.createSplitter()`, `SearchEverywhereContributor`, and `ProjectActivity`) may have signature differences across platform versions. If a method signature doesn't match, consult the IntelliJ Platform SDK docs or the platform source for the exact version specified in `gradle.properties`.

**JediTerm API verification:** The standalone JediTerm library (`jediterm-core` / `jediterm-ui`) API may differ from the version bundled inside IntelliJ's terminal plugin. Verify `JediTermWidget.createTerminalSession()` and `ProcessTtyConnector` constructors against the actual published artifacts.

**pty4j native libraries:** pty4j bundles native libraries for macOS, Linux, and Windows. The Gradle dependency should handle this automatically. If you see `UnsatisfiedLinkError` at runtime, ensure the pty4j JAR's native libs are extracted properly (the IntelliJ Platform's classloader usually handles this).

**Shell OSC 7 support:** macOS zsh emits OSC 7 by default in recent versions. If CWD sync doesn't work, the shell needs to be configured to emit it (see Task 7, Step 5).

**Shortcut conflicts:** Some shortcuts (`Cmd+T`, `Cmd+W`, `Cmd+D`) conflict with IntelliJ's built-in actions (new editor tab, close editor, show documentation). Our actions override them because they're registered with the same keymap shortcuts. If conflicts arise, adjust priorities or use `overrides="true"` in the action registration.
