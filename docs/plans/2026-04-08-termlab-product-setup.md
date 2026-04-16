# TermLab Product Setup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Set up TermLab as a custom IntelliJ Platform product built from the intellij-community source tree, with terminal tabs as the primary viewport — no IDE plugins, no Java, no VCS, only what a terminal workstation needs.

**Architecture:** TermLab is defined as a product inside `~/projects/intellij-community/termlab/`. A `TermLabProperties.kt` class specifies `essentialMinimal()` as the module set (editor area, tool windows, docking, search everywhere, settings, theming) with only 6 bundled plugins (termlab-core, sh, textmate, yaml, toml, json). The termlab-core plugin provides JediTerm terminal in the editor area, local PTY, workspace persistence, CWD sync, command palette, and mini-window.

**Tech Stack:** Kotlin (build scripts) + Java (plugin code), IntelliJ Platform from source, JediTerm + pty4j (bundled in platform's `librariesIde`), JPS module system

**Reference spec:** `/Users/dustin/projects/termlab_2/docs/superpowers/specs/2026-04-08-termlab-workstation-design.md`

---

## File Structure

All paths relative to `~/projects/intellij-community/`.

```
termlab/
├── build/src/
│   ├── TermLabInstallersBuildTarget.kt                    # Build entry point
│   └── org/jetbrains/intellij/build/termlab/
│       └── TermLabProperties.kt                           # Product configuration
├── customization/
│   ├── resources/
│   │   ├── idea/TermLabApplicationInfo.xml                # Product branding
│   │   └── META-INF/TermLabPlugin.xml                     # Product plugin descriptor
│   └── intellij.termlab.customization.iml                 # Module definition
├── sdk/
│   ├── src/com/termlab/sdk/
│   │   ├── TerminalSessionProvider.java                 # Extension point interface
│   │   ├── CommandPaletteContributor.java                # Extension point interface
│   │   ├── CredentialProvider.java                       # Extension point interface
│   │   └── PaletteItem.java                              # Data class
│   └── intellij.termlab.sdk.iml                           # Module definition
├── core/
│   ├── src/com/termlab/core/
│   │   ├── TermLabStartupActivity.java                    # Restore workspace on startup
│   │   ├── TermLabProjectCloseListener.java               # Save workspace on close
│   │   ├── terminal/
│   │   │   ├── TermLabTerminalFileType.java
│   │   │   ├── TermLabTerminalVirtualFile.java
│   │   │   ├── TermLabTerminalEditorProvider.java
│   │   │   ├── TermLabTerminalEditor.java
│   │   │   ├── TermLabTerminalSettings.java
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
│   ├── resources/META-INF/plugin.xml                    # TermLab Core plugin descriptor
│   └── intellij.termlab.core.iml                          # Module definition
├── intellij.termlab.main.iml                              # Run configuration module
└── docs/plans/                                          # This plan
```

Also modified:
- `.idea/modules.xml` — register new modules
- `.idea/runConfigurations/TermLab.xml` — run/debug configuration

---

## Task 1: Create Module Definitions (.iml files)

**Files:**
- Create: `termlab/sdk/intellij.termlab.sdk.iml`
- Create: `termlab/core/intellij.termlab.core.iml`
- Create: `termlab/customization/intellij.termlab.customization.iml`
- Create: `termlab/intellij.termlab.main.iml`
- Modify: `.idea/modules.xml`

- [ ] **Step 1: Create `termlab/sdk/intellij.termlab.sdk.iml`**

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

- [ ] **Step 2: Create `termlab/core/intellij.termlab.core.iml`**

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
    <orderEntry type="module" module-name="intellij.termlab.sdk" />
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

- [ ] **Step 3: Create `termlab/customization/intellij.termlab.customization.iml`**

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

- [ ] **Step 4: Create `termlab/intellij.termlab.main.iml`**

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
    <orderEntry type="module" module-name="intellij.termlab.customization" />
    <orderEntry type="module" module-name="intellij.termlab.core" />
    <orderEntry type="module" module-name="intellij.termlab.sdk" scope="RUNTIME" />
    <orderEntry type="module" module-name="intellij.sh.plugin.main" scope="RUNTIME" />
    <orderEntry type="module" module-name="intellij.textmate.plugin" scope="RUNTIME" />
    <orderEntry type="module" module-name="intellij.searchEverywhereMl" scope="RUNTIME" />
  </component>
</module>
```

- [ ] **Step 5: Register all modules in `.idea/modules.xml`**

Add these lines inside the `<modules>` element (alphabetical order among existing entries):

```xml
      <module fileurl="file://$PROJECT_DIR$/termlab/core/intellij.termlab.core.iml" filepath="$PROJECT_DIR$/termlab/core/intellij.termlab.core.iml" />
      <module fileurl="file://$PROJECT_DIR$/termlab/customization/intellij.termlab.customization.iml" filepath="$PROJECT_DIR$/termlab/customization/intellij.termlab.customization.iml" />
      <module fileurl="file://$PROJECT_DIR$/termlab/intellij.termlab.main.iml" filepath="$PROJECT_DIR$/termlab/intellij.termlab.main.iml" />
      <module fileurl="file://$PROJECT_DIR$/termlab/sdk/intellij.termlab.sdk.iml" filepath="$PROJECT_DIR$/termlab/sdk/intellij.termlab.sdk.iml" />
```

- [ ] **Step 6: Create directory structure**

```bash
cd ~/projects/intellij-community
mkdir -p termlab/build/src/org/jetbrains/intellij/build/termlab
mkdir -p termlab/customization/resources/idea
mkdir -p termlab/customization/resources/META-INF
mkdir -p termlab/sdk/src/com/termlab/sdk
mkdir -p termlab/core/src/com/termlab/core/terminal
mkdir -p termlab/core/src/com/termlab/core/workspace
mkdir -p termlab/core/src/com/termlab/core/explorer
mkdir -p termlab/core/src/com/termlab/core/palette
mkdir -p termlab/core/src/com/termlab/core/miniwindow
mkdir -p termlab/core/src/com/termlab/core/actions
mkdir -p termlab/core/resources/META-INF
mkdir -p termlab/docs/plans
```

- [ ] **Step 7: Commit**

```bash
git add termlab/ .idea/modules.xml
git commit -m "feat(termlab): add module definitions and directory structure"
```

---

## Task 2: Product Branding and Configuration

**Files:**
- Create: `termlab/customization/resources/idea/TermLabApplicationInfo.xml`
- Create: `termlab/customization/resources/META-INF/TermLabPlugin.xml`
- Create: `termlab/build/src/org/jetbrains/intellij/build/termlab/TermLabProperties.kt`
- Create: `termlab/build/src/TermLabInstallersBuildTarget.kt`

- [ ] **Step 1: Create `TermLabApplicationInfo.xml`**

```xml
<component xmlns="http://jetbrains.org/intellij/schema/application-info">
  <version major="0" minor="1"/>
  <company name="TermLab" url="https://github.com/termlab"/>
  <build number="CN-__BUILD__" date="__BUILD_DATE__"/>
  <names product="TermLab" fullname="TermLab" script="termlab"
         motto="Terminal-Driven Workstation"/>
  <essential-plugin>com.termlab.core</essential-plugin>
  <essential-plugin>com.intellij.modules.json</essential-plugin>
</component>
```

Note: Icons (logo, svg) are omitted for now. They can be added later as SVG files. The build system will warn but won't fail without them.

- [ ] **Step 2: Create `TermLabPlugin.xml`**

This is the product plugin descriptor — it defines what the TermLab product includes at the platform level.

```xml
<idea-plugin xmlns:xi="http://www.w3.org/2001/XInclude">
  <module value="com.termlab.modules.terminal"/>

  <xi:include href="/META-INF/PlatformLangPlugin.xml"/>

  <content namespace="termlab">
  </content>
</idea-plugin>
```

Note: The exact content of this file may need adjustment based on what `essentialMinimal()` generates. The generator tool will help validate this. Start minimal and add includes as needed.

- [ ] **Step 3: Create `TermLabProperties.kt`**

```kotlin
package org.jetbrains.intellij.build.termlab

import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.intellij.build.ApplicationInfoProperties
import org.jetbrains.intellij.build.JetBrainsProductProperties
import org.jetbrains.intellij.build.productLayout.CommunityModuleSets
import org.jetbrains.intellij.build.productLayout.ProductModulesContentSpec
import org.jetbrains.intellij.build.productLayout.productModules
import java.nio.file.Path

class TermLabProperties(private val communityHomeDir: Path) : JetBrainsProductProperties() {
  init {
    platformPrefix = "TermLab"
    applicationInfoModule = "intellij.termlab.customization"
    scrambleMainJar = false
    useSplash = false
    buildCrossPlatformDistribution = false

    productLayout.productImplementationModules = listOf(
      "intellij.platform.starter",
      "intellij.termlab.customization",
    )

    productLayout.bundledPluginModules = persistentListOf(
      "intellij.termlab.core",
      "intellij.sh.plugin",
      "intellij.textmate.plugin",
      "intellij.yaml",
      "intellij.toml",
      "intellij.json",
    )

    productLayout.skipUnresolvedContentModules = true
  }

  override val baseFileName: String
    get() = "termlab"

  override fun getProductContentDescriptor(): ProductModulesContentSpec = productModules {
    alias("com.termlab.modules.terminal")

    moduleSet(CommunityModuleSets.essentialMinimal())

    allowMissingDependencies(
      "com.intellij.modules.java",
      "com.intellij.modules.idea",
      "com.intellij.modules.lang",
    )

    bundledPlugins(productLayout.bundledPluginModules)
  }

  override fun getSystemSelector(appInfo: ApplicationInfoProperties, buildNumber: String): String {
    return "TermLab${appInfo.majorVersion}.${appInfo.minorVersionMainPart}"
  }

  override fun getBaseArtifactName(appInfo: ApplicationInfoProperties, buildNumber: String): String {
    return "termlab-$buildNumber"
  }

  override fun getOutputDirectoryName(appInfo: ApplicationInfoProperties): String = "termlab"
}
```

Note: The `allowMissingDependencies` list may need to be expanded based on validation errors. The `skipUnresolvedContentModules = true` helps during initial setup. These can be tightened once the product builds cleanly.

- [ ] **Step 4: Create `TermLabInstallersBuildTarget.kt`**

```kotlin
package org.jetbrains.intellij.build.termlab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.BuildPaths.Companion.COMMUNITY_ROOT
import org.jetbrains.intellij.build.buildDistributions
import org.jetbrains.intellij.build.impl.createBuildContext

object TermLabInstallersBuildTarget {
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
        productProperties = TermLabProperties(COMMUNITY_ROOT.communityRoot),
        options = options,
      )
      buildDistributions(context)
    }
  }
}
```

- [ ] **Step 5: Commit**

```bash
git add termlab/build/ termlab/customization/
git commit -m "feat(termlab): product properties, branding, and build target"
```

---

## Task 3: Run Configuration

**Files:**
- Create: `.idea/runConfigurations/TermLab.xml`

- [ ] **Step 1: Create the run configuration**

```xml
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="TermLab" type="Application" factoryName="Application">
    <option name="ALTERNATIVE_JRE_PATH" value="BUNDLED" />
    <option name="ALTERNATIVE_JRE_PATH_ENABLED" value="true" />
    <log_file alias="idea.log" path="$PROJECT_DIR$/system/termlab/log/idea.log" />
    <option name="MAIN_CLASS_NAME" value="com.intellij.idea.Main" />
    <module name="intellij.termlab.main" />
    <shortenClasspath name="ARGS_FILE" />
    <option name="VM_PARAMETERS" value="-Xmx2g -XX:ReservedCodeCacheSize=240m -XX:SoftRefLRUPolicyMSPerMB=50 -XX:MaxJavaStackTraceDepth=10000 -ea -Dsun.io.useCanonCaches=false -Dapple.laf.useScreenMenuBar=true -Dsun.awt.disablegrab=true -Didea.jre.check=true -Didea.is.internal=true -Didea.debug.mode=true -Djdk.attach.allowAttachSelf -Dfus.internal.test.mode=true -Dkotlinx.coroutines.debug=off -Djdk.module.illegalAccess.silent=true --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.nio.charset=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.base/java.time=ALL-UNNAMED --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/jdk.internal.vm=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED --add-opens=java.desktop/com.apple.laf=ALL-UNNAMED --add-opens=java.desktop/com.sun.java.swing.plaf.gtk=ALL-UNNAMED --add-opens=java.desktop/java.awt.dnd.peer=ALL-UNNAMED --add-opens=java.desktop/java.awt.event=ALL-UNNAMED --add-opens=java.desktop/java.awt.image=ALL-UNNAMED --add-opens=java.desktop/java.awt.peer=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED --add-opens=java.desktop/javax.swing.text=ALL-UNNAMED --add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED --add-opens=java.desktop/javax.swing.text.html.parser=ALL-UNNAMED --add-opens=java.desktop/javax.swing=ALL-UNNAMED --add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED --add-opens=java.desktop/sun.awt.datatransfer=ALL-UNNAMED --add-opens=java.desktop/sun.awt.image=ALL-UNNAMED --add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED --add-opens=java.desktop/sun.awt=ALL-UNNAMED --add-opens=java.desktop/sun.font=ALL-UNNAMED --add-opens=java.desktop/sun.java2d=ALL-UNNAMED --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED --add-opens=java.desktop/sun.swing=ALL-UNNAMED --add-opens=jdk.attach/sun.tools.attach=ALL-UNNAMED --add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED --add-opens=jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED --add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED -Didea.platform.prefix=TermLab -Didea.config.path=../config/termlab -Didea.system.path=../system/termlab -Didea.trust.all.projects=true -Dnosplash=true -Dide.no.tips.dialog=true" />
    <option name="WORKING_DIRECTORY" value="$PROJECT_DIR$/bin" />
    <method v="2">
      <option name="Make" enabled="true" />
    </method>
  </configuration>
