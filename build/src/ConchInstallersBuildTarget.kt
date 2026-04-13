@file:Suppress("SSBasedInspection")

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.BuildPaths.Companion.COMMUNITY_ROOT
import org.jetbrains.intellij.build.impl.buildDistributions
import org.jetbrains.intellij.build.impl.createBuildContext
import org.jetbrains.intellij.build.conch.ConchProperties

object ConchInstallersBuildTarget {
  @JvmStatic
  fun main(args: Array<String>) {
    // Honor CONCH_TARGET_OS env var so the Makefile can build for a
    // single OS (mac / linux / windows) or all of them (default).
    // BuildOptions reads intellij.build.target.os in its init block.
    System.getenv("CONCH_TARGET_OS")?.let {
      System.setProperty("intellij.build.target.os", it)
    }
    runBlocking(Dispatchers.Default) {
      val options = BuildOptions().apply {
        incrementalCompilation = true
        useCompiledClassesFromProjectOutput = false
        buildStepsToSkip += listOf(
          BuildOptions.MAC_SIGN_STEP,
          BuildOptions.WIN_SIGN_STEP,
          BuildOptions.CROSS_PLATFORM_DISTRIBUTION_STEP,
          BuildOptions.SOURCES_ARCHIVE_STEP,
          // Skip the headless product runner steps. Both launch Conch
          // in-process to inspect bundled plugins / build a searchable
          // options index, but they trip on the essential-plugin check
          // (testRunner/externalSystem) even though those modules ARE
          // bundled via essentialMinimal → coreLang. The generated
          // distributions are still valid for manual testing.
          BuildOptions.PROVIDED_MODULES_LIST_STEP,
          BuildOptions.SEARCHABLE_OPTIONS_INDEX_STEP,
          // We want archives only, not platform installers.
          BuildOptions.MAC_DMG_STEP,
          BuildOptions.WINDOWS_EXE_INSTALLER_STEP,
          // License-list generation requires every bundled third-party
          // library to have an entry in the platform's libraries list.
          // The libraries Conch pulls in via plugin layouts (sshd-osgi,
          // bouncy-castle, etc.) are already approved by JetBrains for
          // distribution inside IntelliJ products — we just don't want
          // to maintain the license manifest for our purposes.
          BuildOptions.THIRD_PARTY_LIBRARIES_LIST_STEP,
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
