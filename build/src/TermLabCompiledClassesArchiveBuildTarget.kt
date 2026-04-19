import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.BuildPaths.Companion.COMMUNITY_ROOT
import org.jetbrains.intellij.build.impl.createBuildContext
import org.jetbrains.intellij.build.termlab.TermLabProperties
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.invariantSeparatorsPathString

object TermLabCompiledClassesArchiveBuildTarget {
  @JvmStatic
  fun main(args: Array<String>) {
    runBlocking(Dispatchers.Default) {
      val context = createBuildContext(
        projectHome = COMMUNITY_ROOT.communityRoot,
        productProperties = TermLabProperties(COMMUNITY_ROOT.communityRoot),
        options = BuildOptions().apply {
          incrementalCompilation = true
          useCompiledClassesFromProjectOutput = false
        },
      )

      context.compileProductionModules()

      val artifactDir = context.paths.artifactDir
      Files.createDirectories(artifactDir)
      val archivePath = (System.getenv("TERMLAB_COMPILED_CLASSES_ARCHIVE_OUTPUT")
                         ?.takeIf { it.isNotBlank() }
                         ?.let { Path.of(it) }
                         ?: artifactDir.resolve("termlab-compiled-classes.zip"))
      zipDirectory(context.classesOutputDirectory, archivePath)
      context.notifyArtifactBuilt(archivePath)
      println("Compiled classes archive: $archivePath")
    }
  }

  private fun zipDirectory(sourceDir: Path, archivePath: Path) {
    Files.createDirectories(archivePath.parent)
    ZipOutputStream(Files.newOutputStream(archivePath)).use { zip ->
      Files.walk(sourceDir).use { paths ->
        paths
          .filter { it != sourceDir }
          .sorted()
          .forEach { path ->
            val relativePath = sourceDir.relativize(path).invariantSeparatorsPathString
            val entryName = if (Files.isDirectory(path)) "$relativePath/" else relativePath
            zip.putNextEntry(ZipEntry(entryName))
            if (Files.isRegularFile(path)) {
              Files.newInputStream(path).use { input -> input.copyTo(zip) }
            }
            zip.closeEntry()
          }
      }
    }
  }
}
