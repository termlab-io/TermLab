package org.jetbrains.intellij.build.conch

import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.intellij.build.ApplicationInfoProperties
import com.intellij.platform.pluginSystem.parser.impl.elements.ModuleLoadingRuleValue
import org.jetbrains.intellij.build.JetBrainsProductProperties
import org.jetbrains.intellij.build.LinuxDistributionCustomizer
import org.jetbrains.intellij.build.impl.PluginLayout
import org.jetbrains.intellij.build.MacDistributionCustomizer
import org.jetbrains.intellij.build.WindowsDistributionCustomizer
import org.jetbrains.intellij.build.linuxCustomizer
import org.jetbrains.intellij.build.macCustomizer
import org.jetbrains.intellij.build.productLayout.CoreModuleSets
import org.jetbrains.intellij.build.productLayout.ProductModulesContentSpec
import org.jetbrains.intellij.build.productLayout.productModules
import org.jetbrains.intellij.build.windowsCustomizer
import java.nio.file.Path

class ConchProperties(private val communityHomeDir: Path) : JetBrainsProductProperties() {
  init {
    platformPrefix = "Conch"
    applicationInfoModule = "intellij.conch.customization"
    scrambleMainJar = false
    useSplash = true
    buildCrossPlatformDistribution = false

    productLayout.productImplementationModules = listOf(
      "intellij.platform.starter",
      "intellij.conch.customization",
    )

    productLayout.bundledPluginModules = persistentListOf(
      "intellij.conch.core",
      "intellij.conch.ssh",
      "intellij.conch.vault",
      "intellij.conch.tunnels",
      "intellij.classic.ui",
      "intellij.sh.plugin",
      "intellij.textmate.plugin",
      "intellij.yaml",
      "intellij.toml",
      "intellij.json",
    )

    // Conch plugins depend on non-plugin library modules (sdk, sshd,
    // bouncy-castle) that the installer build doesn't automatically
    // bundle into a plugin's jar. We explicitly pack each into the
    // plugin at the *top* of its `<depends>` chain so downstream
    // plugins inherit the classes via classloader parent delegation
    // instead of duplicating the jars.
    //
    // Dependency chain among Conch plugins:
    //   vault (← core)
    //   ssh   (← core, vault)
    //   tunnels (← core, ssh, vault)
    //
    // Library placement:
    //   - intellij.conch.sdk           → core    (parent of everyone)
    //   - bouncy-castle-provider       → vault   (vault/ssh/tunnels need it)
    //   - sshd-osgi                    → ssh     (ssh/tunnels need it)
    productLayout.pluginLayouts = persistentListOf(
      PluginLayout.plugin(mainModuleName = "intellij.conch.core", auto = true) { spec ->
        spec.withModule("intellij.conch.sdk")
      },
      PluginLayout.plugin(mainModuleName = "intellij.conch.vault", auto = true) { spec ->
        spec.withModule("intellij.libraries.bouncy.castle.provider")
      },
      PluginLayout.plugin(mainModuleName = "intellij.conch.ssh", auto = true) { spec ->
        spec.withModule("intellij.libraries.sshd.osgi")
      },
    )

    productLayout.skipUnresolvedContentModules = true
  }

  override val baseFileName: String
    get() = "conch"

  override fun getProductContentDescriptor(): ProductModulesContentSpec = productModules {
    alias("com.conch.modules.terminal")

    // PlatformLangPlugin.xml is the canonical core plugin descriptor:
    // it xi:includes Core.xml / CoreImpl.xml / Analysis.xml / etc.,
    // registers essential application services like MessageBusFactory,
    // and declares the platform module aliases that downstream plugins
    // depend on (com.intellij.modules.platform, .lang, .xdebugger,
    // .externalSystem). Without this, Conch's generated core plugin
    // descriptor has no services and boot NPEs during service
    // container registration, plus bundled plugins fail to resolve
    // their <depends> entries.
    deprecatedInclude("intellij.platform.resources", "META-INF/PlatformLangPlugin.xml")

    // Expanded from CommunityModuleSets.essentialMinimal() so we can
    // override coreLang — its direct modules include testRunner and
    // externalSystem, which are declared EMBEDDED (required) by the
    // platform. Conch is a terminal workstation: no test runner, no
    // build-system integration, no debugger. Downgrading them to
    // OPTIONAL makes PluginManagerCore.checkEssentialPluginsAreAvailable
    // skip them (it filters for `moduleLoadingRule.required`), so we
    // don't have to drag in their transitive deps just to launch.
    //
    // Nested sets (corePlatform, coreIde, etc.) can only be overridden
    // at the set that directly declares the module — that's why we
    // target coreLang() specifically, not essentialMinimal().
    moduleSet(CoreModuleSets.coreLang()) {
      loading(
        ModuleLoadingRuleValue.OPTIONAL,
        "intellij.platform.testRunner",
        "intellij.platform.externalSystem",
      )
    }
    moduleSet(CoreModuleSets.rpcBackend())
    moduleSet(CoreModuleSets.librariesKtor())
    moduleSet(CoreModuleSets.librariesMisc())

    embeddedModule("intellij.platform.credentialStore.ui")
    embeddedModule("intellij.platform.credentialStore.impl")

    module("intellij.platform.settings.local")
    module("intellij.platform.backend")
    module("intellij.platform.project.backend")
    module("intellij.platform.progress.backend")
    module("intellij.platform.lang.impl.backend")
    module("intellij.platform.frontend")
    module("intellij.platform.monolith")
    module("intellij.platform.editor")
    module("intellij.platform.editor.backend")
    module("intellij.platform.searchEverywhere")
    module("intellij.platform.searchEverywhere.backend")
    module("intellij.platform.searchEverywhere.frontend")
    module("intellij.platform.inline.completion")

    allowMissingDependencies(
      "com.intellij.modules.java",
      "com.intellij.modules.idea",
      "com.intellij.modules.lang",
    )

    bundledPlugins(productLayout.bundledPluginModules)
  }

  override fun createWindowsCustomizer(projectHome: Path): WindowsDistributionCustomizer =
    windowsCustomizer(projectHome) {
      icoPath = "conch/customization/resources/conch.ico"
      fullName { "Conch Terminal Workstation" }
      fullNameAndVendor { "Conch Terminal Workstation" }
    }

  override fun createLinuxCustomizer(projectHome: String): LinuxDistributionCustomizer =
    linuxCustomizer(Path.of(projectHome)) {
      // No PNG icon yet — SVG from customization resources is used automatically.
    }

  override fun createMacCustomizer(projectHome: Path): MacDistributionCustomizer =
    macCustomizer(projectHome) {
      icnsPath = "conch/customization/resources/conch.icns"
      bundleIdentifier = "com.conch.terminal"
    }

  override fun getSystemSelector(appInfo: ApplicationInfoProperties, buildNumber: String): String {
    return "Conch${appInfo.majorVersion}.${appInfo.minorVersionMainPart}"
  }

  override fun getBaseArtifactName(appInfo: ApplicationInfoProperties, buildNumber: String): String {
    return "conch-$buildNumber"
  }

  override fun getOutputDirectoryName(appInfo: ApplicationInfoProperties): String = "conch"
}
