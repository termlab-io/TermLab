@file:Suppress("SSBasedInspection")

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.BuildPaths.Companion.COMMUNITY_ROOT
import org.jetbrains.intellij.build.SignNativeFileMode
import org.jetbrains.intellij.build.impl.buildDistributions
import org.jetbrains.intellij.build.impl.createBuildContext
import org.jetbrains.intellij.build.termlab.TermLabProperties
import java.nio.file.Files

object TermLabInstallersBuildTarget {
  private val GENERATED_THIRD_PARTY_LICENSE_FILES = listOf(
    "third-party-libraries.html",
    "third-party-libraries.json",
  )

  @JvmStatic
  fun main(args: Array<String>) {
    val targetOs = System.getenv("TERMLAB_TARGET_OS")?.trim()?.lowercase()
    // Honor TERMLAB_TARGET_OS env var so the Makefile can build for a
    // single OS (mac / linux / windows) or all of them (default).
    // BuildOptions reads intellij.build.target.os in its init block.
    targetOs?.let {
      System.setProperty("intellij.build.target.os", it)
    }
    val reuseCompiledClasses = (System.getenv("TERMLAB_REUSE_COMPILED_CLASSES") ?: "false").toBoolean()
    val windowsOnlyBuild = targetOs == "windows"
    cleanupDevSeededThirdPartyLicenseFiles()
    val proprietaryBuildTools = createTermLabProprietaryBuildTools()
    val macSigningEnabled = proprietaryBuildTools.signTool.signNativeFileMode != SignNativeFileMode.DISABLED
    val macNotarizationEnabled = macSigningEnabled &&
      !System.getenv("APPLE_ISSUER_ID").isNullOrBlank() &&
      !System.getenv("APPLE_KEY_ID").isNullOrBlank() &&
      !System.getenv("APPLE_PRIVATE_KEY").isNullOrBlank()
    runBlocking(Dispatchers.Default) {
      // Reusing existing project output is much faster for local
      // iteration, but it is mutually exclusive with incremental JPS
      // compilation in the build scripts. CI keeps the default
      // isolated compile path; local "fast" packaging can opt in via
      // TERMLAB_REUSE_COMPILED_CLASSES=true after an IDE/Build Project.
      val options = BuildOptions(
        incrementalCompilation = !reuseCompiledClasses,
        useCompiledClassesFromProjectOutput = reuseCompiledClasses,
      ).apply {
        // Most installer builds should behave like release packaging rather
        // than local IDE dev builds. In particular, the macOS launcher UUID
        // patcher ad-hoc re-signs the launcher in development mode, which
        // leaves unsigned DMG app bundles in a malformed half-signed state.
        //
        // The one exception is a Windows-only installer build on macOS:
        // upstream NSIS packaging skips `.exe` generation on macOS unless the
        // build is marked as development-mode. TermLab exposes that path via
        // `make termlab-installers-windows` so local Windows installer testing
        // still works without weakening the all-platform/macOS packaging flow.
        isInDevelopmentMode = windowsOnlyBuild
        if (macSigningEnabled) {
          buildStepsToSkip -= BuildOptions.MAC_SIGN_STEP
        }
        else {
          buildStepsToSkip += BuildOptions.MAC_SIGN_STEP
        }
        if (macNotarizationEnabled) {
          buildStepsToSkip -= BuildOptions.MAC_NOTARIZE_STEP
        }
        else {
          buildStepsToSkip += BuildOptions.MAC_NOTARIZE_STEP
        }
        buildStepsToSkip += listOf(
          BuildOptions.WIN_SIGN_STEP,
          BuildOptions.CROSS_PLATFORM_DISTRIBUTION_STEP,
          BuildOptions.SOURCES_ARCHIVE_STEP,
          // Skip the headless product runner steps. Both launch TermLab
          // in-process to inspect bundled plugins / build a searchable
          // options index, but they trip on the essential-plugin check
          // (testRunner/externalSystem) even though those modules ARE
          // bundled via essentialMinimal → coreLang. The generated
          // distributions are still valid for manual testing.
          BuildOptions.PROVIDED_MODULES_LIST_STEP,
          BuildOptions.SEARCHABLE_OPTIONS_INDEX_STEP,
          // MAC_DMG_STEP is enabled — TermLabProperties supplies a
          // dmgImagePath, and hdiutil is macOS-native. We also leave
          // the unsigned Windows .exe installer enabled so CI can
          // publish full release artifacts for both Windows arches.
          // TermLab does not publish .sit archives. Keep DMG output and
          // skip the legacy macOS SIT publication step entirely.
          BuildOptions.MAC_SIT_PUBLICATION_STEP,
        )
      }
      val context = createBuildContext(
        projectHome = COMMUNITY_ROOT.communityRoot,
        productProperties = TermLabProperties(COMMUNITY_ROOT.communityRoot),
        proprietaryBuildTools = proprietaryBuildTools,
        options = options,
      )
      buildDistributions(context)
    }
  }

  private fun cleanupDevSeededThirdPartyLicenseFiles() {
    val licenseDir = COMMUNITY_ROOT.communityRoot.resolve("license")
    for (fileName in GENERATED_THIRD_PARTY_LICENSE_FILES) {
      Files.deleteIfExists(licenseDir.resolve(fileName))
    }
  }
}
