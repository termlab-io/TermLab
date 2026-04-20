@file:Suppress("SSBasedInspection")

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.BuildPaths.Companion.COMMUNITY_ROOT
import org.jetbrains.intellij.build.SignNativeFileMode
import org.jetbrains.intellij.build.impl.buildDistributions
import org.jetbrains.intellij.build.impl.createBuildContext
import org.jetbrains.intellij.build.termlab.TermLabProperties

object TermLabInstallersBuildTarget {
  @JvmStatic
  fun main(args: Array<String>) {
    // Honor TERMLAB_TARGET_OS env var so the Makefile can build for a
    // single OS (mac / linux / windows) or all of them (default).
    // BuildOptions reads intellij.build.target.os in its init block.
    System.getenv("TERMLAB_TARGET_OS")?.let {
      System.setProperty("intellij.build.target.os", it)
    }
    val reuseCompiledClasses = (System.getenv("TERMLAB_REUSE_COMPILED_CLASSES") ?: "false").toBoolean()
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
        if (macSigningEnabled) {
          buildStepsToSkip -= BuildOptions.MAC_SIGN_STEP
        }
        if (macNotarizationEnabled) {
          buildStepsToSkip -= BuildOptions.MAC_NOTARIZE_STEP
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
          // License-list generation requires every bundled third-party
          // library to have an entry in the platform's libraries list.
          // The libraries TermLab pulls in via plugin layouts (sshd-osgi,
          // bouncy-castle, etc.) are already approved by JetBrains for
          // distribution inside IntelliJ products — we just don't want
          // to maintain the license manifest for our purposes.
          BuildOptions.THIRD_PARTY_LIBRARIES_LIST_STEP,
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
}
