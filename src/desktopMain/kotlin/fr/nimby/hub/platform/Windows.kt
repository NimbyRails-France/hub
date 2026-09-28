package fr.nimby.hub.platform

import fr.nimby.hub.i18n.tr

import fr.nimby.hub.model.hubJson
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.*

/** Narrow OS adapter: all install policy, validation and transactions live in Kotlin. */
open class Windows(private val programsDirectory: Path = Path(System.getenv("APPDATA") ?: "", "Microsoft/Windows/Start Menu/Programs"),
                   private val log: (String) -> Unit = {}) : DesktopPlatform {
    override val supported get() = System.getProperty("os.name").startsWith("Windows")

    private fun invoke(action: String, vararg values: Pair<String, String>): String {
        check(supported) { tr("L'installation du SDK et des mods nécessite Windows.") }
        val request = buildJsonObject {
            put("action", action); put("language", fr.nimby.hub.i18n.I18n.language)
            values.forEach { (key, value) -> put(key, value) }
        }
        // Paths travel as JSON on stdin, never interpolated into a shell command.
        val resource = checkNotNull(javaClass.getResourceAsStream("/windows.ps1")) { tr("Adaptateur Windows absent") }
        val script = resource.bufferedReader().use { it.readText() }
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-OutputFormat", "Text", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
            .redirectErrorStream(true).start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(request.toString()) }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        check(process.waitFor() == 0) { output.trim() }
        return output.trim()
    }

    override fun requireClosed(game: Path, destination: Path) {
        invoke("closed", "game" to game.toString(), "destination" to destination.toString())
    }
    override fun gameRunning(game: Path): Boolean = invoke("gameRunning", "game" to game.toString()) == "true"
    override fun requestGameClose(game: Path) { invoke("requestGameClose", "game" to game.toString()) }
    override fun launchGame(game: Path) { ProcessBuilder(game.resolve("NIMBYRails.exe").toString()).directory(game.toFile()).start() }
    override fun linkTarget(path: Path): String? = invoke("linkTarget", "path" to path.toString()).ifEmpty { null }
    override fun createLink(path: Path, target: Path) { invoke("createLink", "path" to path.toString(), "target" to target.toString()) }
    override fun removeLink(path: Path, target: Path) { invoke("removeLink", "path" to path.toString(), "target" to target.toString()) }
    override fun checkTree(path: Path) { invoke("checkTree", "path" to path.toString()) }
    override fun shortcut(id: String, destination: Path, remove: Boolean) {
        invoke("shortcut", "id" to id, "destination" to destination.toString(), "programs" to programsDirectory.toString(), "remove" to remove.toString())
    }
    override fun proxy(directory: Path, game: Path, action: String) {
        require(action in setOf("Install", "Remove"))
        log(tr("Chargeur SDK {0} · jeu={1} · distribution={2}", action, game, directory))
        requireClosed(game, directory)
        val marker = listOf("NimbyRailsFranceSDK-install.json", "NimbyRailsSDK-install.json").any { game.resolve(it).exists() }
        if (action == "Install" || !marker) {
            fr.nimby.hub.platform.windows.WindowsSdkRepair.requireClean(game)
            // A verified repair has already restored SDL and removed the proxy.
            // The managed SDK distribution still exists and can be reinstalled.
            if (action == "Remove") return
        }
        fun quote(value: String) = "'${value.replace("'", "''")}'"
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
            & ${quote(directory.resolve("loader/install-proxy.ps1").toString())} -Action ${quote(action)} -GameDirectory ${quote(game.toString())} -SourceDirectory ${quote(directory.resolve("loader").toString())}
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-OutputFormat", "Text", "-ExecutionPolicy", "Bypass",
            "-EncodedCommand", encoded).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (output.isNotBlank()) log(output.trimEnd())
        check(process.waitFor() == 0) { tr("Chargeur SDK : {0}", output) }
    }
}
