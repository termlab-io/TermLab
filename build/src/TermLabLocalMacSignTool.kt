import kotlinx.collections.immutable.PersistentMap
import org.jetbrains.intellij.build.BuildContext
import org.jetbrains.intellij.build.MacOsCodesignIdentity
import org.jetbrains.intellij.build.OsFamily
import org.jetbrains.intellij.build.ProprietaryBuildTools
import org.jetbrains.intellij.build.SignNativeFileMode
import org.jetbrains.intellij.build.SignTool
import org.jetbrains.intellij.build.JvmArchitecture
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.regex.Pattern
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

    fun fromKeychain(): TermLabMacSigningConfig? {
      val process = ProcessBuilder("security", "find-identity", "-v", "-p", "codesigning")
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      val exitCode = process.waitFor()
      if (exitCode != 0) {
        return null
      }

      val matcher = KEYCHAIN_DEVELOPER_ID_PATTERN.matcher(output)
      if (!matcher.find()) {
        return null
      }

      val teamName = matcher.group(1)?.trim().orEmpty()
      val teamId = matcher.group(2)?.trim().orEmpty()
      if (teamName.isEmpty() || teamId.isEmpty()) {
        return null
      }
      return TermLabMacSigningConfig(teamName = teamName, teamId = teamId)
    }

    private val KEYCHAIN_DEVELOPER_ID_PATTERN: Pattern = Pattern.compile(
      "\"Developer ID Application: (.+) \\(([A-Z0-9]+)\\)\""
    )
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
        signPath(file = file, entitlements = null, useRuntime = useRuntime)
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
  val macSigning = TermLabMacSigningConfig.fromEnv() ?: TermLabMacSigningConfig.fromKeychain()
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
