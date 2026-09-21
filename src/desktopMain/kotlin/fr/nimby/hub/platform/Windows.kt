package fr.nimby.hub.platform

import fr.nimby.hub.model.hubJson
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.*

/** Narrow OS adapter: all install policy, validation and transactions live in Kotlin. */
open class Windows(private val programsDirectory: Path = Path(System.getenv("APPDATA") ?: "", "Microsoft/Windows/Start Menu/Programs")) : DesktopPlatform {
    override val supported get() = System.getProperty("os.name").startsWith("Windows")

    private fun invoke(action: String, vararg values: Pair<String, String>): String {
        check(supported) { "L'installation du SDK et des mods nécessite Windows." }
        val request = buildJsonObject { put("action", action); values.forEach { (key, value) -> put(key, value) } }
        // Paths travel as JSON on stdin, never interpolated into a shell command.
        val resource = checkNotNull(javaClass.getResourceAsStream("/windows.ps1")) { "Adaptateur Windows absent" }
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
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", directory.resolve("loader/install-proxy.ps1").toString(), "-Action", action,
            "-GameDirectory", game.toString(), "-SourceDirectory", directory.resolve("loader").toString()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Chargeur SDK : $output" }
    }
}