</component>
```

The critical VM parameters:
- `-Didea.platform.prefix=TermLab` — must match `TermLabProperties.platformPrefix`
- `-Didea.config.path=../config/termlab` — separate config from other products
- `-Didea.system.path=../system/termlab` — separate system cache
- `-Didea.trust.all.projects=true` — bypass trust dialog
- `-Dnosplash=true` — skip splash screen

- [ ] **Step 2: Commit**

```bash
git add .idea/runConfigurations/TermLab.xml
git commit -m "feat(termlab): add IntelliJ run configuration for TermLab"
```

---

## Task 4: Plugin SDK Interfaces

**Files:**
- Create: `termlab/sdk/src/com/termlab/sdk/TerminalSessionProvider.java`
- Create: `termlab/sdk/src/com/termlab/sdk/CommandPaletteContributor.java`
- Create: `termlab/sdk/src/com/termlab/sdk/CredentialProvider.java`
- Create: `termlab/sdk/src/com/termlab/sdk/PaletteItem.java`

These files are identical to what was created in the standalone project. Copy from `~/projects/termlab_2/termlab-sdk/src/main/java/com/termlab/sdk/`.

- [ ] **Step 1: Copy SDK interfaces from the standalone project**

```bash
cp ~/projects/termlab_2/termlab-sdk/src/main/java/com/termlab/sdk/TerminalSessionProvider.java termlab/sdk/src/com/termlab/sdk/
cp ~/projects/termlab_2/termlab-sdk/src/main/java/com/termlab/sdk/CommandPaletteContributor.java termlab/sdk/src/com/termlab/sdk/
cp ~/projects/termlab_2/termlab-sdk/src/main/java/com/termlab/sdk/CredentialProvider.java termlab/sdk/src/com/termlab/sdk/
cp ~/projects/termlab_2/termlab-sdk/src/main/java/com/termlab/sdk/PaletteItem.java termlab/sdk/src/com/termlab/sdk/
```

- [ ] **Step 2: Verify the imports work**

The SDK interfaces import `com.jediterm.terminal.TtyConnector`. In the IntelliJ source tree, JediTerm is available via the `intellij.libraries.jediterm.core` module, so the import should resolve. Verify by opening IntelliJ IDEA and checking that the files have no red squiggles.

- [ ] **Step 3: Commit**

```bash
git add termlab/sdk/
git commit -m "feat(termlab): plugin SDK interfaces — TerminalSessionProvider, CredentialProvider, CommandPaletteContributor"
```

---

## Task 5: Core Plugin Code

**Files:**
- Create: all files under `termlab/core/src/com/termlab/core/`
- Create: `termlab/core/resources/META-INF/plugin.xml`

- [ ] **Step 1: Copy core plugin code from the standalone project**

```bash
# Terminal
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/terminal/TermLabTerminalFileType.java termlab/core/src/com/termlab/core/terminal/
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/terminal/TermLabTerminalVirtualFile.java termlab/core/src/com/termlab/core/terminal/
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/terminal/TermLabTerminalEditorProvider.java termlab/core/src/com/termlab/core/terminal/
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/terminal/TermLabTerminalEditor.java termlab/core/src/com/termlab/core/terminal/
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/terminal/TermLabTerminalSettings.java termlab/core/src/com/termlab/core/terminal/
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/terminal/LocalPtySessionProvider.java termlab/core/src/com/termlab/core/terminal/
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/terminal/OscTrackingTtyConnector.java termlab/core/src/com/termlab/core/terminal/

