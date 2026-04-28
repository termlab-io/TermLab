import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.BuildPaths.Companion.COMMUNITY_ROOT
import org.jetbrains.intellij.build.CompilationContext
import org.jetbrains.intellij.build.TestingOptions
import org.jetbrains.intellij.build.TestingTasks
import org.jetbrains.intellij.build.impl.asArchived
import org.jetbrains.intellij.build.impl.createCompilationContext
import java.nio.file.Files

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
      diagnoseClasspathPaths(context.asArchived)
      val options = TestingOptions().also {
        if (it.mainModule == null) it.mainModule = "intellij.termlab.tests"
        if (it.testGroups == null) it.testGroups = "ALL_EXCLUDE_DEFINED"
      }
      TestingTasks.create(context, options).runTests()
    }
  }

  /**
   * Mirrors the path-resolution work `TestingTasksImpl.runTestsProcess` does just
   * before its `require(Files.exists)` check, so the offending path is surfaced in
   * CI logs instead of just `Failed requirement`.
   */
  private suspend fun diagnoseClasspathPaths(context: CompilationContext) {
    val out = context.outputProvider
    val testsModule = out.findRequiredModule("intellij.termlab.tests")
    val bootstrapModule = out.findRequiredModule("intellij.tools.testsBootstrap")

    val testClasspath = context.getModuleRuntimeClasspath(testsModule, forTests = true).toList()
    val bootstrapClasspath = context.getModuleRuntimeClasspath(bootstrapModule, forTests = false).toList()
    val testRoots = TERMLAB_PLUGIN_MODULES.flatMap { name ->
      out.getModuleOutputRoots(out.findRequiredModule(name), forTests = true)
    }

    listOf(
      "intellij.termlab.tests testClasspath" to testClasspath,
      "intellij.tools.testsBootstrap bootstrapClasspath" to bootstrapClasspath,
      "termlab plugins testRoots (forTests=true)" to testRoots,
    ).forEach { (label, paths) ->
      val missing = paths.filterNot { Files.exists(it) }
      System.err.println("[diagnoseClasspathPaths] $label: ${paths.size} entries, ${missing.size} missing")
      missing.forEach { System.err.println("[diagnoseClasspathPaths]   MISSING: $it") }
    }
  }
}
