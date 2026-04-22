import kotlinx.collections.immutable.PersistentMap
import org.jetbrains.intellij.build.BuildContext
import org.jetbrains.intellij.build.MacOsCodesignIdentity
import org.jetbrains.intellij.build.OsFamily
import org.jetbrains.intellij.build.ProprietaryBuildTools
import org.jetbrains.intellij.build.SignNativeFileMode
import org.jetbrains.intellij.build.SignTool
import org.jetbrains.intellij.build.JvmArchitecture
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.pathString

private const val MAC_APP_ZIP_CONTENT_TYPE = "application/x-mac-app-zip"

data class TermLabMacSigningConfig(
  val teamName: String,
  val teamId: String,
) {
  val identity = MacOsCodesignIdentity(teamName = teamName, teamID = teamId)

  companion object {
    fun fromEnv(): TermLabMacSigningConfig? {
      val teamName = System.getenv("TERMLAB_MAC_CODESIGN_TEAM_NAME")?.trim().orEmpty()
      val teamId = System.getenv("TERMLAB_MAC_CODESIGN_TEAM_ID")?.trim().orEmpty()
      if (teamName.isEmpty() || teamId.isEmpty()) {
        return null
      }
      return TermLabMacSigningConfig(teamName = teamName, teamId = teamId)
    }
  }
}

