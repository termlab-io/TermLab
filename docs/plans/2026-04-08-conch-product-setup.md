# Conch Product Setup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Set up Conch as a custom IntelliJ Platform product built from the intellij-community source tree, with terminal tabs as the primary viewport — no IDE plugins, no Java, no VCS, only what a terminal workstation needs.

**Architecture:** Conch is defined as a product inside `~/projects/intellij-community/conch/`. A `ConchProperties.kt` class specifies `essentialMinimal()` as the module set (editor area, tool windows, docking, search everywhere, settings, theming) with only 6 bundled plugins (conch-core, sh, textmate, yaml, toml, json). The conch-core plugin provides JediTerm terminal in the editor area, local PTY, workspace persistence, CWD sync, command palette, and mini-window.

**Tech Stack:** Kotlin (build scripts) + Java (plugin code), IntelliJ Platform from source, JediTerm + pty4j (bundled in platform's `librariesIde`), JPS module system

**Reference spec:** `/Users/dustin/projects/conch_2/docs/superpowers/specs/2026-04-08-conch-workstation-design.md`

---

## File Structure

All paths relative to `~/projects/intellij-community/`.

```
conch/
├── build/src/
│   ├── ConchInstallersBuildTarget.kt                    # Build entry point
│   └── org/jetbrains/intellij/build/conch/
│       └── ConchProperties.kt                           # Product configuration
├── customization/
│   ├── resources/
│   │   ├── idea/ConchApplicationInfo.xml                # Product branding
│   │   └── META-INF/ConchPlugin.xml                     # Product plugin descriptor
│   └── intellij.conch.customization.iml                 # Module definition
├── sdk/
│   ├── src/com/conch/sdk/
│   │   ├── TerminalSessionProvider.java                 # Extension point interface
│   │   ├── CommandPaletteContributor.java                # Extension point interface
│   │   ├── CredentialProvider.java                       # Extension point interface
│   │   └── PaletteItem.java                              # Data class
│   └── intellij.conch.sdk.iml                           # Module definition
├── core/
│   ├── src/com/conch/core/
│   │   ├── ConchStartupActivity.java                    # Restore workspace on startup
│   │   ├── ConchProjectCloseListener.java               # Save workspace on close
│   │   ├── terminal/
│   │   │   ├── ConchTerminalFileType.java
│   │   │   ├── ConchTerminalVirtualFile.java
│   │   │   ├── ConchTerminalEditorProvider.java
│   │   │   ├── ConchTerminalEditor.java
│   │   │   ├── ConchTerminalSettings.java
│   │   │   ├── LocalPtySessionProvider.java
│   │   │   └── OscTrackingTtyConnector.java
│   │   ├── workspace/
│   │   │   ├── WorkspaceState.java
│   │   │   ├── WorkspaceManager.java
│   │   │   └── WorkspaceSerializer.java
│   │   ├── explorer/
│   │   │   └── CwdSyncManager.java
│   │   ├── palette/
│   │   │   └── TerminalPaletteContributor.java
│   │   ├── miniwindow/
│   │   │   └── MiniTerminalWindow.java
│   │   └── actions/
│   │       ├── NewTerminalTabAction.java
│   │       ├── CloseTerminalTabAction.java
│   │       ├── SplitTerminalHorizontalAction.java
│   │       ├── SplitTerminalVerticalAction.java
│   │       ├── OpenMiniWindowAction.java
│   │       ├── SaveWorkspaceAction.java
│   │       └── LoadWorkspaceAction.java
│   ├── resources/META-INF/plugin.xml                    # Conch Core plugin descriptor
│   └── intellij.conch.core.iml                          # Module definition
├── intellij.conch.main.iml                              # Run configuration module
└── docs/plans/                                          # This plan
```

Also modified:
- `.idea/modules.xml` — register new modules
- `.idea/runConfigurations/Conch.xml` — run/debug configuration

---

## Task 1: Create Module Definitions (.iml files)

**Files:**
- Create: `conch/sdk/intellij.conch.sdk.iml`
- Create: `conch/core/intellij.conch.core.iml`
- Create: `conch/customization/intellij.conch.customization.iml`
- Create: `conch/intellij.conch.main.iml`
- Modify: `.idea/modules.xml`

- [ ] **Step 1: Create `conch/sdk/intellij.conch.sdk.iml`**

The SDK module has no IntelliJ Platform dependencies — it only needs JediTerm for the `TtyConnector` interface.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<module type="JAVA_MODULE" version="4">
  <component name="NewModuleRootManager" inherit-compiler-output="true">
    <exclude-output />
    <content url="file://$MODULE_DIR$">
      <sourceFolder url="file://$MODULE_DIR$/src" isTestSource="false" />
    </content>
    <orderEntry type="inheritedJdk" />
    <orderEntry type="sourceFolder" forTests="false" />
    <orderEntry type="module" module-name="intellij.libraries.jediterm.core" />
    <orderEntry type="library" name="jetbrains-annotations" level="project" />
  </component>
</module>
```

- [ ] **Step 2: Create `conch/core/intellij.conch.core.iml`**

The core plugin module depends on the SDK, platform APIs, and JediTerm UI.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<module type="JAVA_MODULE" version="4">
  <component name="NewModuleRootManager" inherit-compiler-output="true">
    <exclude-output />
    <content url="file://$MODULE_DIR$">
      <sourceFolder url="file://$MODULE_DIR$/src" isTestSource="false" />
      <sourceFolder url="file://$MODULE_DIR$/resources" type="java-resource" />
    </content>
    <orderEntry type="inheritedJdk" />
    <orderEntry type="sourceFolder" forTests="false" />
    <orderEntry type="module" module-name="intellij.conch.sdk" />
    <orderEntry type="module" module-name="intellij.platform.core" />
    <orderEntry type="module" module-name="intellij.platform.ide" />
    <orderEntry type="module" module-name="intellij.platform.ide.impl" />
    <orderEntry type="module" module-name="intellij.platform.lang" />
    <orderEntry type="module" module-name="intellij.platform.lang.impl" />
    <orderEntry type="module" module-name="intellij.platform.editor" />
    <orderEntry type="module" module-name="intellij.platform.projectModel" />
    <orderEntry type="module" module-name="intellij.libraries.jediterm.core" />
    <orderEntry type="module" module-name="intellij.libraries.jediterm.ui" />
    <orderEntry type="module" module-name="intellij.libraries.pty4j" />
    <orderEntry type="library" name="jetbrains-annotations" level="project" />
    <orderEntry type="library" name="Gson" level="project" />
  </component>
</module>
```

Note: The exact library names (`jetbrains-annotations`, `Gson`) and module names must match what's defined in the IntelliJ project. Verify by searching `.idea/libraries/` for the correct names. If `Gson` isn't a project library, use the module `intellij.libraries.gson` if it exists, or find the correct reference.

- [ ] **Step 3: Create `conch/customization/intellij.conch.customization.iml`**

The customization module holds branding (ApplicationInfo.xml) and the product plugin descriptor.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<module type="JAVA_MODULE" version="4">
  <component name="NewModuleRootManager" inherit-compiler-output="true">
    <exclude-output />
    <content url="file://$MODULE_DIR$">
      <sourceFolder url="file://$MODULE_DIR$/resources" type="java-resource" />
    </content>
    <orderEntry type="inheritedJdk" />
    <orderEntry type="sourceFolder" forTests="false" />
  </component>
</module>
```

- [ ] **Step 4: Create `conch/intellij.conch.main.iml`**

The main module for the run configuration — lists all runtime dependencies.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<module type="JAVA_MODULE" version="4">
  <component name="NewModuleRootManager" inherit-compiler-output="true">
    <exclude-output />
    <orderEntry type="inheritedJdk" />
    <orderEntry type="sourceFolder" forTests="false" />
    <orderEntry type="module" module-name="intellij.platform.monolith.main" scope="RUNTIME" />
    <orderEntry type="module" module-name="intellij.platform.bootstrap" scope="RUNTIME" />
    <orderEntry type="module" module-name="intellij.platform.starter" />
    <orderEntry type="module" module-name="intellij.conch.customization" />
    <orderEntry type="module" module-name="intellij.conch.core" />
    <orderEntry type="module" module-name="intellij.conch.sdk" scope="RUNTIME" />
    <orderEntry type="module" module-name="intellij.sh.plugin.main" scope="RUNTIME" />
    <orderEntry type="module" module-name="intellij.textmate.plugin" scope="RUNTIME" />
    <orderEntry type="module" module-name="intellij.searchEverywhereMl" scope="RUNTIME" />
  </component>
</module>
```

- [ ] **Step 5: Register all modules in `.idea/modules.xml`**

Add these lines inside the `<modules>` element (alphabetical order among existing entries):

```xml
      <module fileurl="file://$PROJECT_DIR$/conch/core/intellij.conch.core.iml" filepath="$PROJECT_DIR$/conch/core/intellij.conch.core.iml" />
      <module fileurl="file://$PROJECT_DIR$/conch/customization/intellij.conch.customization.iml" filepath="$PROJECT_DIR$/conch/customization/intellij.conch.customization.iml" />
      <module fileurl="file://$PROJECT_DIR$/conch/intellij.conch.main.iml" filepath="$PROJECT_DIR$/conch/intellij.conch.main.iml" />
      <module fileurl="file://$PROJECT_DIR$/conch/sdk/intellij.conch.sdk.iml" filepath="$PROJECT_DIR$/conch/sdk/intellij.conch.sdk.iml" />
```

- [ ] **Step 6: Create directory structure**

```bash
cd ~/projects/intellij-community
mkdir -p conch/build/src/org/jetbrains/intellij/build/conch
mkdir -p conch/customization/resources/idea
mkdir -p conch/customization/resources/META-INF
mkdir -p conch/sdk/src/com/conch/sdk
mkdir -p conch/core/src/com/conch/core/terminal
mkdir -p conch/core/src/com/conch/core/workspace
mkdir -p conch/core/src/com/conch/core/explorer
mkdir -p conch/core/src/com/conch/core/palette
mkdir -p conch/core/src/com/conch/core/miniwindow
mkdir -p conch/core/src/com/conch/core/actions
mkdir -p conch/core/resources/META-INF
mkdir -p conch/docs/plans
```

- [ ] **Step 7: Commit**

```bash
git add conch/ .idea/modules.xml
git commit -m "feat(conch): add module definitions and directory structure"
```

---

## Task 2: Product Branding and Configuration

**Files:**
- Create: `conch/customization/resources/idea/ConchApplicationInfo.xml`
- Create: `conch/customization/resources/META-INF/ConchPlugin.xml`
- Create: `conch/build/src/org/jetbrains/intellij/build/conch/ConchProperties.kt`
- Create: `conch/build/src/ConchInstallersBuildTarget.kt`

- [ ] **Step 1: Create `ConchApplicationInfo.xml`**

```xml
<component xmlns="http://jetbrains.org/intellij/schema/application-info">
  <version major="0" minor="1"/>
  <company name="Conch" url="https://github.com/conch"/>
  <build number="CN-__BUILD__" date="__BUILD_DATE__"/>
  <names product="Conch" fullname="Conch Workbench" script="conch"
         motto="Terminal-Driven Workstation"/>
  <essential-plugin>com.conch.core</essential-plugin>
  <essential-plugin>com.intellij.modules.json</essential-plugin>
</component>
```

Note: Icons (logo, svg) are omitted for now. They can be added later as SVG files. The build system will warn but won't fail without them.

- [ ] **Step 2: Create `ConchPlugin.xml`**

This is the product plugin descriptor — it defines what the Conch product includes at the platform level.

```xml
<idea-plugin xmlns:xi="http://www.w3.org/2001/XInclude">
  <module value="com.conch.modules.terminal"/>

  <xi:include href="/META-INF/PlatformLangPlugin.xml"/>

  <content namespace="conch">
  </content>
</idea-plugin>
```

Note: The exact content of this file may need adjustment based on what `essentialMinimal()` generates. The generator tool will help validate this. Start minimal and add includes as needed.

- [ ] **Step 3: Create `ConchProperties.kt`**

```kotlin
package org.jetbrains.intellij.build.conch

import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.intellij.build.ApplicationInfoProperties
import org.jetbrains.intellij.build.JetBrainsProductProperties
import org.jetbrains.intellij.build.productLayout.CommunityModuleSets
import org.jetbrains.intellij.build.productLayout.ProductModulesContentSpec
import org.jetbrains.intellij.build.productLayout.productModules
import java.nio.file.Path

class ConchProperties(private val communityHomeDir: Path) : JetBrainsProductProperties() {
  init {
    platformPrefix = "Conch"
    applicationInfoModule = "intellij.conch.customization"
    scrambleMainJar = false
    useSplash = false
    buildCrossPlatformDistribution = false

    productLayout.productImplementationModules = listOf(
      "intellij.platform.starter",
      "intellij.conch.customization",
    )

    productLayout.bundledPluginModules = persistentListOf(
      "intellij.conch.core",
      "intellij.sh.plugin",
      "intellij.textmate.plugin",
      "intellij.yaml",
      "intellij.toml",
      "intellij.json",
    )

    productLayout.skipUnresolvedContentModules = true
  }

  override val baseFileName: String
    get() = "conch"

  override fun getProductContentDescriptor(): ProductModulesContentSpec = productModules {
    alias("com.conch.modules.terminal")

    moduleSet(CommunityModuleSets.essentialMinimal())

    allowMissingDependencies(
      "com.intellij.modules.java",
      "com.intellij.modules.idea",
      "com.intellij.modules.lang",
    )

    bundledPlugins(productLayout.bundledPluginModules)
  }

  override fun getSystemSelector(appInfo: ApplicationInfoProperties, buildNumber: String): String {
    return "Conch${appInfo.majorVersion}.${appInfo.minorVersionMainPart}"
  }

  override fun getBaseArtifactName(appInfo: ApplicationInfoProperties, buildNumber: String): String {
    return "conch-$buildNumber"
  }

  override fun getOutputDirectoryName(appInfo: ApplicationInfoProperties): String = "conch"
}
```

Note: The `allowMissingDependencies` list may need to be expanded based on validation errors. The `skipUnresolvedContentModules = true` helps during initial setup. These can be tightened once the product builds cleanly.

- [ ] **Step 4: Create `ConchInstallersBuildTarget.kt`**

```kotlin
package org.jetbrains.intellij.build.conch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.BuildPaths.Companion.COMMUNITY_ROOT
import org.jetbrains.intellij.build.buildDistributions
import org.jetbrains.intellij.build.impl.createBuildContext

object ConchInstallersBuildTarget {
  @JvmStatic
  fun main(args: Array<String>) {
    runBlocking(Dispatchers.Default) {
      val options = BuildOptions().apply {
        incrementalCompilation = true
        useCompiledClassesFromProjectOutput = false
        buildStepsToSkip += listOf(
          BuildOptions.MAC_SIGN_STEP,
          BuildOptions.WIN_SIGN_STEP,
          BuildOptions.CROSS_PLATFORM_DISTRIBUTION_STEP,
          BuildOptions.SOURCES_ARCHIVE_STEP,
        )
      }
      val context = createBuildContext(
        projectHome = COMMUNITY_ROOT.communityRoot,
        productProperties = ConchProperties(COMMUNITY_ROOT.communityRoot),
        options = options,
      )
      buildDistributions(context)
    }
  }
}
```

- [ ] **Step 5: Commit**

```bash
git add conch/build/ conch/customization/
git commit -m "feat(conch): product properties, branding, and build target"
```

---

## Task 3: Run Configuration

**Files:**
- Create: `.idea/runConfigurations/Conch.xml`

- [ ] **Step 1: Create the run configuration**

```xml
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="Conch" type="Application" factoryName="Application">
    <option name="ALTERNATIVE_JRE_PATH" value="BUNDLED" />
    <option name="ALTERNATIVE_JRE_PATH_ENABLED" value="true" />
    <log_file alias="idea.log" path="$PROJECT_DIR$/system/conch/log/idea.log" />
    <option name="MAIN_CLASS_NAME" value="com.intellij.idea.Main" />
    <module name="intellij.conch.main" />
    <shortenClasspath name="ARGS_FILE" />
    <option name="VM_PARAMETERS" value="-Xmx2g -XX:ReservedCodeCacheSize=240m -XX:SoftRefLRUPolicyMSPerMB=50 -XX:MaxJavaStackTraceDepth=10000 -ea -Dsun.io.useCanonCaches=false -Dapple.laf.useScreenMenuBar=true -Dsun.awt.disablegrab=true -Didea.jre.check=true -Didea.is.internal=true -Didea.debug.mode=true -Djdk.attach.allowAttachSelf -Dfus.internal.test.mode=true -Dkotlinx.coroutines.debug=off -Djdk.module.illegalAccess.silent=true --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.nio.charset=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.base/java.time=ALL-UNNAMED --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/jdk.internal.vm=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED --add-opens=java.desktop/com.apple.laf=ALL-UNNAMED --add-opens=java.desktop/com.sun.java.swing.plaf.gtk=ALL-UNNAMED --add-opens=java.desktop/java.awt.dnd.peer=ALL-UNNAMED --add-opens=java.desktop/java.awt.event=ALL-UNNAMED --add-opens=java.desktop/java.awt.image=ALL-UNNAMED --add-opens=java.desktop/java.awt.peer=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED --add-opens=java.desktop/javax.swing.text=ALL-UNNAMED --add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED --add-opens=java.desktop/javax.swing.text.html.parser=ALL-UNNAMED --add-opens=java.desktop/javax.swing=ALL-UNNAMED --add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED --add-opens=java.desktop/sun.awt.datatransfer=ALL-UNNAMED --add-opens=java.desktop/sun.awt.image=ALL-UNNAMED --add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED --add-opens=java.desktop/sun.awt=ALL-UNNAMED --add-opens=java.desktop/sun.font=ALL-UNNAMED --add-opens=java.desktop/sun.java2d=ALL-UNNAMED --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED --add-opens=java.desktop/sun.swing=ALL-UNNAMED --add-opens=jdk.attach/sun.tools.attach=ALL-UNNAMED --add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED --add-opens=jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED --add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED -Didea.platform.prefix=Conch -Didea.config.path=../config/conch -Didea.system.path=../system/conch -Didea.trust.all.projects=true -Dnosplash=true -Dide.no.tips.dialog=true" />
    <option name="WORKING_DIRECTORY" value="$PROJECT_DIR$/bin" />
    <method v="2">
      <option name="Make" enabled="true" />
    </method>
  </configuration>
</component>
```

The critical VM parameters:
- `-Didea.platform.prefix=Conch` — must match `ConchProperties.platformPrefix`
- `-Didea.config.path=../config/conch` — separate config from other products
- `-Didea.system.path=../system/conch` — separate system cache
- `-Didea.trust.all.projects=true` — bypass trust dialog
- `-Dnosplash=true` — skip splash screen

- [ ] **Step 2: Commit**

```bash
git add .idea/runConfigurations/Conch.xml
git commit -m "feat(conch): add IntelliJ run configuration for Conch"
```

---

## Task 4: Plugin SDK Interfaces

**Files:**
- Create: `conch/sdk/src/com/conch/sdk/TerminalSessionProvider.java`
- Create: `conch/sdk/src/com/conch/sdk/CommandPaletteContributor.java`
- Create: `conch/sdk/src/com/conch/sdk/CredentialProvider.java`
- Create: `conch/sdk/src/com/conch/sdk/PaletteItem.java`

These files are identical to what was created in the standalone project. Copy from `~/projects/conch_2/conch-sdk/src/main/java/com/conch/sdk/`.

- [ ] **Step 1: Copy SDK interfaces from the standalone project**

```bash
cp ~/projects/conch_2/conch-sdk/src/main/java/com/conch/sdk/TerminalSessionProvider.java conch/sdk/src/com/conch/sdk/
cp ~/projects/conch_2/conch-sdk/src/main/java/com/conch/sdk/CommandPaletteContributor.java conch/sdk/src/com/conch/sdk/
cp ~/projects/conch_2/conch-sdk/src/main/java/com/conch/sdk/CredentialProvider.java conch/sdk/src/com/conch/sdk/
cp ~/projects/conch_2/conch-sdk/src/main/java/com/conch/sdk/PaletteItem.java conch/sdk/src/com/conch/sdk/
```

- [ ] **Step 2: Verify the imports work**

The SDK interfaces import `com.jediterm.terminal.TtyConnector`. In the IntelliJ source tree, JediTerm is available via the `intellij.libraries.jediterm.core` module, so the import should resolve. Verify by opening IntelliJ IDEA and checking that the files have no red squiggles.

- [ ] **Step 3: Commit**

```bash
git add conch/sdk/
git commit -m "feat(conch): plugin SDK interfaces — TerminalSessionProvider, CredentialProvider, CommandPaletteContributor"
```

---

## Task 5: Core Plugin Code

**Files:**
- Create: all files under `conch/core/src/com/conch/core/`
- Create: `conch/core/resources/META-INF/plugin.xml`

- [ ] **Step 1: Copy core plugin code from the standalone project**

```bash
# Terminal
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/terminal/ConchTerminalFileType.java conch/core/src/com/conch/core/terminal/
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/terminal/ConchTerminalVirtualFile.java conch/core/src/com/conch/core/terminal/
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/terminal/ConchTerminalEditorProvider.java conch/core/src/com/conch/core/terminal/
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/terminal/ConchTerminalEditor.java conch/core/src/com/conch/core/terminal/
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/terminal/ConchTerminalSettings.java conch/core/src/com/conch/core/terminal/
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/terminal/LocalPtySessionProvider.java conch/core/src/com/conch/core/terminal/
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/terminal/OscTrackingTtyConnector.java conch/core/src/com/conch/core/terminal/

# Workspace
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/workspace/WorkspaceState.java conch/core/src/com/conch/core/workspace/
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/workspace/WorkspaceManager.java conch/core/src/com/conch/core/workspace/
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/workspace/WorkspaceSerializer.java conch/core/src/com/conch/core/workspace/

# Explorer, palette, miniwindow
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/explorer/CwdSyncManager.java conch/core/src/com/conch/core/explorer/
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/palette/TerminalPaletteContributor.java conch/core/src/com/conch/core/palette/
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/miniwindow/MiniTerminalWindow.java conch/core/src/com/conch/core/miniwindow/

# Actions
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/actions/*.java conch/core/src/com/conch/core/actions/

# Startup/close listeners
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/ConchStartupActivity.java conch/core/src/com/conch/core/
cp ~/projects/conch_2/conch-core/src/main/java/com/conch/core/ConchProjectCloseListener.java conch/core/src/com/conch/core/
```

- [ ] **Step 2: Remove `ConchAppLifecycleListener.java`**

This was needed in the standalone project to bypass the welcome screen. In the source build, the product configuration handles this. Do NOT copy `ConchAppLifecycleListener.java`.

- [ ] **Step 3: Copy and verify plugin.xml**

```bash
cp ~/projects/conch_2/conch-core/src/main/resources/META-INF/plugin.xml conch/core/resources/META-INF/plugin.xml
```

Remove the `<applicationListeners>` block that references `ConchAppLifecycleListener` — it doesn't exist in the source build.

The plugin.xml should contain:
- Extension points (terminalSessionProvider, commandPaletteContributor, credentialProvider)
- Extensions (fileEditorProvider, postStartupActivity, projectService x2, searchEverywhereContributor)
- applicationListeners (only ConchProjectCloseListener)
- Actions (all terminal actions, command palette, workspace actions)

- [ ] **Step 4: Fix imports if needed**

In the standalone project, JediTerm classes came from the external jar. In the IntelliJ source tree, they're in platform modules. The import paths should be the same (`com.jediterm.terminal.*`) — verify they resolve.

Similarly, `com.pty4j.*` should resolve from the `intellij.libraries.pty4j` module.

`com.google.gson.*` should resolve from the platform's Gson library.

- [ ] **Step 5: Commit**

```bash
git add conch/core/
git commit -m "feat(conch): core plugin — terminal editor, workspace, CWD sync, palette, mini-window, actions"
```

---

## Task 6: Build and Run

- [ ] **Step 1: Open the IntelliJ project**

Open `~/projects/intellij-community` in IntelliJ IDEA. Wait for indexing to complete. The new Conch modules should appear in the Project view under `conch/`.

- [ ] **Step 2: Fix compilation errors**

Build the project (`Build > Build Project` or `Cmd+F9`). Fix any compilation errors in the Conch modules:
- Missing imports: adjust module dependencies in `.iml` files
- API differences: JediTerm/pty4j classes may have slightly different APIs when used as platform modules vs external jars
- Gson availability: if Gson isn't available via the declared dependency, check `.idea/libraries/` for the correct library name

Common fixes:
- If `com.pty4j.PtyProcessBuilder` can't be found, verify the `intellij.libraries.pty4j` module name is correct
- If `DefaultSettingsProvider` can't be found in JediTerm, check `intellij.libraries.jediterm.ui` module
- If `ProcessTtyConnector` is abstract (as discovered in the standalone project), keep the anonymous subclass from the standalone code

- [ ] **Step 3: Run Conch**

Select the "Conch" run configuration from the dropdown and click Run (or `Shift+F10`).

Expected: A stripped-down IDE window opens with:
- No Java, VCS, Gradle, Maven, or other IDE plugins
- Only the plugins we bundled (sh, textmate, yaml, toml, json, conch-core)
- `ConchStartupActivity` fires, opens a terminal tab
- `Cmd+T` opens new terminal tabs
- The command palette (`Cmd+Shift+P`) shows only Terminals and Actions

- [ ] **Step 4: Verify core functionality**

1. `Cmd+T` — new terminal tab in editor area
2. Type `ls`, `echo $TERM` — terminal works
3. `Cmd+D` — split horizontal
4. `Cmd+Shift+D` — split vertical
5. `Cmd+W` — close tab
6. `Cmd+Shift+P` — command palette with Terminals tab
7. `Cmd+Shift+N` — mini-window
8. Settings > Plugins — only Conch Core + the 5 bundled plugins visible
9. No Structure panel, no Git, no AI sidebar, no Java tools

- [ ] **Step 5: Commit any fixes**

```bash
git add conch/ .idea/
git commit -m "fix(conch): resolve compilation issues for source tree build"
```

---

## Implementation Notes

**Module dependency names:** The `.iml` files reference module names like `intellij.platform.core`, `intellij.libraries.jediterm.ui`, etc. These must exactly match the module names in the IntelliJ project. If a module can't be found, search `.idea/modules.xml` for the correct name, or search for `.iml` files: `find ~/projects/intellij-community -name "intellij.libraries.jediterm*"`.

**Library names:** Project libraries are defined in `.idea/libraries/`. If `Gson` isn't the correct name, check: `ls ~/projects/intellij-community/.idea/libraries/ | grep -i gson`.

**The `essentialMinimal()` module set** already includes `librariesIde()` which bundles JediTerm, pty4j, and JNA. This means our plugin doesn't need to manage these dependencies — they're platform-provided.

**Build target (ConchInstallersBuildTarget)** is for producing a distributable. For day-to-day development, use the IntelliJ IDEA run configuration. The build target is used later for CI/CD and jpackage distribution.

**ConchPlugin.xml vs plugin.xml:** `ConchPlugin.xml` is the product-level descriptor (what the Conch product itself provides). `plugin.xml` in conch-core is the plugin descriptor (what the Conch Core plugin provides). These are separate concerns — the product descriptor is loaded by the platform, the plugin descriptor is loaded by the plugin system.
