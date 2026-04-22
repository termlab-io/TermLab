package org.jetbrains.intellij.build.termlab

import org.jetbrains.intellij.build.BuildContext
import org.jetbrains.intellij.build.JvmArchitecture
import org.jetbrains.intellij.build.dependencies.extractFileToCacheLocation
import org.jetbrains.intellij.build.dependencies.BuildDependenciesUtil.cleanDirectory
import org.jetbrains.intellij.build.downloadFileToCacheLocation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object TermLabBundledGitForWindows {
  internal const val VERSION = "2.54.0"
  internal const val RELEASE_TAG = "v2.54.0.windows.1"
  private const val RELEASE_BASE_URL = "https://github.com/git-for-windows/git/releases/download/$RELEASE_TAG"

  private val assets = mapOf(
    JvmArchitecture.x64 to RuntimeAsset(
      fileName = "Git-$VERSION-64-bit.tar.bz2",
      sha256 = "e1819cee60d09793dde322cdb1170e03663c41cd9265cf45246219fc5e6aeecd",
    ),
    JvmArchitecture.aarch64 to RuntimeAsset(
      fileName = "Git-$VERSION-arm64.tar.bz2",
      sha256 = "ce10b24c74ac9c724ab81e2ee30d06e7ee693977a552b8da4e434e909a641847",
    ),
  )

  suspend fun copyIntoDistribution(targetDir: Path, arch: JvmArchitecture, context: BuildContext) {
    val asset = assets[arch] ?: return
    val url = "$RELEASE_BASE_URL/${asset.fileName}"
    context.messages.info("Bundling Git for Windows $VERSION for ${arch.marketplaceName} from $url")

    val archiveFile = downloadFileToCacheLocation(url, context.paths.communityHomeDirRoot)
    verifyChecksum(archiveFile, asset.sha256)

    val extractedDir = extractFileToCacheLocation(archiveFile, context.paths.communityHomeDirRoot)
    val runtimeRoot = locateRuntimeRoot(extractedDir)
    val bundledRoot = targetDir.resolve("git")

    if (Files.exists(bundledRoot)) {
      cleanDirectory(bundledRoot)
    }
    else {
      Files.createDirectories(bundledRoot)
    }

    copyRecursively(runtimeRoot, bundledRoot)
  }

  private fun locateRuntimeRoot(extractedDir: Path): Path {
    if (Files.isRegularFile(extractedDir.resolve("bin").resolve("bash.exe"))) {
      return extractedDir
    }

    Files.list(extractedDir).use { children ->
      val roots = children.toList()
      if (roots.size == 1) {
        val child = roots.single()
        if (Files.isRegularFile(child.resolve("bin").resolve("bash.exe"))) {
          return child
        }
      }
    }

    error("Could not find Git Bash runtime root inside $extractedDir")
  }

  private fun verifyChecksum(archiveFile: Path, expectedSha256: String) {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(archiveFile).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) {
          break
        }
        if (read > 0) {
          digest.update(buffer, 0, read)
        }
      }
    }

    val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    check(actual.equals(expectedSha256, ignoreCase = true)) {
      "Checksum mismatch for ${archiveFile.fileName}: expected $expectedSha256, got $actual"
    }
  }

  private fun copyRecursively(sourceDir: Path, targetDir: Path) {
    Files.walk(sourceDir).use { stream ->
      stream.forEach { source ->
        val relative = sourceDir.relativize(source)
        val target = targetDir.resolve(relative.toString())
        if (Files.isDirectory(source)) {
          Files.createDirectories(target)
        }
        else {
          Files.createDirectories(target.parent)
          Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
      }
    }
  }

  private data class RuntimeAsset(
    val fileName: String,
    val sha256: String,
  )
}
