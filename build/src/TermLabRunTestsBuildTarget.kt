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
      materialiseMissingClasspathStubs(context.asArchived)
      val options = TestingOptions().also {
        if (it.mainModule == null) it.mainModule = "intellij.termlab.tests"
        if (it.testGroups == null) it.testGroups = "ALL_EXCLUDE_DEFINED"
      }
      TestingTasks.create(context, options).runTests()
    }
  }

  /**
   * On CI, JPS reports a handful of unresolved transitive Maven deps (e.g.,
   * testng-7.1.0, jcommander-1.72, guice-4.1.0, guava-19.0, snakeyaml-1.21) in
   * the test runtime classpath even though nothing actually loads them — they
   * appear because some platform module's POM references them but they aren't
   * a primary library and never get downloaded. TestingTasksImpl maps the test
   * classpath through `require(Files.exists)`, so a missing JAR aborts the run.
   *
   * Materialise empty placeholder JARs at the expected paths so the existence
   * check passes; the test JVM will still find real classes via the actual
   * library jars (TestNG 7.8.0 etc.) loaded earlier on the classpath.
   */
  private suspend fun materialiseMissingClasspathStubs(context: CompilationContext) {
    val out = context.outputProvider
    val testsModule = out.findRequiredModule("intellij.termlab.tests")
    val bootstrapModule = out.findRequiredModule("intellij.tools.testsBootstrap")

    val candidates = buildSet<java.nio.file.Path> {
      addAll(context.getModuleRuntimeClasspath(testsModule, forTests = true))
      addAll(context.getModuleRuntimeClasspath(bootstrapModule, forTests = false))
    }

    val emptyJarBytes = byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    var created = 0
    for (path in candidates) {
      if (Files.exists(path)) continue
      if (path.toString().endsWith(".jar")) {
        Files.createDirectories(path.parent)
        Files.write(path, emptyJarBytes)
        System.err.println("[materialiseMissingClasspathStubs] created empty placeholder: $path")
        created++
      }
      else {
        System.err.println("[materialiseMissingClasspathStubs] non-jar missing path (left as-is): $path")
      }
    }
    if (created > 0) {
      System.err.println("[materialiseMissingClasspathStubs] created $created empty JAR placeholders")
    }
  }
}
