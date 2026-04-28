import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.BuildPaths.Companion.COMMUNITY_ROOT
import org.jetbrains.intellij.build.TestingOptions
import org.jetbrains.intellij.build.TestingTasks
import org.jetbrains.intellij.build.impl.createCompilationContext

object TermLabRunTestsBuildTarget {
  private val TERMLAB_PLUGIN_MODULES = listOf(
    "intellij.termlab.core",
    "intellij.termlab.editor",
    "intellij.termlab.proxmox",
    "intellij.termlab.runner",
    "intellij.termlab.search",
    "intellij.termlab.sftp",
    "intellij.termlab.share",
    "intellij.termlab.ssh",
    "intellij.termlab.sysinfo",
    "intellij.termlab.tunnels",
    "intellij.termlab.vault",
  )

  @JvmStatic
  fun main(args: Array<String>) {
    // TestingTasks.guessTestModulesForGroupsAndPatterns calls FileSystems.newFileSystem
    // on each candidate test-output root, which only works for ZIP/JAR files. Setting
    // USE_ARCHIVED_COMPILED_CLASSES routes the context through ArchivedCompilationContext,
    // which archives compiled-class directories to JARs and returns those JAR paths.
    //
    // ArchivedCompilationOutputStorage.getArchived() short-circuits and returns paths
    // unchanged when they don't start with classesOutputDirectory; without classOutDir
    // pointing at the JPS project output (out/), the archive layer is silently bypassed
    // and TestingTasks's downstream Files.exists() check on non-existent paths trips.
    val intellijRoot = COMMUNITY_ROOT.communityRoot
    System.setProperty(TestingOptions.USE_ARCHIVED_COMPILED_CLASSES, "true")
    runBlocking(Dispatchers.Default) {
      val context = createCompilationContext(
        projectHome = intellijRoot,
        defaultOutputRoot = intellijRoot.resolve("out/termlab-tests"),
        options = BuildOptions().also {
          it.useTestCompilationOutput = true
          it.classOutDir = intellijRoot.resolve("out").toString()
        },
      )
      // TestingTasks compiles only mainModule's tests by default. Our mainModule
      // is a meta-aggregator with no test sources, so we explicitly compile the
      // 11 plugin modules' test outputs first.
      context.compileModules(
        moduleNames = listOf("intellij.tools.testsBootstrap"),
        includingTestsInModules = TERMLAB_PLUGIN_MODULES,
      )
      val options = TestingOptions().also {
        if (it.mainModule == null) it.mainModule = "intellij.termlab.tests"
        if (it.testGroups == null) it.testGroups = "ALL_EXCLUDE_DEFINED"
      }
      TestingTasks.create(context, options).runTests()
    }
  }
}
