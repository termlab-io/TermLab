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