class TermLabLocalMacSignTool(
  private val config: TermLabMacSigningConfig,
) : SignTool {
  override val macOsCodesignIdentity: MacOsCodesignIdentity = config.identity

  override val signNativeFileMode: SignNativeFileMode
    get() = SignNativeFileMode.PREPARE

  override suspend fun signFiles(files: List<Path>, context: BuildContext?, options: PersistentMap<String, String>) {
    if (files.isEmpty()) {
      return
    }

    val contentType = options["contentType"].orEmpty()
    if (contentType == "application/x-exe") {
      return
    }
    val entitlements = options["mac_codesign_entitlements"]?.takeIf { it.isNotBlank() }?.let(Path::of)
    val useRuntime = options["mac_codesign_options"]?.contains("runtime") == true

    for (file in files) {
      if (!Files.exists(file)) {
        error("Cannot sign missing file: $file")
      }

      if (contentType == MAC_APP_ZIP_CONTENT_TYPE || file.extension == "sit" || file.extension == "zip") {
        signMacArchive(file = file, entitlements = entitlements, useRuntime = useRuntime)
      }
      else {
        signPath(file = file, entitlements = entitlements, useRuntime = useRuntime)
      }
    }
  }

  override suspend fun signFilesWithGpg(files: List<Path>, context: BuildContext) {
    error("GPG signing is not supported by the local TermLab mac sign tool")
  }

  override suspend fun getPresignedLibraryFile(path: String, libName: String, libVersion: String, context: BuildContext): Path? = null

  override suspend fun commandLineClient(context: BuildContext, os: OsFamily, arch: JvmArchitecture): Path? = null

  private fun signMacArchive(file: Path, entitlements: Path?, useRuntime: Boolean) {
    val tempDir = Files.createTempDirectory("termlab-mac-sign-")
    try {
      runCommand(
        listOf(
          "ditto",
          "--norsrc",
          "-x",
          "-k",
          file.pathString,
          tempDir.pathString,
        )
      )

      val appPath = Files.list(tempDir).use { paths ->
        paths.filter { it.name.endsWith(".app") }.findFirst().orElseThrow {
          IllegalStateException("Expected a .app inside $file after extraction")
        }
      }

      resignNestedMacLibraries(appPath = appPath, useRuntime = useRuntime)
      signPath(file = appPath, entitlements = entitlements, useRuntime = useRuntime)

      val rebuilt = file.resolveSibling("${file.name}.signed")
      runCommand(
        listOf(
          "ditto",
          "--norsrc",
          "-c",
          "-k",
          "--keepParent",
          appPath.pathString,
          rebuilt.pathString,
        ),
        workingDirectory = tempDir,
      )
      Files.move(rebuilt, file, StandardCopyOption.REPLACE_EXISTING)
    }
    finally {
      Files.walk(tempDir)
        .sorted(Comparator.reverseOrder())
        .forEach(Files::deleteIfExists)
    }
  }

  private fun signPath(file: Path, entitlements: Path?, useRuntime: Boolean) {
    val command = mutableListOf(
      "codesign",
      "--force",
      "--sign",
      config.identity.certificateID,
      "--timestamp",
    )
    if (useRuntime) {
      command += listOf("--options", "runtime")
    }
    if (entitlements != null) {
      command += listOf("--entitlements", entitlements.pathString)
    }
    command += file.pathString
    runCommand(command)
  }

  private fun resignNestedMacLibraries(appPath: Path, useRuntime: Boolean) {
    val libDir = appPath.resolve("Contents/lib")
    if (!Files.isDirectory(libDir)) {
      return
    }

    Files.walk(libDir).use { paths ->
      paths
        .filter { Files.isRegularFile(it) && it.extension == "jar" }
        .forEach { jarPath ->
          rewriteJarWithSignedMacNatives(jarPath = jarPath, useRuntime = useRuntime)
        }
    }
  }

  private fun rewriteJarWithSignedMacNatives(jarPath: Path, useRuntime: Boolean) {
    ZipFile(jarPath.toFile()).use { zipFile ->
      val entries = buildList {
        val enumeration = zipFile.entries()
        while (enumeration.hasMoreElements()) {
          add(enumeration.nextElement())
        }
      }
      if (entries.none { entry -> !entry.isDirectory && shouldResignJarEntry(entry.name) }) {
        return
      }

      val rewrittenJar = Files.createTempFile(jarPath.parent, "${jarPath.name}.", ".signed")
      try {
        ZipOutputStream(BufferedOutputStream(Files.newOutputStream(rewrittenJar))).use { output ->
          for (entry in entries) {
            val replacement = ZipEntry(entry.name).apply {
              comment = entry.comment
              extra = entry.extra
              method = ZipEntry.DEFLATED
              if (entry.time >= 0) {
                time = entry.time
              }
            }
            output.putNextEntry(replacement)
            if (!entry.isDirectory) {
              if (shouldResignJarEntry(entry.name)) {
                val signedNative = signJarEntry(zipFile = zipFile, entry = entry, useRuntime = useRuntime)
                Files.newInputStream(signedNative).use { input -> input.copyTo(output) }
                Files.deleteIfExists(signedNative)
              }
              else {
                BufferedInputStream(zipFile.getInputStream(entry)).use { input -> input.copyTo(output) }
              }
            }
            output.closeEntry()
          }
        }
        Files.move(rewrittenJar, jarPath, StandardCopyOption.REPLACE_EXISTING)
      }
      finally {
        Files.deleteIfExists(rewrittenJar)
      }
    }
  }

  private fun signJarEntry(zipFile: ZipFile, entry: ZipEntry, useRuntime: Boolean): Path {
    val suffix = entry.name.substringAfterLast('/', "").substringAfterLast('.', "")
      .takeIf { it.isNotBlank() }
      ?.let { ".$it" }
      ?: ".bin"
    val extracted = Files.createTempFile("termlab-mac-native-", suffix)
    BufferedInputStream(zipFile.getInputStream(entry)).use { input ->
      BufferedOutputStream(Files.newOutputStream(extracted)).use { output -> input.copyTo(output) }
    }
    signPath(file = extracted, entitlements = null, useRuntime = useRuntime)
    return extracted
  }

  private fun shouldResignJarEntry(entryName: String): Boolean {
    val normalized = entryName.lowercase()
    if (normalized.endsWith(".dylib") || normalized.endsWith(".jnilib")) {
      return true
    }

    if (!normalized.contains("/darwin/") &&
        !normalized.contains("/darwin-") &&
        !normalized.contains("/mac/") &&
        !normalized.contains("/macos/") &&
        !normalized.contains("/osx/")) {
      return false
    }

    val fileName = normalized.substringAfterLast('/')
    return fileName.isNotEmpty() && !fileName.contains('.')
  }

  private fun runCommand(command: List<String>, workingDirectory: Path? = null) {
    val process = ProcessBuilder(command)
      .directory(workingDirectory?.toFile())
      .apply { environment()["COPYFILE_DISABLE"] = "1" }
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
      error("Command failed (${command.joinToString(" ")}):\n$output")
    }
  }
}

fun createTermLabProprietaryBuildTools(): ProprietaryBuildTools {
  val macSigning = TermLabMacSigningConfig.fromEnv()
  return macSigning?.let { config ->
    ProprietaryBuildTools(
      signTool = TermLabLocalMacSignTool(config),
      scrambleTool = null,
      artifactsServer = null,
      featureUsageStatisticsProperties = null,
      licenseServerHost = null,
    )
  } ?: ProprietaryBuildTools.DUMMY
}