# Workspace
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/workspace/WorkspaceState.java termlab/core/src/com/termlab/core/workspace/
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/workspace/WorkspaceManager.java termlab/core/src/com/termlab/core/workspace/
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/workspace/WorkspaceSerializer.java termlab/core/src/com/termlab/core/workspace/

# Explorer, palette, miniwindow
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/explorer/CwdSyncManager.java termlab/core/src/com/termlab/core/explorer/
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/palette/TerminalPaletteContributor.java termlab/core/src/com/termlab/core/palette/
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/miniwindow/MiniTerminalWindow.java termlab/core/src/com/termlab/core/miniwindow/

# Actions
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/actions/*.java termlab/core/src/com/termlab/core/actions/

# Startup/close listeners
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/TermLabStartupActivity.java termlab/core/src/com/termlab/core/
cp ~/projects/termlab_2/termlab-core/src/main/java/com/termlab/core/TermLabProjectCloseListener.java termlab/core/src/com/termlab/core/
```

- [ ] **Step 2: Remove `TermLabAppLifecycleListener.java`**

This was needed in the standalone project to bypass the welcome screen. In the source build, the product configuration handles this. Do NOT copy `TermLabAppLifecycleListener.java`.

- [ ] **Step 3: Copy and verify plugin.xml**

```bash
cp ~/projects/termlab_2/termlab-core/src/main/resources/META-INF/plugin.xml termlab/core/resources/META-INF/plugin.xml
```

Remove the `<applicationListeners>` block that references `TermLabAppLifecycleListener` — it doesn't exist in the source build.

The plugin.xml should contain:
- Extension points (terminalSessionProvider, commandPaletteContributor, credentialProvider)
- Extensions (fileEditorProvider, postStartupActivity, projectService x2, searchEverywhereContributor)
- applicationListeners (only TermLabProjectCloseListener)
- Actions (all terminal actions, command palette, workspace actions)

- [ ] **Step 4: Fix imports if needed**

In the standalone project, JediTerm classes came from the external jar. In the IntelliJ source tree, they're in platform modules. The import paths should be the same (`com.jediterm.terminal.*`) — verify they resolve.

Similarly, `com.pty4j.*` should resolve from the `intellij.libraries.pty4j` module.

`com.google.gson.*` should resolve from the platform's Gson library.

- [ ] **Step 5: Commit**

```bash
git add termlab/core/
git commit -m "feat(termlab): core plugin — terminal editor, workspace, CWD sync, palette, mini-window, actions"
```

---

## Task 6: Build and Run

- [ ] **Step 1: Open the IntelliJ project**

Open `~/projects/intellij-community` in IntelliJ IDEA. Wait for indexing to complete. The new TermLab modules should appear in the Project view under `termlab/`.

- [ ] **Step 2: Fix compilation errors**

Build the project (`Build > Build Project` or `Cmd+F9`). Fix any compilation errors in the TermLab modules:
- Missing imports: adjust module dependencies in `.iml` files
- API differences: JediTerm/pty4j classes may have slightly different APIs when used as platform modules vs external jars
- Gson availability: if Gson isn't available via the declared dependency, check `.idea/libraries/` for the correct library name

Common fixes:
- If `com.pty4j.PtyProcessBuilder` can't be found, verify the `intellij.libraries.pty4j` module name is correct
- If `DefaultSettingsProvider` can't be found in JediTerm, check `intellij.libraries.jediterm.ui` module
- If `ProcessTtyConnector` is abstract (as discovered in the standalone project), keep the anonymous subclass from the standalone code

- [ ] **Step 3: Run TermLab**

Select the "TermLab" run configuration from the dropdown and click Run (or `Shift+F10`).

Expected: A stripped-down IDE window opens with:
- No Java, VCS, Gradle, Maven, or other IDE plugins
- Only the plugins we bundled (sh, textmate, yaml, toml, json, termlab-core)
- `TermLabStartupActivity` fires, opens a terminal tab
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
8. Settings > Plugins — only TermLab Core + the 5 bundled plugins visible
9. No Structure panel, no Git, no AI sidebar, no Java tools

- [ ] **Step 5: Commit any fixes**

```bash
git add termlab/ .idea/
git commit -m "fix(termlab): resolve compilation issues for source tree build"
```

---

## Implementation Notes

**Module dependency names:** The `.iml` files reference module names like `intellij.platform.core`, `intellij.libraries.jediterm.ui`, etc. These must exactly match the module names in the IntelliJ project. If a module can't be found, search `.idea/modules.xml` for the correct name, or search for `.iml` files: `find ~/projects/intellij-community -name "intellij.libraries.jediterm*"`.

**Library names:** Project libraries are defined in `.idea/libraries/`. If `Gson` isn't the correct name, check: `ls ~/projects/intellij-community/.idea/libraries/ | grep -i gson`.

**The `essentialMinimal()` module set** already includes `librariesIde()` which bundles JediTerm, pty4j, and JNA. This means our plugin doesn't need to manage these dependencies — they're platform-provided.

**Build target (TermLabInstallersBuildTarget)** is for producing a distributable. For day-to-day development, use the IntelliJ IDEA run configuration. The build target is used later for CI/CD and jpackage distribution.

**TermLabPlugin.xml vs plugin.xml:** `TermLabPlugin.xml` is the product-level descriptor (what the TermLab product itself provides). `plugin.xml` in termlab-core is the plugin descriptor (what the TermLab Core plugin provides). These are separate concerns — the product descriptor is loaded by the platform, the plugin descriptor is loaded by the plugin system.
