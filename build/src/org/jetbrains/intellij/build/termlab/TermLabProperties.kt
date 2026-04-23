package org.jetbrains.intellij.build.termlab

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.plus
import org.jetbrains.intellij.build.ApplicationInfoProperties
import org.jetbrains.intellij.build.CommunityRepositoryModules
import org.jetbrains.intellij.build.CommunityLibraryLicenses
import com.intellij.platform.pluginSystem.parser.impl.elements.ModuleLoadingRuleValue
import org.jetbrains.intellij.build.JetBrainsProductProperties
import org.jetbrains.intellij.build.JvmArchitecture
import org.jetbrains.intellij.build.LinuxDistributionCustomizer
import org.jetbrains.intellij.build.impl.PluginLayout
import org.jetbrains.intellij.build.MacDistributionCustomizer
import org.jetbrains.intellij.build.WindowsDistributionCustomizer
import org.jetbrains.intellij.build.linuxCustomizer
import org.jetbrains.intellij.build.macCustomizer
import org.jetbrains.intellij.build.productLayout.CoreModuleSets
import org.jetbrains.intellij.build.productLayout.ProductModulesContentSpec
import org.jetbrains.intellij.build.productLayout.productModules
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class TermLabProperties(private val communityHomeDir: Path) : JetBrainsProductProperties() {
  init {
    platformPrefix = "TermLab"
    applicationInfoModule = "intellij.termlab.customization"
    scrambleMainJar = false
    useSplash = true
    buildCrossPlatformDistribution = false

    productLayout.productImplementationModules = listOf(
      "intellij.platform.starter",
      "intellij.termlab.customization",
    )

    productLayout.bundledPluginModules = persistentListOf(
      "intellij.termlab.core",
      "intellij.termlab.ssh",
      "intellij.termlab.vault",
      "intellij.termlab.tunnels",
      "intellij.termlab.share",
      "intellij.termlab.sftp",
      "intellij.termlab.editor",
      "intellij.termlab.runner",
      "intellij.termlab.search",
      "intellij.termlab.sysinfo",
      "intellij.classic.ui",
      "intellij.textmate.plugin",
    )

    // TermLab plugins depend on non-plugin library modules (sdk, sshd,
    // bouncy-castle) that the installer build doesn't automatically
    // bundle into a plugin's jar. We explicitly pack each into the
    // plugin at the *top* of its `<depends>` chain so downstream
    // plugins inherit the classes via classloader parent delegation
    // instead of duplicating the jars.
    //
    // Dependency chain among TermLab plugins:
    //   vault (← core)
    //   ssh   (← core, vault)
    //   tunnels (← core, ssh, vault)
    //
    // Library placement:
    //   - intellij.termlab.sdk           → core    (parent of everyone)
    //   - bouncy-castle-provider       → vault   (vault/ssh/tunnels need it)
    //   - sshd-osgi                    → ssh     (ssh/tunnels need it)
    // Keep the upstream community plugin layout rules for bundled JetBrains
    // plugins like TextMate. TextMate relies on a custom layout hook that
    // copies its built-in grammar bundles into `plugins/textmate/lib/bundles`;
    // replacing the full pluginLayouts list drops that hook and installers
    // ship without syntax bundles.
    productLayout.pluginLayouts = CommunityRepositoryModules.COMMUNITY_REPOSITORY_PLUGINS + persistentListOf(
      PluginLayout.plugin(mainModuleName = "intellij.termlab.core", auto = true) { spec ->
        spec.withModule("intellij.termlab.sdk")
      },
      PluginLayout.plugin(mainModuleName = "intellij.termlab.vault", auto = true) { spec ->
        spec.withModule("intellij.libraries.bouncy.castle.provider")
      },
      PluginLayout.plugin(mainModuleName = "intellij.termlab.ssh", auto = true) { spec ->
        spec.withModule("intellij.libraries.sshd.osgi")
      },
    )

    productLayout.skipUnresolvedContentModules = true

    // JVM-level encoding defaults. Java 18+ already picks UTF-8 for
    // file.encoding, but Finder-launched Mac apps inherit a minimal
    // launchd env that can confuse stdout/stderr encoding detection.
    // Pin them so the terminal, log files, and any child process
    // output are always decoded as UTF-8 regardless of how the app
    // was launched. Deliberately NOT forcing user.language /
    // user.country so non-US locales aren't overridden.
    additionalVmOptions = persistentListOf(
      "-Dfile.encoding=UTF-8",
      "-Dstdout.encoding=UTF-8",
      "-Dstderr.encoding=UTF-8",
      // Force packaged fresh installs onto the branded default LaF.
      "-Dlaf.name.for.new.installation=TermLab Dark",
      // Disable the native fsnotifier. TermLab has no project
      // indexing so filewatcher events are useless overhead, and on
      // Linux it pops an "inotify watch limit reached" balloon on
      // any user at distro defaults. NativeFileWatcherImpl checks
      // this property before starting fsnotifier.
      "-Didea.filewatcher.disabled=true",
    )

    // Ship the product license text in the distribution's standard
    // license directory so every packaged format, including the MSI
    // we build in CI, carries the Apache 2.0 terms.
    additionalDirectoriesWithLicenses = listOf(
      communityHomeDir.resolve("termlab/customization/licenses"),
    )
    allLibraryLicenses = CommunityLibraryLicenses.LICENSES_LIST + TermLabThirdPartyLicenses.LICENSES
  }

  override val baseFileName: String
    get() = "termlab"

  override fun getProductContentDescriptor(): ProductModulesContentSpec = productModules {
    alias("com.termlab.modules.terminal")

    // PlatformLangPlugin.xml is the canonical core plugin descriptor:
    // it xi:includes Core.xml / CoreImpl.xml / Analysis.xml / etc.,
    // registers essential application services like MessageBusFactory,
    // and declares the platform module aliases that downstream plugins
    // depend on (com.intellij.modules.platform, .lang, .xdebugger,
    // .externalSystem). Without this, TermLab's generated core plugin
    // descriptor has no services and boot NPEs during service
    // container registration, plus bundled plugins fail to resolve
    // their <depends> entries.
    deprecatedInclude("intellij.platform.resources", "META-INF/PlatformLangPlugin.xml")

    // Expanded from CommunityModuleSets.essentialMinimal() so we can
    // override coreLang — its direct modules include testRunner and
    // externalSystem, which are declared EMBEDDED (required) by the
    // platform. TermLab is a terminal workstation: no test runner, no
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

    // BuiltInServerManager service. Required by BrowserLauncherImpl.signUrl,
    // which is called unconditionally from BrowserUtil.browse — so without
    // this module any IDE-initiated browser launch (Download button on the
    // update dialog, Help → Web Help, "Learn More" links, etc.) throws
    // "Cannot find service org.jetbrains.ide.BuiltInServerManager".
    embeddedModule("intellij.platform.builtInServer.impl")

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
    object : WindowsDistributionCustomizer() {
      init {
        icoPath = projectHome.resolve("termlab/customization/resources/termlab.ico")
        installerImagesPath = projectHome.resolve("termlab/customization/resources/win")
      }

      override suspend fun copyAdditionalFiles(targetDir: Path, arch: JvmArchitecture, context: org.jetbrains.intellij.build.BuildContext) {
        super.copyAdditionalFiles(targetDir, arch, context)
        TermLabBundledGitForWindows.copyIntoDistribution(targetDir, arch, context)
      }

      override fun getFullNameIncludingEdition(appInfo: ApplicationInfoProperties): String {
        return packagedProductName(appInfo)
      }

      override fun getFullNameIncludingEditionAndVendor(appInfo: ApplicationInfoProperties): String {
        return packagedProductName(appInfo)
      }

      override fun getNameForInstallDirAndDesktopShortcut(appInfo: ApplicationInfoProperties, buildNumber: String): String {
        return packagedProductName(appInfo)
      }
    }

  override fun createLinuxCustomizer(projectHome: String): LinuxDistributionCustomizer =
    linuxCustomizer(Path.of(projectHome)) {
      // No PNG icon yet — SVG from customization resources is used automatically.
    }

  override fun createMacCustomizer(projectHome: Path): MacDistributionCustomizer =
    macCustomizer(projectHome) {
      icnsPath = "termlab/customization/resources/termlab.icns"
      bundleIdentifier = "com.termlab.terminal"
      rootDirectoryName { appInfo, _ -> "${macBundleFileName(appInfo)}.app" }
      // Multi-resolution TIFF (455x296 1x + 910x592 2x retina) with
      // the TermLab palette + "Drag to Applications" hint. Required for
      // MAC_DMG_STEP — without it the DMG build fails with
      // "Path to background image for DMG is not specified".
      dmgImagePath = "termlab/customization/resources/mac/dmg_background.tiff"
      copyAdditionalFiles { targetDir, _, context ->
        normalizeMacBundleName(targetDir.resolve("Info.plist"), context.applicationInfo)
      }
    }

  override fun getSystemSelector(appInfo: ApplicationInfoProperties, buildNumber: String): String {
    return "TermLab${appInfo.majorVersion}.${appInfo.minorVersionMainPart}"
  }

  override fun getBaseArtifactName(appInfo: ApplicationInfoProperties, buildNumber: String): String {
    // The buildNumber (e.g. 262.1.1) is structurally locked — leading
    // IntelliJ branch + 3-segment SemVer cap leaves no room for the 0.x
    // major in 0.1.1. Prepend the product version (from version@suffix,
    // stripped of the dev-mode " (sha)" tail) so humans can read the
    // real version off the filename.
    val productVersion = appInfo.versionSuffix!!.substringBefore(' ')
    return "termlab-$productVersion-$buildNumber"
  }

  override fun getOutputDirectoryName(appInfo: ApplicationInfoProperties): String = "termlab"
}

private fun packagedProductName(appInfo: ApplicationInfoProperties): String {
  return if (appInfo.isEAP) "TermLab EAP" else "TermLab"
}

private fun macBundleFileName(appInfo: ApplicationInfoProperties): String {
  // On this macOS build, `hdiutil create -srcfolder` fails with
  // "could not access /Volumes/TermLab EAP/TermLab EAP.app" when the
  // EAP bundle filename and DMG volume label both contain the spaced
  // "TermLab EAP" form. Keep the bundle filename hyphenated for DMG
  // packaging while `normalizeMacBundleName` preserves the nicer
  // display name inside Info.plist.
  return if (appInfo.isEAP) "${appInfo.fullProductName}-EAP" else packagedProductName(appInfo)
}

private fun normalizeMacBundleName(infoPlistPath: Path, appInfo: ApplicationInfoProperties) {
  if (!appInfo.isEAP || !infoPlistPath.exists()) {
    return
  }

  val currentName = "${appInfo.fullProductName}-EAP"
  val desiredName = packagedProductName(appInfo)
  val original = Files.readString(infoPlistPath)
  val updated = original.replace(
    "<string>$currentName</string>",
    "<string>$desiredName</string>",
  )
  if (updated != original) {
    Files.writeString(infoPlistPath, updated)
  }
}
